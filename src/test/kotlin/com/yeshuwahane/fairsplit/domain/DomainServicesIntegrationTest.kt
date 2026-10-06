package com.yeshuwahane.fairsplit.domain

import com.yeshuwahane.fairsplit.common.error.AppException
import com.yeshuwahane.fairsplit.data.repository.*
import com.yeshuwahane.fairsplit.data.table.*
import com.yeshuwahane.fairsplit.domain.model.*
import com.yeshuwahane.fairsplit.domain.repository.*
import com.yeshuwahane.fairsplit.domain.service.*
import com.yeshuwahane.fairsplit.realtime.model.RealtimeEventType
import com.yeshuwahane.fairsplit.user.repository.UserRepositoryImpl
import com.yeshuwahane.fairsplit.user.table.UsersTable
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.transactions.transaction
import java.util.UUID
import kotlin.test.*

class DomainServicesIntegrationTest {

    private lateinit var database: Database
    private val userRepository = UserRepositoryImpl()
    private val epicRepository = EpicRepositoryImpl()
    private val expenseRepository = ExpenseRepositoryImpl()
    private val settlementRepository = SettlementRepositoryImpl()
    private val activityRepository = ActivityRepositoryImpl()
    private val idempotencyRepository = IdempotencyRepositoryImpl()

    private lateinit var epicService: EpicService
    private lateinit var expenseService: ExpenseService
    private lateinit var settlementService: SettlementService
    private lateinit var idempotencyService: IdempotencyService

    @BeforeTest
    fun setUp() {
        val dbName = "test_${UUID.randomUUID().toString().replace("-", "")}"
        database = Database.connect(
            url = "jdbc:h2:mem:$dbName;DB_CLOSE_DELAY=-1;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE",
            driver = "org.h2.Driver"
        )

        transaction(database) {
            SchemaUtils.create(
                UsersTable,
                EpicsTable,
                EpicMembersTable,
                ExpensesTable,
                ExpenseSplitsTable,
                SettlementsTable,
                ActivityLogsTable,
                IdempotencyKeysTable
            )
        }

        epicService = EpicService(epicRepository, activityRepository, expenseRepository, settlementRepository)
        expenseService = ExpenseService(expenseRepository, epicRepository, settlementRepository, activityRepository)
        settlementService = SettlementService(settlementRepository, epicRepository, expenseRepository, activityRepository)
        idempotencyService = IdempotencyService(idempotencyRepository)
    }

    @Test
    fun testEpicCreationAndMembership() = kotlinx.coroutines.runBlocking {
        val userA = userRepository.create(name = "User A", email = "a@test.com")
        val epicResult = epicService.createEpic(
            userId = userA.id,
            title = "Goa Trip",
            description = "Weekend trip",
            iconUrl = null
        )
        val epic = epicResult.data

        assertEquals("Goa Trip", epic.title)
        assertTrue(epic.inviteCode.startsWith("FS-GOA"))
        assertTrue(epicResult.events.isNotEmpty())

        // User A is owner
        val members = epicService.getMembers(userA.id, epic.id)
        assertEquals(1, members.size)
        assertEquals(userA.id, members[0].userId)
        assertEquals(EpicMemberRole.OWNER, members[0].role)

        // List my epics
        val myEpics = epicService.listMyEpics(userA.id)
        assertEquals(1, myEpics.size)
        assertEquals(epic.id, myEpics[0].id)
    }

    @Test
    fun testJoinEpicWithSecondUser() = kotlinx.coroutines.runBlocking {
        val userA = userRepository.create(name = "User A")
        val userB = userRepository.create(name = "User B")

        val epic = epicService.createEpic(userA.id, "Ladakh Trip", null, null).data

        // User B joins via invite code
        val joinResult = epicService.joinEpic(userB.id, epic.inviteCode)
        val joined = joinResult.data
        assertEquals(epic.id, joined.id)
        assertTrue(joinResult.events.any { it.type == RealtimeEventType.MEMBER_JOINED })

        // Both see 2 members now
        val membersA = epicService.getMembers(userA.id, epic.id)
        val membersB = epicService.getMembers(userB.id, epic.id)
        assertEquals(2, membersA.size)
        assertEquals(2, membersB.size)
        assertTrue(membersA.any { it.userId == userB.id })
    }

