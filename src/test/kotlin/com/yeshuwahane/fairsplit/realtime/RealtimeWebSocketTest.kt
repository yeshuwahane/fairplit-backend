package com.yeshuwahane.fairsplit.realtime

import com.yeshuwahane.fairsplit.config.JwtConfig
import com.yeshuwahane.fairsplit.data.repository.EpicRepositoryImpl
import com.yeshuwahane.fairsplit.data.repository.ExpenseRepositoryImpl
import com.yeshuwahane.fairsplit.data.repository.SettlementRepositoryImpl
import com.yeshuwahane.fairsplit.data.table.EpicMembersTable
import com.yeshuwahane.fairsplit.data.table.EpicsTable
import com.yeshuwahane.fairsplit.data.table.ExpenseSplitsTable
import com.yeshuwahane.fairsplit.data.table.ExpensesTable
import com.yeshuwahane.fairsplit.data.table.SettlementsTable
import com.yeshuwahane.fairsplit.user.table.UsersTable
import com.yeshuwahane.fairsplit.domain.model.EpicMemberRole
import com.yeshuwahane.fairsplit.domain.model.SplitType
import com.yeshuwahane.fairsplit.infrastructure.auth.JwtProvider
import com.yeshuwahane.fairsplit.realtime.broadcaster.EpicRealtimeBroadcaster
import com.yeshuwahane.fairsplit.realtime.broadcaster.InMemoryEpicRealtimeBroadcaster
import com.yeshuwahane.fairsplit.realtime.model.RealtimeEvent
import com.yeshuwahane.fairsplit.realtime.model.RealtimeEventType
import com.yeshuwahane.fairsplit.realtime.route.configureRealtimeRoutes
import com.yeshuwahane.fairsplit.user.repository.UserRepositoryImpl
import io.ktor.client.plugins.websocket.*
import io.ktor.server.testing.*
import io.ktor.websocket.*
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.transactions.transaction
import java.util.UUID
import kotlin.test.*

class RealtimeWebSocketTest {

    private lateinit var database: Database
    private val userRepository = UserRepositoryImpl()
    private val epicRepository = EpicRepositoryImpl()
    private val expenseRepository = ExpenseRepositoryImpl()
    private val settlementRepository = SettlementRepositoryImpl()
    private val broadcaster: EpicRealtimeBroadcaster = InMemoryEpicRealtimeBroadcaster()

    private val jwtConfig = JwtConfig(
        secret = "super-secret-jwt-key-with-minimum-32-bytes-length-here",
        issuer = "fairsplit-test",
        audience = "fairsplit-audience",
        accessTtlDays = 1,
        refreshTtlDays = 30
    )
    private val jwtProvider = JwtProvider(jwtConfig)

    @BeforeTest
    fun setUp() {
        val dbName = "ws_test_${UUID.randomUUID().toString().replace("-", "")}"
        database = Database.connect(
            url = "jdbc:h2:mem:$dbName;DB_CLOSE_DELAY=-1;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE",
            driver = "org.h2.Driver"
        )
        org.jetbrains.exposed.sql.transactions.TransactionManager.defaultDatabase = database
        transaction(database) {
            SchemaUtils.create(
                UsersTable,
                EpicsTable,
                EpicMembersTable,
                ExpensesTable,
                ExpenseSplitsTable,
                SettlementsTable
            )
        }
    }

    @Test
    fun testValidMemberConnectsAndReceivesConnectedMessage() = testApplication {
        install(io.ktor.server.websocket.WebSockets) {
            pingPeriodMillis = 15_000L
            timeoutMillis = 30_000L
        }

        application {
            configureRealtimeRoutes(broadcaster, epicRepository, jwtProvider)
        }

        val client = createClient {
            install(io.ktor.client.plugins.websocket.WebSockets)
        }

        val user = userRepository.create(name = "Alice")
        val epic = epicRepository.createEpic("Trip", null, null, user.id, "INR", "FS-TRIP-1")
        epicRepository.addMember(epic.id, user.id, EpicMemberRole.OWNER)
        val token = jwtProvider.createAccessToken(user.id)

        client.webSocket("/api/v1/ws/epics/${epic.id}?token=$token") {
            val frame = incoming.receive()
            assertTrue(frame is Frame.Text)
            val text = (frame as Frame.Text).readText()
            assertTrue(text.contains("CONNECTED"))
            assertTrue(text.contains(epic.id.toString()))
        }

        // Also test connection via Authorization: Bearer header
        client.webSocket(
            urlString = "/api/v1/ws/epics/${epic.id}",
            request = {
                headers.append("Authorization", "Bearer $token")
            }
        ) {
            val frame = incoming.receive()
            assertTrue(frame is Frame.Text)
            val text = (frame as Frame.Text).readText()
            assertTrue(text.contains("CONNECTED"))
            assertTrue(text.contains(epic.id.toString()))
        }
    }

