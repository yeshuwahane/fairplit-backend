package com.yeshuwahane.fairsplit.infrastructure.auth

import com.yeshuwahane.fairsplit.common.error.AppException
import com.yeshuwahane.fairsplit.common.error.ErrorCode
import io.ktor.server.application.*
import io.ktor.server.auth.*

fun Application.configureAuthPlugin(jwtProvider: JwtProvider) {
    install(Authentication) {
        bearer("auth-jwt") {
            authenticate { credential ->
                val token = credential.token
                try {
                    val userId = jwtProvider.verifyAccessToken(token)
                    FairSplitPrincipal(userId)
                } catch (e: AppException.Unauthorized) {
                    null
                } catch (e: Exception) {
                    null
                }
            }
        }
    }
}