    @Test
    fun testEqualExpenseAndBalanceCalculation() = kotlinx.coroutines.runBlocking {
        val userA = userRepository.create(name = "User A")
        val userB = userRepository.create(name = "User B")

        val epic = epicService.createEpic(userA.id, "Dinner Party", null, null).data
        epicService.joinEpic(userB.id, epic.inviteCode)

        // User A pays ₹1000 (100000 minor) equally between A and B
        val expenseResult = expenseService.createExpense(
            callerId = userA.id,
            epicId = epic.id,
            title = "Dinner",
            amountMinor = 100000L,
            paidByUserId = userA.id,
            splitType = SplitType.EQUAL,
            participantIds = listOf(userA.id, userB.id)
        )
        val expense = expenseResult.data

        assertEquals(100000L, expense.amountMinor)
        assertTrue(expenseResult.events.any { it.type == RealtimeEventType.EXPENSE_CREATED })
        assertTrue(expenseResult.events.any { it.type == RealtimeEventType.BALANCE_UPDATED })

        // Check splits
        val splits = expenseRepository.getSplitsForExpense(expense.id)
        assertEquals(2, splits.size)
        assertEquals(50000L, splits.first { it.userId == userA.id }.amountMinor)
        assertEquals(50000L, splits.first { it.userId == userB.id }.amountMinor)

        // Check derived balances
        val expenses = expenseRepository.findByEpicId(epic.id)
        val allSplits = expenseRepository.getSplitsForEpic(epic.id)
        val netBalances = BalanceCalculator.calculateNetBalances(
            setOf(userA.id, userB.id),
            expenses,
            allSplits,
            emptyList()
        )

        // User A paid 1000, owes 500 -> net +500 (+50000 minor)
        // User B paid 0, owes 500 -> net -500 (-50000 minor)
        assertEquals(50000L, netBalances[userA.id]?.netBalanceMinor)
        assertEquals(-50000L, netBalances[userB.id]?.netBalanceMinor)

        // Pairwise debt
        val debt = BalanceCalculator.getBilateralDebt(
            fromUserId = userB.id,
            toUserId = userA.id,
            expenses = expenses,
            splitsByExpenseId = mapOf(expense.id to splits),
            settlements = emptyList()
        )
        assertEquals(50000L, debt)
    }

    @Test
    fun testSettlementFlowAndOverpaymentRejection() = kotlinx.coroutines.runBlocking {
        val userA = userRepository.create(name = "User A")
        val userB = userRepository.create(name = "User B")

        val epic = epicService.createEpic(userA.id, "Trip", null, null).data
        epicService.joinEpic(userB.id, epic.inviteCode)

        // Expense: A paid ₹100 (10000 minor) split equally (5000 each)
        expenseService.createExpense(
            callerId = userA.id,
            epicId = epic.id,
            title = "Taxi",
            amountMinor = 10000L,
            paidByUserId = userA.id,
            splitType = SplitType.EQUAL,
            participantIds = listOf(userA.id, userB.id)
        )

        // Overpayment rejection: B tries to pay A ₹60 (6000 minor), but only owes ₹50 (5000 minor)
        assertFailsWith<AppException.Validation> {
            settlementService.createSettlement(
                callerId = userB.id,
                epicId = epic.id,
                fromUserId = userB.id,
                toUserId = userA.id,
                amountMinor = 6000L
            )
        }

        // Valid settlement: B pays A ₹50 (5000 minor)
        val setResult = settlementService.createSettlement(
            callerId = userB.id,
            epicId = epic.id,
            fromUserId = userB.id,
            toUserId = userA.id,
            amountMinor = 5000L
        )
        val settlement = setResult.data
        assertEquals(5000L, settlement.amountMinor)
        assertTrue(setResult.events.any { it.type == RealtimeEventType.SETTLEMENT_CREATED })
        assertTrue(setResult.events.any { it.type == RealtimeEventType.BALANCE_UPDATED })

        // Balances become 0
        val expenses = expenseRepository.findByEpicId(epic.id)
        val splits = expenseRepository.getSplitsForEpic(epic.id)
        val settlements = settlementRepository.findByEpicId(epic.id)
        val netBalances = BalanceCalculator.calculateNetBalances(
            setOf(userA.id, userB.id),
            expenses,
            splits,
            settlements
        )
        assertEquals(0L, netBalances[userA.id]?.netBalanceMinor)
        assertEquals(0L, netBalances[userB.id]?.netBalanceMinor)

        // Bilateral debt is now 0
        val remainingDebt = BalanceCalculator.getBilateralDebt(
            userB.id,
            userA.id,
            expenses,
            splits.groupBy { it.expenseId },
            settlements
        )
        assertEquals(0L, remainingDebt)
    }