    @Test
    fun testNonMemberConnectionIsRejected() = testApplication {
        install(io.ktor.server.websocket.WebSockets)
        application {
            configureRealtimeRoutes(broadcaster, epicRepository, jwtProvider)
        }

        val client = createClient {
            install(io.ktor.client.plugins.websocket.WebSockets)
        }

        val owner = userRepository.create(name = "Alice")
        val stranger = userRepository.create(name = "Eve")
        val epic = epicRepository.createEpic("Private Group", null, null, owner.id, "INR", "FS-PRIV-1")
        epicRepository.addMember(epic.id, owner.id, EpicMemberRole.OWNER)

        val strangerToken = jwtProvider.createAccessToken(stranger.id)

        // Connecting with a valid JWT for stranger who is NOT a member of epic -> must be closed with VIOLATED_POLICY
        client.webSocket("/api/v1/ws/epics/${epic.id}?token=$strangerToken") {
            val reason = closeReason.await()
            assertNotNull(reason)
            assertEquals(CloseReason.Codes.VIOLATED_POLICY.code, reason.code)
        }
    }

    @Test
    fun testInvalidOrMissingJwtIsRejected() = testApplication {
        install(io.ktor.server.websocket.WebSockets)
        application {
            configureRealtimeRoutes(broadcaster, epicRepository, jwtProvider)
        }

        val client = createClient {
            install(io.ktor.client.plugins.websocket.WebSockets)
        }

        val user = userRepository.create(name = "Alice")
        val epic = epicRepository.createEpic("Test", null, null, user.id, "INR", "FS-TEST-1")

        // 1. Missing token
        client.webSocket("/api/v1/ws/epics/${epic.id}") {
            val reason = closeReason.await()
            assertNotNull(reason)
            assertEquals(CloseReason.Codes.VIOLATED_POLICY.code, reason.code)
        }

        // 2. Garbage token
        client.webSocket("/api/v1/ws/epics/${epic.id}?token=invalid.jwt.token") {
            val reason = closeReason.await()
            assertNotNull(reason)
            assertEquals(CloseReason.Codes.VIOLATED_POLICY.code, reason.code)
        }
    }

