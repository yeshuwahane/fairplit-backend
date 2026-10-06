package com.yeshuwahane.fairsplit

import com.yeshuwahane.fairsplit.auth.route.configureAuthRoutes
import com.yeshuwahane.fairsplit.auth.service.PhoneAuthService
import com.yeshuwahane.fairsplit.auth.service.TokenService
import com.yeshuwahane.fairsplit.common.error.configureStatusPages
import com.yeshuwahane.fairsplit.config.AppConfig
import com.yeshuwahane.fairsplit.di.appModule
import com.yeshuwahane.fairsplit.infrastructure.auth.JwtProvider
import com.yeshuwahane.fairsplit.infrastructure.auth.configureAuthPlugin
import com.yeshuwahane.fairsplit.infrastructure.database.DatabaseFactory
import com.yeshuwahane.fairsplit.plugins.configureHealthRoutes
import com.yeshuwahane.fairsplit.realtime.route.configureRealtimeRoutes
import com.yeshuwahane.fairsplit.user.route.configureUserRoutes
import com.yeshuwahane.fairsplit.sync.route.configureSyncRoutes
import com.yeshuwahane.fairsplit.user.service.UserService
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.calllogging.*
import io.ktor.server.plugins.contentnegotiation.*
import kotlinx.serialization.json.Json
import org.koin.ktor.ext.get
import org.koin.ktor.ext.inject
import org.koin.ktor.plugin.Koin
import org.koin.logger.slf4jLogger
import org.slf4j.LoggerFactory

fun main(args: Array<String>) {
    EngineMain.main(args)
}

fun Application.module() {
    val logger = LoggerFactory.getLogger("com.yeshuwahane.fairsplit.Application")

    // Install Koin
    install(Koin) {
        slf4jLogger()
        modules(appModule(environment.config))
    }

    val appConfig = get<AppConfig>()
    val databaseFactory = get<DatabaseFactory>()
    val jwtProvider = get<JwtProvider>()
    val phoneAuthService = get<PhoneAuthService>()
    val tokenService = get<TokenService>()
    val userService = get<UserService>()

    logger.info("Initializing FairSplit Backend [Environment: {}]", appConfig.environment)

    // Install StatusPages
    configureStatusPages()

    // Install ContentNegotiation (JSON)
    install(ContentNegotiation) {
        json(Json {
            prettyPrint = true
            isLenient = true
            ignoreUnknownKeys = true
            encodeDefaults = true
        })
    }

    // Install Call Logging
    install(CallLogging)

    // Install WebSockets
    install(io.ktor.server.websocket.WebSockets) {
        pingPeriodMillis = 15_000L
        timeoutMillis = 30_000L
        maxFrameSize = Long.MAX_VALUE
        masking = false
    }

    // Install Auth Plugin
    configureAuthPlugin(jwtProvider)

    val authIdentityRepository = get<com.yeshuwahane.fairsplit.auth.repository.AuthIdentityRepository>()
    val userRepository = get<com.yeshuwahane.fairsplit.user.repository.UserRepository>()
    val epicService = get<com.yeshuwahane.fairsplit.domain.service.EpicService>()
    val expenseService = get<com.yeshuwahane.fairsplit.domain.service.ExpenseService>()
    val settlementService = get<com.yeshuwahane.fairsplit.domain.service.SettlementService>()
    val epicRepository = get<com.yeshuwahane.fairsplit.domain.repository.EpicRepository>()
    val expenseRepository = get<com.yeshuwahane.fairsplit.domain.repository.ExpenseRepository>()
    val settlementRepository = get<com.yeshuwahane.fairsplit.domain.repository.SettlementRepository>()
    val activityRepository = get<com.yeshuwahane.fairsplit.domain.repository.ActivityRepository>()
    val idempotencyService = get<com.yeshuwahane.fairsplit.domain.service.IdempotencyService>()
    val epicRealtimeBroadcaster = get<com.yeshuwahane.fairsplit.realtime.broadcaster.EpicRealtimeBroadcaster>()

    // Register Routes
    configureHealthRoutes()
    configureAuthRoutes(phoneAuthService, tokenService, appConfig, authIdentityRepository, userRepository)
    configureUserRoutes(userService)
    configureRealtimeRoutes(
        epicRealtimeBroadcaster = epicRealtimeBroadcaster,
        epicRepository = epicRepository,
        jwtProvider = jwtProvider
    )
    configureSyncRoutes(
        epicService = epicService,
        expenseService = expenseService,
        settlementService = settlementService,
        epicRepository = epicRepository,
        expenseRepository = expenseRepository,
        settlementRepository = settlementRepository,
        activityRepository = activityRepository,
        userRepository = userRepository,
        idempotencyService = idempotencyService,
        epicRealtimeBroadcaster = epicRealtimeBroadcaster,
        storageConfig = get()
    )
}