    @Test
    fun testMemberRemovalRules() = kotlinx.coroutines.runBlocking {
        val userA = userRepository.create(name = "Owner A")
        val userB = userRepository.create(name = "Member B")

        val epic = epicService.createEpic(userA.id, "Flatmates", null, null).data
        epicService.joinEpic(userB.id, epic.inviteCode)

        // Expense created where B owes money
        expenseService.createExpense(
            callerId = userA.id,
            epicId = epic.id,
            title = "Groceries",
            amountMinor = 2000L,
            paidByUserId = userA.id,
            splitType = SplitType.EQUAL,
            participantIds = listOf(userA.id, userB.id)
        )

        // Owner tries to remove B who has unsettled debt -> rejected
        assertFailsWith<AppException.Validation> {
            epicService.removeMember(userA.id, epic.id, userB.id)
        }

        // Settle the debt
        settlementService.createSettlement(
            callerId = userB.id,
            epicId = epic.id,
            fromUserId = userB.id,
            toUserId = userA.id,
            amountMinor = 1000L
        )

        // Now removal succeeds
        val removeResult = epicService.removeMember(userA.id, epic.id, userB.id)
        val removed = removeResult.data
        assertTrue(removed)
        assertTrue(removeResult.events.any { it.type == RealtimeEventType.MEMBER_REMOVED })

        val membersAfter = epicService.getMembers(userA.id, epic.id)
        assertEquals(1, membersAfter.size)
        assertFalse(membersAfter.any { it.userId == userB.id })
    }

    @Test
    fun testIdempotentExecution() = kotlinx.coroutines.runBlocking {
        val userA = userRepository.create(name = "User A")
        var executionCount = 0

        val (res1, replayed1) = idempotencyService.executeWithIdempotency(
            userId = userA.id,
            key = "key-12345",
            endpoint = "/test",
            requestBody = """{"test": 1}""",
            serializer = { it },
            deserializer = { it }
        ) {
            executionCount++
            "SUCCESS_RESULT"
        }

        assertEquals("SUCCESS_RESULT", res1)
        assertFalse(replayed1)
        assertEquals(1, executionCount)

        // Call again with same key and same body
        val (res2, replayed2) = idempotencyService.executeWithIdempotency(
            userId = userA.id,
            key = "key-12345",
            endpoint = "/test",
            requestBody = """{"test": 1}""",
            serializer = { it },
            deserializer = { it }
        ) {
            executionCount++
            "SHOULD_NOT_EXECUTE"
        }

        assertEquals("SUCCESS_RESULT", res2)
        assertTrue(replayed2)
        assertEquals(1, executionCount) // Action was NOT re-executed

        // Call with same key but DIFFERENT body -> Conflict
        assertFailsWith<AppException.Validation> {
            idempotencyService.executeWithIdempotency(
                userId = userA.id,
                key = "key-12345",
                endpoint = "/test",
                requestBody = """{"different": 2}""",
                serializer = { it },
                deserializer = { it }
            ) {
                "CONFLICT"
            }
        }
        Unit
    }