    @Test
    fun testRoomIsolationBetweenEpics() = testApplication {
        install(io.ktor.server.websocket.WebSockets)
        application {
            configureRealtimeRoutes(broadcaster, epicRepository, jwtProvider)
        }

        val client = createClient {
            install(io.ktor.client.plugins.websocket.WebSockets)
        }

        val alice = userRepository.create(name = "Alice")
        val bob = userRepository.create(name = "Bob")

        val epicA = epicRepository.createEpic("Epic A", null, null, alice.id, "INR", "FS-EPA-1")
        val epicB = epicRepository.createEpic("Epic B", null, null, bob.id, "INR", "FS-EPB-1")
        epicRepository.addMember(epicA.id, alice.id, EpicMemberRole.OWNER)
        epicRepository.addMember(epicB.id, bob.id, EpicMemberRole.OWNER)

        val tokenAlice = jwtProvider.createAccessToken(alice.id)
        val tokenBob = jwtProvider.createAccessToken(bob.id)

        // Broadcaster session counts before connection
        assertEquals(0, broadcaster.getSessionCount(epicA.id))
        assertEquals(0, broadcaster.getSessionCount(epicB.id))

        // Connect Alice to Epic A
        val sessionAlice = client.webSocketSession("/api/v1/ws/epics/${epicA.id}?token=$tokenAlice")
        val connFrameA = sessionAlice.incoming.receive() as Frame.Text
        assertTrue(connFrameA.readText().contains("CONNECTED"))

        // Connect Bob to Epic B
        val sessionBob = client.webSocketSession("/api/v1/ws/epics/${epicB.id}?token=$tokenBob")
        val connFrameB = sessionBob.incoming.receive() as Frame.Text
        assertTrue(connFrameB.readText().contains("CONNECTED"))

        // Verify room session counts
        assertEquals(1, broadcaster.getSessionCount(epicA.id))
        assertEquals(1, broadcaster.getSessionCount(epicB.id))

        // Publish an event specifically for Epic A
        val eventA = RealtimeEvent(
            type = RealtimeEventType.EXPENSE_CREATED,
            epicId = epicA.id.toString(),
            entityId = UUID.randomUUID().toString(),
            actorId = alice.id.toString()
        )
        broadcaster.publish(eventA)

        // Alice should receive the event
        val aliceEventFrame = sessionAlice.incoming.receive() as Frame.Text
        val receivedJson = aliceEventFrame.readText()
        assertTrue(receivedJson.contains("EXPENSE_CREATED"))
        assertTrue(receivedJson.contains(epicA.id.toString()))

        // Bob in Epic B must NOT receive this event.
        assertTrue(sessionBob.incoming.isEmpty, "Bob in Epic B should not receive Epic A event!")

        sessionAlice.close()
        sessionBob.close()
    }

    @Test
    fun testMultiDeviceBroadcast() = testApplication {
        install(io.ktor.server.websocket.WebSockets)
        application {
            configureRealtimeRoutes(broadcaster, epicRepository, jwtProvider)
        }

        val client = createClient {
            install(io.ktor.client.plugins.websocket.WebSockets)
        }

        val alice = userRepository.create(name = "Alice")
        val bob = userRepository.create(name = "Bob")

        val epic = epicRepository.createEpic("Goa Trip", null, null, alice.id, "INR", "FS-GOA-1")
        epicRepository.addMember(epic.id, alice.id, EpicMemberRole.OWNER)
        epicRepository.addMember(epic.id, bob.id, EpicMemberRole.MEMBER)

        val tokenAlice = jwtProvider.createAccessToken(alice.id)
        val tokenBob = jwtProvider.createAccessToken(bob.id)

        // Connect Alice Device 1
        val sessionAlice1 = client.webSocketSession("/api/v1/ws/epics/${epic.id}?token=$tokenAlice")
        sessionAlice1.incoming.receive() // Handshake

        // Connect Alice Device 2
        val sessionAlice2 = client.webSocketSession("/api/v1/ws/epics/${epic.id}?token=$tokenAlice")
        sessionAlice2.incoming.receive() // Handshake

        // Connect Bob Device 1
        val sessionBob1 = client.webSocketSession("/api/v1/ws/epics/${epic.id}?token=$tokenBob")
        sessionBob1.incoming.receive() // Handshake

        // 3 sessions connected to epic
        assertEquals(3, broadcaster.getSessionCount(epic.id))

        // Publish EXPENSE_CREATED event
        val event = RealtimeEvent(
            type = RealtimeEventType.EXPENSE_CREATED,
            epicId = epic.id.toString(),
            entityId = UUID.randomUUID().toString(),
            actorId = alice.id.toString()
        )
        broadcaster.publish(event)

        // Bob receives it
        val bobFrame = sessionBob1.incoming.receive() as Frame.Text
        val bobPayload = Json.decodeFromString<RealtimeEvent>(bobFrame.readText())
        assertEquals(RealtimeEventType.EXPENSE_CREATED, bobPayload.type)

        // Alice Device 2 receives it
        val alice2Frame = sessionAlice2.incoming.receive() as Frame.Text
        val alice2Payload = Json.decodeFromString<RealtimeEvent>(alice2Frame.readText())
        assertEquals(RealtimeEventType.EXPENSE_CREATED, alice2Payload.type)

        // Alice Device 1 receives it
        val alice1Frame = sessionAlice1.incoming.receive() as Frame.Text
        val alice1Payload = Json.decodeFromString<RealtimeEvent>(alice1Frame.readText())
        assertEquals(RealtimeEventType.EXPENSE_CREATED, alice1Payload.type)

        sessionAlice1.close()
        sessionAlice2.close()
        sessionBob1.close()

        // Give Ktor server a brief moment to process disconnects
        kotlinx.coroutines.delay(100)
        assertEquals(0, broadcaster.getSessionCount(epic.id))
    }

