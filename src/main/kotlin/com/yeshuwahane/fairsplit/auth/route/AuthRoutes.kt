package com.yeshuwahane.fairsplit.auth.route

import com.yeshuwahane.fairsplit.auth.dto.AuthResponse
import com.yeshuwahane.fairsplit.auth.dto.LogoutRequest
import com.yeshuwahane.fairsplit.auth.dto.RefreshTokenRequest
import com.yeshuwahane.fairsplit.auth.dto.RequestOtpRequest
import com.yeshuwahane.fairsplit.auth.dto.RequestOtpResponse
import com.yeshuwahane.fairsplit.auth.dto.TokenRefreshResponse
import com.yeshuwahane.fairsplit.auth.dto.VerifyOtpRequest
import com.yeshuwahane.fairsplit.auth.service.PhoneAuthService
import com.yeshuwahane.fairsplit.auth.service.TokenService
import com.yeshuwahane.fairsplit.common.api.ApiResponse
import com.yeshuwahane.fairsplit.config.AppConfig
import com.yeshuwahane.fairsplit.infrastructure.auth.FairSplitPrincipal
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import com.yeshuwahane.fairsplit.auth.model.AuthProvider
import com.yeshuwahane.fairsplit.auth.model.AuthResult
import com.yeshuwahane.fairsplit.auth.repository.AuthIdentityRepository
import com.yeshuwahane.fairsplit.common.error.AppException
import com.yeshuwahane.fairsplit.common.error.ErrorCode
import com.yeshuwahane.fairsplit.user.model.User
import com.yeshuwahane.fairsplit.user.repository.UserRepository
import kotlinx.serialization.Serializable

@Serializable
data class GoogleAuthRequest(
    val idToken: String = "",
    val email: String,
    val displayName: String? = null,
    val photoUrl: String? = null
)

fun Application.configureAuthRoutes(
    phoneAuthService: PhoneAuthService,
    tokenService: TokenService,
    appConfig: AppConfig,
    authIdentityRepository: AuthIdentityRepository? = null,
    userRepository: UserRepository? = null
) {
    routing {
        fun Route.authEndpoints() {
            route("/auth") {
                route("/phone") {
                    post("/request-otp") {
                        val req = call.receive<RequestOtpRequest>()
                        val otp = phoneAuthService.requestOtp(req.phoneValue)
                        val devOtp = if (appConfig.environment != "prod") otp else null
                        call.respond(
                            HttpStatusCode.OK,
                            ApiResponse.success(
                                RequestOtpResponse(
                                    message = "OTP challenge sent successfully",
                                    devOtp = devOtp
                                )
                            )
                        )
                    }

                    post("/verify-otp") {
                        val req = call.receive<VerifyOtpRequest>()
                        val result = phoneAuthService.verifyOtp(req.phoneValue, req.otp)
                        call.respond(
                            HttpStatusCode.OK,
                            ApiResponse.success(AuthResponse.fromResult(result))
                        )
                    }
                }

                post("/google") {
                    val req = call.receive<GoogleAuthRequest>()
                    val cleanEmail = req.email.trim().lowercase()
                    if (cleanEmail.isBlank()) {
                        call.respond(HttpStatusCode.BadRequest, ApiResponse.error("INVALID_EMAIL", "Email cannot be empty"))
                        return@post
                    }
                    if (authIdentityRepository == null || userRepository == null) {
                        call.respond(HttpStatusCode.NotImplemented, ApiResponse.error("NOT_CONFIGURED", "Google auth repositories not configured"))
                        return@post
                    }

                    val existingIdentity = authIdentityRepository.findByProvider(AuthProvider.GOOGLE, cleanEmail)
                    val user: User = if (existingIdentity != null) {
                        val existingUser = userRepository.findById(existingIdentity.userId)
                            ?: throw AppException.NotFound("User associated with Google identity not found", ErrorCode.USER_NOT_FOUND)
                        val needsUpdate = (existingUser.avatarUrl.isNullOrBlank() && !req.photoUrl.isNullOrBlank()) ||
                                          (existingUser.name.isBlank() && !req.displayName.isNullOrBlank())
                        if (needsUpdate) {
                            userRepository.update(
                                userId = existingUser.id,
                                name = req.displayName?.ifBlank { existingUser.name } ?: existingUser.name,
                                avatarUrl = req.photoUrl ?: existingUser.avatarUrl
                            ) ?: existingUser
                        } else {
                            existingUser
                        }
                    } else {
                        // Strict isolation: Do not auto-merge with other users by email.
                        // Create a dedicated user for this Google identity.
                        val newUser = userRepository.create(
                            name = req.displayName.orEmpty(),
                            email = cleanEmail,
                            avatarUrl = req.photoUrl,
                            profileCompleted = !req.displayName.isNullOrBlank()
                        )
                        authIdentityRepository.create(
                            userId = newUser.id,
                            provider = AuthProvider.GOOGLE,
                            providerUserId = cleanEmail
                        )
                        newUser
                    }

                    val tokenPair = tokenService.issueTokenPair(user.id)
                    call.respond(
                        HttpStatusCode.OK,
                        ApiResponse.success(AuthResponse.fromResult(AuthResult(user, tokenPair)))
                    )
                }

                post("/refresh") {
                    val req = call.receive<RefreshTokenRequest>()
                    val pair = tokenService.refresh(req.refreshToken)
                    call.respond(
                        HttpStatusCode.OK,
                        ApiResponse.success(
                            TokenRefreshResponse(
                                accessToken = pair.accessToken,
                                refreshToken = pair.refreshToken,
                                tokenType = "Bearer",
                                expiresIn = pair.accessExpiresAt
                            )
                        )
                    )
                }

                post("/logout") {
                    val req = try {
                        call.receive<LogoutRequest>()
                    } catch (_: Exception) {
                        null
                    }
                    if (req?.refreshToken != null) {
                        tokenService.revoke(req.refreshToken)
                    }
                    call.respond(
                        HttpStatusCode.OK,
                        ApiResponse.success(mapOf("message" to "Logged out successfully"))
                    )
                }

                authenticate("auth-jwt", optional = true) {
                    get("/me") {
                        val principal = call.principal<FairSplitPrincipal>()
                        if (principal == null) {
                            call.respond(HttpStatusCode.Unauthorized, ApiResponse.error("UNAUTHORIZED", "Not authenticated"))
                        } else {
                            call.respond(HttpStatusCode.OK, ApiResponse.success(mapOf("userId" to principal.userId.toString())))
                        }
                    }
                }
            }
        }

        // Mount at /api/v1/auth
        route("/api/v1") {
            authEndpoints()
        }

        // Also mount at /auth for convenience
        authEndpoints()
    }
}