    @Test
    fun testAtomicRollbackWhenSubOperationFails() = kotlinx.coroutines.runBlocking {
        val userA = userRepository.create(name = "User A")

        // 1. Transaction failure during createEpic (e.g., throwing after epicRepository.createEpic)
        // We will create a failing ActivityRepository
        val failingActivityRepo = object : ActivityRepository by activityRepository {
            override suspend fun createActivity(activity: ActivityLog): ActivityLog {
                throw RuntimeException("Database error writing activity log!")
            }
        }
        val failingEpicService = EpicService(epicRepository, failingActivityRepo, expenseRepository, settlementRepository)

        assertFailsWith<RuntimeException> {
            failingEpicService.createEpic(
                userId = userA.id,
                title = "Rollback Group",
                description = null,
                iconUrl = null
            )
        }

        // Verify that NEITHER epic nor epic_members were persisted (full rollback)
        val epics = epicRepository.findEpicsByUserId(userA.id)
        assertTrue(epics.isEmpty(), "Epic should have been rolled back!")

        // 2. Transaction failure during createExpense
        val epic = epicService.createEpic(userA.id, "Valid Group", null, null).data
        val failingExpenseService = ExpenseService(expenseRepository, epicRepository, settlementRepository, failingActivityRepo)

        assertFailsWith<RuntimeException> {
            failingExpenseService.createExpense(
                callerId = userA.id,
                epicId = epic.id,
                title = "Failing Dinner",
                amountMinor = 50000L,
                paidByUserId = userA.id,
                splitType = SplitType.EQUAL,
                participantIds = listOf(userA.id)
            )
        }

        // Verify that NO expense and NO expense splits were persisted
        val savedExpenses = expenseRepository.findByEpicId(epic.id)
        assertTrue(savedExpenses.isEmpty(), "Expense should have been rolled back!")
        val savedSplits = expenseRepository.getSplitsForEpic(epic.id)
        assertTrue(savedSplits.isEmpty(), "Expense splits should have been rolled back!")

        // 3. Transaction failure during createSettlement
        val validExpense = expenseService.createExpense(
            callerId = userA.id,
            epicId = epic.id,
            title = "Valid Dinner",
            amountMinor = 10000L,
            paidByUserId = userA.id,
            splitType = SplitType.EQUAL,
            participantIds = listOf(userA.id)
        ).data
        assertNotNull(validExpense)

        val failingSettlementService = SettlementService(settlementRepository, epicRepository, expenseRepository, failingActivityRepo)
        val userB = userRepository.create(name = "User B")
        epicService.joinEpic(userB.id, epic.inviteCode)

        // Add expense with B
        expenseService.createExpense(
            callerId = userA.id,
            epicId = epic.id,
            title = "Coffee with B",
            amountMinor = 2000L,
            paidByUserId = userA.id,
            splitType = SplitType.EQUAL,
            participantIds = listOf(userA.id, userB.id)
        )

        assertFailsWith<RuntimeException> {
            failingSettlementService.createSettlement(
                callerId = userB.id,
                epicId = epic.id,
                fromUserId = userB.id,
                toUserId = userA.id,
                amountMinor = 1000L
            )
        }

        val settlements = settlementRepository.findByEpicId(epic.id)
        assertTrue(settlements.isEmpty(), "Settlement should have been rolled back completely!")
    }

    @Test
    fun testMultiUserSyncAndPersistenceAcrossConnectionLifecycle() = kotlinx.coroutines.runBlocking {
        val userA = userRepository.create(name = "Alice")
        val userB = userRepository.create(name = "Bob")

        // Step 1: User A creates epic
        val epic = epicService.createEpic(userA.id, "Goa Trip 2026", "Beach fun", null).data
        assertNotNull(epic.inviteCode)

        // Step 2: User B joins epic
        val joinedEpic = epicService.joinEpic(userB.id, epic.inviteCode).data
        assertEquals(epic.id, joinedEpic.id)

        // Step 3: User A creates ₹1000 expense split equally
        val expense = expenseService.createExpense(
            callerId = userA.id,
            epicId = epic.id,
            title = "Resort Booking",
            amountMinor = 100000L, // ₹1,000.00
            paidByUserId = userA.id,
            splitType = SplitType.EQUAL,
            participantIds = listOf(userA.id, userB.id)
        ).data
        assertNotNull(expense)

        // Step 4: Verify User B sees the expense and balance = -₹500 (-50000 minor)
        val expListForB = expenseService.getExpenses(userB.id, epic.id)
        assertEquals(1, expListForB.size)
        assertEquals(100000L, expListForB[0].amountMinor)

        var expenses = expenseRepository.findByEpicId(epic.id)
        var splits = expenseRepository.getSplitsForEpic(epic.id)
        var settlements = settlementRepository.findByEpicId(epic.id)
        var balances = BalanceCalculator.calculateNetBalances(setOf(userA.id, userB.id), expenses, splits, settlements)

        assertEquals(50000L, balances[userA.id]?.netBalanceMinor) // Alice is owed ₹500
        assertEquals(-50000L, balances[userB.id]?.netBalanceMinor) // Bob owes ₹500

        // Step 5: User B settles ₹500
        val settlement = settlementService.createSettlement(
            callerId = userB.id,
            epicId = epic.id,
            fromUserId = userB.id,
            toUserId = userA.id,
            amountMinor = 50000L,
            method = "UPI"
        ).data
        assertNotNull(settlement)

        // Step 6: Verify User A sees updated balance = 0
        expenses = expenseRepository.findByEpicId(epic.id)
        splits = expenseRepository.getSplitsForEpic(epic.id)
        settlements = settlementRepository.findByEpicId(epic.id)
        balances = BalanceCalculator.calculateNetBalances(setOf(userA.id, userB.id), expenses, splits, settlements)

        assertEquals(0L, balances[userA.id]?.netBalanceMinor)
        assertEquals(0L, balances[userB.id]?.netBalanceMinor)

        // Step 7: Simulate server restart / completely fresh service instances reading from the database
        val freshEpicRepo = EpicRepositoryImpl()
        val freshExpenseRepo = ExpenseRepositoryImpl()
        val freshSettlementRepo = SettlementRepositoryImpl()
        val freshActivityRepo = ActivityRepositoryImpl()
        val freshEpicService = EpicService(freshEpicRepo, freshActivityRepo, freshExpenseRepo, freshSettlementRepo)
        val freshExpenseService = ExpenseService(freshExpenseRepo, freshEpicRepo, freshSettlementRepo, freshActivityRepo)

        val restartedEpic = freshEpicService.getEpic(userA.id, epic.id)
        assertEquals("Goa Trip 2026", restartedEpic.title)

        val restartedMembers = freshEpicService.getMembers(userA.id, epic.id)
        assertEquals(2, restartedMembers.size)

        val restartedExpenses = freshExpenseService.getExpenses(userB.id, epic.id)
        assertEquals(1, restartedExpenses.size)
        assertEquals(100000L, restartedExpenses[0].amountMinor)

        val freshSettlements = freshSettlementRepo.findByEpicId(epic.id)
        assertEquals(1, freshSettlements.size)
        assertEquals(50000L, freshSettlements[0].amountMinor)
    }