    @Test
    fun testEndToEndExpenseCreationAndBobReceipt() = testApplication {
        install(io.ktor.server.websocket.WebSockets)
        application {
            configureRealtimeRoutes(broadcaster, epicRepository, jwtProvider)
        }

        val client = createClient {
            install(io.ktor.client.plugins.websocket.WebSockets)
        }

        // 1. Set up Alice and Bob
        val alice = userRepository.create(name = "Alice")
        val bob = userRepository.create(name = "Bob")

        val epic = epicRepository.createEpic("Goa Vacation", null, null, alice.id, "INR", "FS-GOA-E2E")
        epicRepository.addMember(epic.id, alice.id, EpicMemberRole.OWNER)
        epicRepository.addMember(epic.id, bob.id, EpicMemberRole.MEMBER)

        val tokenAlice = jwtProvider.createAccessToken(alice.id)
        val tokenBob = jwtProvider.createAccessToken(bob.id)

        // 2. Bob connects WebSocket with Authorization Bearer header
        val sessionBob = client.webSocketSession(
            urlString = "/api/v1/ws/epics/${epic.id}",
            block = {
                headers.append("Authorization", "Bearer $tokenBob")
            }
        )
        val handshake = sessionBob.incoming.receive() as Frame.Text
        assertTrue(handshake.readText().contains("CONNECTED"))

        // 3. Alice creates an expense in PostgreSQL
        val expenseId = UUID.randomUUID()
        val expense = com.yeshuwahane.fairsplit.domain.model.Expense(
            id = expenseId,
            epicId = epic.id,
            title = "Dinner ₹1000",
            amountMinor = 100000L,
            currency = "INR",
            paidByUserId = alice.id,
            splitType = SplitType.EQUAL,
            receiptUrl = null,
            notes = null,
            createdAt = java.time.Instant.now(),
            updatedAt = java.time.Instant.now()
        )
        val splits = listOf(
            com.yeshuwahane.fairsplit.domain.model.ExpenseSplit(
                id = UUID.randomUUID(),
                expenseId = expenseId,
                userId = alice.id,
                amountMinor = 50000L,
                createdAt = java.time.Instant.now()
            ),
            com.yeshuwahane.fairsplit.domain.model.ExpenseSplit(
                id = UUID.randomUUID(),
                expenseId = expenseId,
                userId = bob.id,
                amountMinor = 50000L,
                createdAt = java.time.Instant.now()
            )
        )
        expenseRepository.createExpenseWithSplits(expense, splits)

        // 4. Post-commit event published to broadcaster
        val event = RealtimeEvent(
            type = RealtimeEventType.EXPENSE_CREATED,
            epicId = epic.id.toString(),
            entityId = expense.id.toString(),
            actorId = alice.id.toString()
        )
        broadcaster.publish(event)

        // 5. Bob receives realtime notification
        val bobFrame = sessionBob.incoming.receive() as Frame.Text
        val receivedEvent = Json.decodeFromString<RealtimeEvent>(bobFrame.readText())
        assertEquals(RealtimeEventType.EXPENSE_CREATED, receivedEvent.type)
        assertEquals(expense.id.toString(), receivedEvent.entityId)

        // 6. Bob refreshes from authoritative PostgreSQL state
        val authoritativeExpenses = expenseRepository.findByEpicId(epic.id)
        assertEquals(1, authoritativeExpenses.size)
        assertEquals("Dinner ₹1000", authoritativeExpenses.first().title)
        assertEquals(100000L, authoritativeExpenses.first().amountMinor)

        sessionBob.close()
    }
}
