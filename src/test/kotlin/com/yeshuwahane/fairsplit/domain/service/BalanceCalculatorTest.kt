package com.yeshuwahane.fairsplit.domain.service

import com.yeshuwahane.fairsplit.domain.model.Expense
import com.yeshuwahane.fairsplit.domain.model.ExpenseSplit
import com.yeshuwahane.fairsplit.domain.model.Settlement
import com.yeshuwahane.fairsplit.domain.model.SplitType
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BalanceCalculatorTest {

    private val alice = UUID.randomUUID()
    private val bob = UUID.randomUUID()
    private val charlie = UUID.randomUUID()
    private val epicId = UUID.randomUUID()
    private val now = Instant.now()

    @Test
    fun calculatePairwiseDebt_returnsOutstandingDebt() {
        // Bob pays ₹980 (98,000 minor units). Alice owes ₹980, Bob owes ₹0.
        val expenseId = UUID.randomUUID()
        val expense = Expense(
            id = expenseId,
            epicId = epicId,
            title = "Dinner",
            amountMinor = 98000L,
            currency = "INR",
            paidByUserId = bob,
            splitType = SplitType.EXACT,
            receiptUrl = null,
            notes = null,
            createdAt = now,
            updatedAt = now
        )
        val splitAlice = ExpenseSplit(
            id = UUID.randomUUID(),
            expenseId = expenseId,
            userId = alice,
            amountMinor = 98000L,
            createdAt = now
        )

        val expenses = listOf(expense)
        val splitsByExpense = mapOf(expenseId to listOf(splitAlice))
        val settlements = emptyList<Settlement>()

        val debts = BalanceCalculator.calculatePairwiseDebts(expenses, splitsByExpense, settlements)

        assertEquals(1, debts.size)
        val debt = debts.first()
        assertEquals(alice, debt.debtorId)
        assertEquals(bob, debt.creditorId)
        assertEquals(98000L, debt.amountMinor)
    }

    @Test
    fun partialSettlement_reducesOutstandingDebt() {
        // Initial debt: Alice owes Bob ₹980 (98,000 minor)
        val expenseId = UUID.randomUUID()
        val expense = Expense(
            id = expenseId,
            epicId = epicId,
            title = "Dinner",
            amountMinor = 98000L,
            currency = "INR",
            paidByUserId = bob,
            splitType = SplitType.EXACT,
            receiptUrl = null,
            notes = null,
            createdAt = now,
            updatedAt = now
        )
        val splitAlice = ExpenseSplit(
            id = UUID.randomUUID(),
            expenseId = expenseId,
            userId = alice,
            amountMinor = 98000L,
            createdAt = now
        )

        // Alice pays Bob ₹500 (50,000 minor)
        val settlement = Settlement(
            id = UUID.randomUUID(),
            epicId = epicId,
            fromUserId = alice,
            toUserId = bob,
            amountMinor = 50000L,
            currency = "INR",
            method = "UPI",
            notes = "Partial settlement",
            createdAt = now
        )

        val expenses = listOf(expense)
        val splitsByExpense = mapOf(expenseId to listOf(splitAlice))
        val settlements = listOf(settlement)

        val debts = BalanceCalculator.calculatePairwiseDebts(expenses, splitsByExpense, settlements)

        assertEquals(1, debts.size)
        val debt = debts.first()
        assertEquals(alice, debt.debtorId)
        assertEquals(bob, debt.creditorId)
        assertEquals(48000L, debt.amountMinor) // ₹480 remaining
    }

    @Test
    fun fullSettlement_removesOutstandingDebt() {
        // Initial debt: Alice owes Bob ₹980
        val expenseId = UUID.randomUUID()
        val expense = Expense(
            id = expenseId,
            epicId = epicId,
            title = "Dinner",
            amountMinor = 98000L,
            currency = "INR",
            paidByUserId = bob,
            splitType = SplitType.EXACT,
            receiptUrl = null,
            notes = null,
            createdAt = now,
            updatedAt = now
        )
        val splitAlice = ExpenseSplit(
            id = UUID.randomUUID(),
            expenseId = expenseId,
            userId = alice,
            amountMinor = 98000L,
            createdAt = now
        )

        // Alice pays Bob ₹500, then pays remaining ₹480
        val settlement1 = Settlement(
            id = UUID.randomUUID(),
            epicId = epicId,
            fromUserId = alice,
            toUserId = bob,
            amountMinor = 50000L,
            currency = "INR",
            method = "UPI",
            notes = "First payment",
            createdAt = now
        )
        val settlement2 = Settlement(
            id = UUID.randomUUID(),
            epicId = epicId,
            fromUserId = alice,
            toUserId = bob,
            amountMinor = 48000L,
            currency = "INR",
            method = "UPI",
            notes = "Final payment",
            createdAt = now
        )

        val expenses = listOf(expense)
        val splitsByExpense = mapOf(expenseId to listOf(splitAlice))
        val settlements = listOf(settlement1, settlement2)

        val debts = BalanceCalculator.calculatePairwiseDebts(expenses, splitsByExpense, settlements)

        assertTrue(debts.isEmpty(), "All debts should be completely cleared after full settlement!")
    }

    @Test
    fun multipleBilateralDebts_arePreserved() {
        // Alice owes Bob ₹500
        val exp1Id = UUID.randomUUID()
        val exp1 = Expense(
            id = exp1Id,
            epicId = epicId,
            title = "Lunch by Bob",
            amountMinor = 50000L,
            currency = "INR",
            paidByUserId = bob,
            splitType = SplitType.EXACT,
            receiptUrl = null,
            notes = null,
            createdAt = now,
            updatedAt = now
        )
        val split1 = ExpenseSplit(
            id = UUID.randomUUID(),
            expenseId = exp1Id,
            userId = alice,
            amountMinor = 50000L,
            createdAt = now
        )

        // Alice owes Charlie ₹300
        val exp2Id = UUID.randomUUID()
        val exp2 = Expense(
            id = exp2Id,
            epicId = epicId,
            title = "Snacks by Charlie",
            amountMinor = 30000L,
            currency = "INR",
            paidByUserId = charlie,
            splitType = SplitType.EXACT,
            receiptUrl = null,
            notes = null,
            createdAt = now,
            updatedAt = now
        )
        val split2 = ExpenseSplit(
            id = UUID.randomUUID(),
            expenseId = exp2Id,
            userId = alice,
            amountMinor = 30000L,
            createdAt = now
        )

        val expenses = listOf(exp1, exp2)
        val splitsByExpense = mapOf(
            exp1Id to listOf(split1),
            exp2Id to listOf(split2)
        )
        val settlements = emptyList<Settlement>()

        val debts = BalanceCalculator.calculatePairwiseDebts(expenses, splitsByExpense, settlements)

        assertEquals(2, debts.size)
        val debtToBob = debts.firstOrNull { it.debtorId == alice && it.creditorId == bob }
        val debtToCharlie = debts.firstOrNull { it.debtorId == alice && it.creditorId == charlie }

        assertEquals(50000L, debtToBob?.amountMinor)
        assertEquals(30000L, debtToCharlie?.amountMinor)

        // Net balances
        val balances = BalanceCalculator.calculateNetBalances(setOf(alice, bob, charlie), expenses, listOf(split1, split2), settlements)
        assertEquals(-80000L, balances[alice]?.netBalanceMinor) // Alice owes ₹800 in total
        assertEquals(50000L, balances[bob]?.netBalanceMinor)    // Bob is owed ₹500
        assertEquals(30000L, balances[charlie]?.netBalanceMinor)// Charlie is owed ₹300
    }

    @Test
    fun voidedSettlement_isIgnoredInCalculations() {
        val expenseId = UUID.randomUUID()
        val expense = Expense(
            id = expenseId,
            epicId = epicId,
            title = "Dinner",
            amountMinor = 100000L, // ₹1000
            currency = "INR",
            paidByUserId = bob,
            splitType = SplitType.EXACT,
            receiptUrl = null,
            notes = null,
            createdAt = now,
            updatedAt = now
        )
        val splitAlice = ExpenseSplit(
            id = UUID.randomUUID(),
            expenseId = expenseId,
            userId = alice,
            amountMinor = 100000L,
            createdAt = now
        )

        // Settlement of ₹600 was marked, but then VOIDED
        val voidedSettlement = Settlement(
            id = UUID.randomUUID(),
            epicId = epicId,
            fromUserId = alice,
            toUserId = bob,
            amountMinor = 60000L,
            currency = "INR",
            method = "UPI",
            notes = "Accidental settlement",
            status = "VOIDED",
            createdAt = now,
            voidedAt = now,
            voidedBy = alice
        )

        val expenses = listOf(expense)
        val splitsByExpense = mapOf(expenseId to listOf(splitAlice))
        val settlements = listOf(voidedSettlement)

        val debts = BalanceCalculator.calculatePairwiseDebts(expenses, splitsByExpense, settlements)
        assertEquals(1, debts.size)
        assertEquals(100000L, debts.first().amountMinor, "Voided settlement must NOT reduce bilateral debt!")

        val balances = BalanceCalculator.calculateNetBalances(setOf(alice, bob), expenses, listOf(splitAlice), settlements)
        assertEquals(-100000L, balances[alice]?.netBalanceMinor, "Voided settlement must NOT alter net balance!")
        assertEquals(100000L, balances[bob]?.netBalanceMinor, "Voided settlement must NOT alter net balance!")
    }
}
