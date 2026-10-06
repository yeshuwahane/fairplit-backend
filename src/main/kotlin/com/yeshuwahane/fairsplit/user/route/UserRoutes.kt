package com.yeshuwahane.fairsplit.user.route

import com.yeshuwahane.fairsplit.common.api.ApiResponse
import com.yeshuwahane.fairsplit.infrastructure.auth.FairSplitPrincipal
import com.yeshuwahane.fairsplit.user.dto.UpdateProfileRequest
import com.yeshuwahane.fairsplit.user.service.UserService
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

fun Application.configureUserRoutes(userService: UserService) {
    routing {
        fun Route.userEndpoints() {
            route("/users/me") {
                authenticate("auth-jwt") {
                    get {
                        val principal = call.principal<FairSplitPrincipal>()
                            ?: return@get call.respond(HttpStatusCode.Unauthorized, ApiResponse.error("UNAUTHORIZED", "Not authenticated"))
                        val profile = userService.getProfile(principal.userId)
                        call.respond(HttpStatusCode.OK, ApiResponse.success(profile))
                    }

                    put {
                        val principal = call.principal<FairSplitPrincipal>()
                            ?: return@put call.respond(HttpStatusCode.Unauthorized, ApiResponse.error("UNAUTHORIZED", "Not authenticated"))
                        val req = call.receive<UpdateProfileRequest>()
                        val updated = userService.updateProfile(principal.userId, req)
                        call.respond(HttpStatusCode.OK, ApiResponse.success(updated))
                    }
                }
            }

            // Also support PUT /users/profile as alias
            route("/users/profile") {
                authenticate("auth-jwt") {
                    put {
                        val principal = call.principal<FairSplitPrincipal>()
                            ?: return@put call.respond(HttpStatusCode.Unauthorized, ApiResponse.error("UNAUTHORIZED", "Not authenticated"))
                        val req = call.receive<UpdateProfileRequest>()
                        val updated = userService.updateProfile(principal.userId, req)
                        call.respond(HttpStatusCode.OK, ApiResponse.success(updated))
                    }
                }
            }
        }

        route("/api/v1") {
            userEndpoints()
        }
        userEndpoints()
    }
}