    @Test
    fun testPairwiseDebtCheckBlocksMemberRemovalEvenWhenNetBalanceIsZero() = kotlinx.coroutines.runBlocking {
        // A, B, C:
        // A pays ₹100 for B (B owes A ₹100)
        // C pays ₹100 for A (A owes C ₹100)
        // For User A:
        // Net balance: +100 - 100 = 0!
        // But A still owes C ₹100 and B owes A ₹100!
        // User A MUST NOT be allowed to leave until bilateral debts are settled.
        val userA = userRepository.create(name = "User A")
        val userB = userRepository.create(name = "User B")
        val userC = userRepository.create(name = "User C")

        val epic = epicService.createEpic(userA.id, "Trio Trip", null, null).data
        epicService.joinEpic(userB.id, epic.inviteCode)
        epicService.joinEpic(userC.id, epic.inviteCode)

        // Expense 1: A pays ₹100 for B
        expenseService.createExpense(
            callerId = userA.id,
            epicId = epic.id,
            title = "A pays for B",
            amountMinor = 10000L,
            paidByUserId = userA.id,
            splitType = SplitType.EXACT,
            participantIds = listOf(userB.id),
            splitDetails = listOf(ExpenseService.SplitDetail(userId = userB.id, exactAmountMinor = 10000L))
        )

        // Expense 2: C pays ₹100 for A
        expenseService.createExpense(
            callerId = userC.id,
            epicId = epic.id,
            title = "C pays for A",
            amountMinor = 10000L,
            paidByUserId = userC.id,
            splitType = SplitType.EXACT,
            participantIds = listOf(userA.id),
            splitDetails = listOf(ExpenseService.SplitDetail(userId = userA.id, exactAmountMinor = 10000L))
        )

        val expenses = expenseRepository.findByEpicId(epic.id)
        val splits = expenseRepository.getSplitsForEpic(epic.id)
        val netBalances = BalanceCalculator.calculateNetBalances(setOf(userA.id), expenses, splits, emptyList())

        // Confirm net balance is exactly 0
        assertEquals(0L, netBalances[userA.id]?.netBalanceMinor)

        // Member B attempts to leave or be removed: B owes A ₹100 -> rejected
        assertFailsWith<AppException.Validation> {
            epicService.removeMember(userB.id, epic.id, userB.id)
        }

        // Member C attempts to leave or be removed: A owes C ₹100 -> rejected
        assertFailsWith<AppException.Validation> {
            epicService.removeMember(userC.id, epic.id, userC.id)
        }
        Unit
    }

