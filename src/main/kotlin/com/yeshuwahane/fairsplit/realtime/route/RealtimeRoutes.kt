package com.yeshuwahane.fairsplit.realtime.route

import com.yeshuwahane.fairsplit.domain.repository.EpicRepository
import com.yeshuwahane.fairsplit.infrastructure.auth.JwtProvider
import com.yeshuwahane.fairsplit.realtime.broadcaster.EpicRealtimeBroadcaster
import io.ktor.server.application.*
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import io.ktor.websocket.*
import org.slf4j.LoggerFactory
import java.util.UUID

fun Application.configureRealtimeRoutes(
    epicRealtimeBroadcaster: EpicRealtimeBroadcaster,
    epicRepository: EpicRepository,
    jwtProvider: JwtProvider
) {
    val logger = LoggerFactory.getLogger("com.yeshuwahane.fairsplit.realtime.WebSocket")

    routing {
        fun Route.webSocketEndpoints() {
            webSocket("/ws/epics/{epicId}") {
                val epicIdParam = call.parameters["epicId"]
                val epicId = try {
                    UUID.fromString(epicIdParam)
                } catch (_: Exception) {
                    logger.warn("WebSocket connection rejected: invalid epicId format")
                    close(CloseReason(CloseReason.Codes.CANNOT_ACCEPT, "Invalid epicId UUID"))
                    return@webSocket
                }

                // 1. Authenticate JWT token from query parameter 'token' or 'Authorization' header
                val token = call.request.queryParameters["token"]
                    ?: call.request.headers["Authorization"]?.removePrefix("Bearer ")?.trim()

                if (token.isNullOrBlank()) {
                    logger.warn("WebSocket connection rejected for epic {}: missing token", epicId)
                    close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "Missing authentication token"))
                    return@webSocket
                }

                val userId = try {
                    jwtProvider.verifyAccessToken(token)
                } catch (e: Exception) {
                    logger.warn("WebSocket connection rejected for epic {}: invalid or expired token", epicId)
                    close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "Invalid or expired token"))
                    return@webSocket
                }

                // 2. Authorize epic membership
                val membership = epicRepository.findMember(epicId, userId)
                if (membership == null) {
                    logger.warn("WebSocket connection rejected for epic {}: user {} is not a member", epicId, userId)
                    close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "Forbidden: not a member of this Epic"))
                    return@webSocket
                }

                // 3. Register session in room
                epicRealtimeBroadcaster.connect(epicId, userId, this)

                try {
                    // Send an initial connected handshake frame
                    send(Frame.Text("""{"type":"CONNECTED","epicId":"$epicId"}"""))

                    // Keep session alive and handle incoming client messages (e.g. ping/pong)
                    for (frame in incoming) {
                        when (frame) {
                            is Frame.Text -> {
                                val text = frame.readText()
                                if (text.trim() == "ping") {
                                    send(Frame.Text("pong"))
                                }
                            }
                            is Frame.Ping -> {
                                send(Frame.Pong(frame.data))
                            }
                            else -> Unit
                        }
                    }
                } catch (e: Exception) {
                    logger.debug("WebSocket connection terminated for epic {}: {}", epicId, e.message)
                } finally {
                    epicRealtimeBroadcaster.disconnect(epicId, this)
                }
            }
        }

        // Support both /api/v1/ws/epics/{epicId} and /ws/epics/{epicId}
        route("/api/v1") {
            webSocketEndpoints()
        }
        webSocketEndpoints()
    }
}
