package com.yeshuwahane.fairsplit.auth

import com.yeshuwahane.fairsplit.auth.dto.*
import com.yeshuwahane.fairsplit.auth.route.configureAuthRoutes
import com.yeshuwahane.fairsplit.auth.service.PhoneAuthService
import com.yeshuwahane.fairsplit.auth.service.TokenService
import com.yeshuwahane.fairsplit.common.api.ApiResponse
import com.yeshuwahane.fairsplit.common.error.configureStatusPages
import com.yeshuwahane.fairsplit.config.AppConfig
import com.yeshuwahane.fairsplit.config.JwtConfig
import com.yeshuwahane.fairsplit.infrastructure.auth.JwtProvider
import com.yeshuwahane.fairsplit.infrastructure.auth.configureAuthPlugin
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.testing.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import com.yeshuwahane.fairsplit.auth.route.GoogleAuthRequest

class AuthRoutesTest {

    private val json = Json { ignoreUnknownKeys = true }

    private val jwtConfig = JwtConfig(
        secret = "secret-key-that-is-at-least-32-bytes-long-for-testing",
        issuer = "fairsplit-test",
        audience = "fairsplit-api-test",
        accessTtlDays = 30,
        refreshTtlDays = 90
    )
    private val appConfig = AppConfig(environment = "dev", port = 8080)
    private val jwtProvider = JwtProvider(jwtConfig)

    private fun ApplicationTestBuilder.setupAuth() {
        val userRepo = FakeUserRepository()
        val authIdRepo = FakeAuthIdentityRepository()
        val otpRepo = FakeOtpRepository()
        val refreshRepo = FakeRefreshSessionRepository()
        val tokenService = TokenService(jwtProvider, refreshRepo, jwtConfig)
        val phoneAuthService = PhoneAuthService(otpRepo, authIdRepo, userRepo, tokenService, appConfig)

        application {
            configureStatusPages()
            install(ContentNegotiation) {
                json(json)
            }
            configureAuthPlugin(jwtProvider)
            configureAuthRoutes(phoneAuthService, tokenService, appConfig, authIdRepo, userRepo)
        }
    }

    @Test
    fun testGoogleAuthConsistentUser() = testApplication {
        setupAuth()

        // Device 1 signs in with Google
        val req1 = GoogleAuthRequest(
            email = "tester@gmail.com",
            displayName = "Tester One",
            photoUrl = "http://example.com/avatar.jpg"
        )
        val res1 = client.post("/api/v1/auth/google") {
            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            setBody(json.encodeToString(req1))
        }
        assertEquals(HttpStatusCode.OK, res1.status)
        val body1 = json.decodeFromString<ApiResponse<AuthResponse>>(res1.bodyAsText())
        assertTrue(body1.success)
        val user1 = body1.data?.user
        assertNotNull(user1)
        assertEquals("tester@gmail.com", user1.email)
        assertEquals("http://example.com/avatar.jpg", user1.avatarUrl)

        // Device 2 signs in with the SAME Google account
        val req2 = GoogleAuthRequest(
            email = "tester@gmail.com",
            displayName = "Tester One",
            photoUrl = "http://example.com/avatar.jpg"
        )
        val res2 = client.post("/api/v1/auth/google") {
            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            setBody(json.encodeToString(req2))
        }
        assertEquals(HttpStatusCode.OK, res2.status)
        val body2 = json.decodeFromString<ApiResponse<AuthResponse>>(res2.bodyAsText())
        assertTrue(body2.success)
        val user2 = body2.data?.user
        assertNotNull(user2)

        // MUST be the exact same user ID across devices!
        assertEquals(user1.id, user2.id)
    }

    @Test
    fun testPhoneOtpEndpointsAreDeprecated() = testApplication {
        setupAuth()

        // 1. Request OTP returns 410 Gone
        val reqResponse = client.post("/api/v1/auth/phone/request-otp") {
            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            setBody(json.encodeToString(RequestOtpRequest("+919876543210")))
        }
        assertEquals(HttpStatusCode.Gone, reqResponse.status)

        // 2. Verify OTP returns 410 Gone
        val verifyResponse = client.post("/api/v1/auth/phone/verify-otp") {
            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            setBody(json.encodeToString(VerifyOtpRequest("+919876543210", "123456")))
        }
        assertEquals(HttpStatusCode.Gone, verifyResponse.status)
    }

    @Test
    fun testGoogleAuthProtectedRoutesAndRefreshFlow() = testApplication {
        setupAuth()

        // 1. Sign in with Google
        val googleReq = GoogleAuthRequest(
            email = "flowuser@gmail.com",
            displayName = "Flow User",
            photoUrl = "http://example.com/flow.jpg"
        )
        val authRes = client.post("/api/v1/auth/google") {
            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            setBody(json.encodeToString(googleReq))
        }
        assertEquals(HttpStatusCode.OK, authRes.status)
        val authBody = json.decodeFromString<ApiResponse<AuthResponse>>(authRes.bodyAsText())
        assertTrue(authBody.success)
        val tokens = authBody.data
        assertNotNull(tokens)
        assertNotNull(tokens.accessToken)
        assertNotNull(tokens.refreshToken)

        // 2. Access Protected Route /api/v1/auth/me with Bearer token
        val meResponse = client.get("/api/v1/auth/me") {
            header(HttpHeaders.Authorization, "Bearer ${tokens.accessToken}")
        }
        assertEquals(HttpStatusCode.OK, meResponse.status)

        // 3. Access Protected Route without token -> 401
        val unauthorizedResponse = client.get("/api/v1/auth/me")
        assertEquals(HttpStatusCode.Unauthorized, unauthorizedResponse.status)

        // 4. Refresh token
        val refreshResponse = client.post("/api/v1/auth/refresh") {
            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            setBody(json.encodeToString(RefreshTokenRequest(tokens.refreshToken)))
        }
        assertEquals(HttpStatusCode.OK, refreshResponse.status)
        val refreshBody = json.decodeFromString<ApiResponse<TokenRefreshResponse>>(refreshResponse.bodyAsText())
        assertTrue(refreshBody.success)
        assertNotNull(refreshBody.data?.accessToken)

        // 5. Logout
        val logoutResponse = client.post("/api/v1/auth/logout") {
            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            setBody(json.encodeToString(LogoutRequest(refreshBody.data?.refreshToken)))
        }
        assertEquals(HttpStatusCode.OK, logoutResponse.status)
    }
}