    @Test
    fun testTwoDeviceManualSyncFlow_AliceCreates_BobJoins_ExactSync() = kotlinx.coroutines.test.runTest {
        // Device A (Alice)
        val alice = userRepository.create(UUID.randomUUID(), name = "Alice")
        // Device B (Bob)
        val bob = userRepository.create(UUID.randomUUID(), name = "Bob")

        // 1. Device A creates Epic "test" with invite code "FS-TEST-9999"
        val createResult = epicService.createEpic(
            userId = alice.id,
            title = "test",
            description = "Group for test",
            iconUrl = null,
            customInviteCode = "FS-TEST-9999"
        )
        val epic = createResult.data
        assertEquals("test", epic.title)
        assertEquals("FS-TEST-9999", epic.inviteCode)

        // Verify Device A is the only member (no phantom members!)
        val initialMembers = epicRepository.getMembers(epic.id)
        assertEquals(1, initialMembers.size)
        assertEquals(alice.id, initialMembers[0].userId)
        assertEquals(EpicMemberRole.OWNER, initialMembers[0].role)

        // Verify creation activity exists
        val initialActivities = activityRepository.findByEpicId(epic.id)
        assertEquals(1, initialActivities.size)
        assertEquals("EPIC_CREATED", initialActivities[0].actionType)
        assertEquals(alice.id, initialActivities[0].actorId)

        // 2. Device B joins with invite code "FS-TEST-9999"
        val joinResult = epicService.joinEpic(bob.id, "FS-TEST-9999")
        assertEquals(epic.id, joinResult.data.id)

        // Verify member count is now exactly 2: Alice and Bob
        val membersAfterJoin = epicRepository.getMembers(epic.id)
        assertEquals(2, membersAfterJoin.size)
        assertTrue(membersAfterJoin.any { it.userId == alice.id && it.role == EpicMemberRole.OWNER })
        assertTrue(membersAfterJoin.any { it.userId == bob.id && it.role == EpicMemberRole.MEMBER })

        // Verify activity logs contain join event
        val activitiesAfterJoin = activityRepository.findByEpicId(epic.id)
        assertEquals(2, activitiesAfterJoin.size)
        assertEquals("MEMBER_JOINED", activitiesAfterJoin[0].actionType)
        assertEquals(bob.id, activitiesAfterJoin[0].actorId)

        // 3. Bob adds an expense: "Dinner" for ₹500 split equally
        val expenseResult = expenseService.createExpense(
            callerId = bob.id,
            epicId = epic.id,
            title = "Dinner",
            amountMinor = 50000L,
            paidByUserId = bob.id,
            splitType = SplitType.EQUAL,
            participantIds = listOf(alice.id, bob.id)
        )
        assertNotNull(expenseResult.data)

        // Verify activity log has expense added
        val activitiesAfterExpense = activityRepository.findByEpicId(epic.id)
        assertEquals(3, activitiesAfterExpense.size)
        assertEquals("EXPENSE_ADDED", activitiesAfterExpense[0].actionType)

        // Verify balances: Alice owes Bob ₹250
        val expenses = expenseRepository.findByEpicId(epic.id)
        val splits = expenseRepository.getSplitsForEpic(epic.id)
        val netBalances = BalanceCalculator.calculateNetBalances(setOf(alice.id, bob.id), expenses, splits, emptyList())
        assertEquals(-25000L, netBalances[alice.id]?.netBalanceMinor)
        assertEquals(25000L, netBalances[bob.id]?.netBalanceMinor)

        // 4. Alice settles with Bob for ₹250
        val settlementResult = settlementService.createSettlement(
            callerId = alice.id,
            epicId = epic.id,
            fromUserId = alice.id,
            toUserId = bob.id,
            amountMinor = 25000L,
            method = "UPI",
            notes = "Settled via UPI"
        )
        assertNotNull(settlementResult.data)

        // Balances are now 0
        val settlements = settlementRepository.findByEpicId(epic.id)
        val netBalancesSettled = BalanceCalculator.calculateNetBalances(setOf(alice.id, bob.id), expenses, splits, settlements)
        assertEquals(0L, netBalancesSettled[alice.id]?.netBalanceMinor)
        assertEquals(0L, netBalancesSettled[bob.id]?.netBalanceMinor)

        // 5. Alice deletes the epic
        val deleteResult = epicService.deleteEpic(alice.id, epic.id)
        assertTrue(deleteResult.data)
        assertNull(epicRepository.findById(epic.id))
    }
}
