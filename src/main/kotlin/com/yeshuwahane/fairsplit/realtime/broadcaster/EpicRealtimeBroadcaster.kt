package com.yeshuwahane.fairsplit.realtime.broadcaster

import com.yeshuwahane.fairsplit.realtime.model.RealtimeEvent
import io.ktor.websocket.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

interface EpicRealtimeBroadcaster {
    suspend fun connect(epicId: UUID, userId: UUID, session: WebSocketSession)
    suspend fun disconnect(epicId: UUID, session: WebSocketSession)
    suspend fun publish(event: RealtimeEvent)
    suspend fun publish(events: List<RealtimeEvent>)
    fun getSessionCount(epicId: UUID): Int
}

class InMemoryEpicRealtimeBroadcaster : EpicRealtimeBroadcaster {
    private val logger = LoggerFactory.getLogger(InMemoryEpicRealtimeBroadcaster::class.java)

    // epicId -> Map<WebSocketSession, userId>
    private val rooms = ConcurrentHashMap<UUID, MutableMap<WebSocketSession, UUID>>()
    private val roomLocks = ConcurrentHashMap<UUID, Mutex>()

    private fun getRoomLock(epicId: UUID): Mutex = roomLocks.computeIfAbsent(epicId) { Mutex() }

    override suspend fun connect(epicId: UUID, userId: UUID, session: WebSocketSession) {
        val lock = getRoomLock(epicId)
        lock.withLock {
            val sessionMap = rooms.computeIfAbsent(epicId) { ConcurrentHashMap() }
            sessionMap[session] = userId
            logger.info("WebSocket connected to epic {}: user {} (active sessions in room: {})", epicId, userId, sessionMap.size)
        }
    }

    override suspend fun disconnect(epicId: UUID, session: WebSocketSession) {
        val lock = getRoomLock(epicId)
        lock.withLock {
            val sessionMap = rooms[epicId]
            if (sessionMap != null) {
                val userId = sessionMap.remove(session)
                logger.info("WebSocket disconnected from epic {}: user {} (remaining sessions: {})", epicId, userId, sessionMap.size)
                if (sessionMap.isEmpty()) {
                    rooms.remove(epicId)
                    roomLocks.remove(epicId)
                    logger.info("Room for epic {} is now empty and cleaned up", epicId)
                }
            }
        }
    }

    override suspend fun publish(event: RealtimeEvent) {
        val epicUuid = try {
            UUID.fromString(event.epicId)
        } catch (_: Exception) {
            logger.warn("Invalid epicId in realtime event: {}", event.epicId)
            return
        }

        val sessionMap = rooms[epicUuid]
        if (sessionMap == null || sessionMap.isEmpty()) {
            logger.debug("No active WebSocket connections in room for epic {}", epicUuid)
            return
        }

        val jsonPayload = Json.encodeToString(event)
        val deadSessions = mutableListOf<WebSocketSession>()

        // Broadcast to all active sessions in the room
        for ((session, _) in sessionMap) {
            try {
                session.send(Frame.Text(jsonPayload))
            } catch (e: Exception) {
                logger.debug("Failed to send frame to WebSocket session for epic {}: {}", epicUuid, e.message)
                deadSessions.add(session)
            }
        }

        // Clean up dead sessions if any were detected
        if (deadSessions.isNotEmpty()) {
            val lock = getRoomLock(epicUuid)
            lock.withLock {
                deadSessions.forEach { sessionMap.remove(it) }
                if (sessionMap.isEmpty()) {
                    rooms.remove(epicUuid)
                    roomLocks.remove(epicUuid)
                }
            }
        }
    }

    override suspend fun publish(events: List<RealtimeEvent>) {
        for (event in events) {
            publish(event)
        }
    }

    override fun getSessionCount(epicId: UUID): Int {
        return rooms[epicId]?.size ?: 0
    }
}
