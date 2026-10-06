package com.yeshuwahane.fairsplit.di

import com.yeshuwahane.fairsplit.auth.repository.*
import com.yeshuwahane.fairsplit.auth.service.PhoneAuthService
import com.yeshuwahane.fairsplit.auth.service.TokenService
import com.yeshuwahane.fairsplit.config.AppConfig
import com.yeshuwahane.fairsplit.config.DatabaseConfig
import com.yeshuwahane.fairsplit.config.JwtConfig
import com.yeshuwahane.fairsplit.config.StorageConfig
import com.yeshuwahane.fairsplit.infrastructure.auth.JwtProvider
import com.yeshuwahane.fairsplit.infrastructure.database.DatabaseFactory
import com.yeshuwahane.fairsplit.user.repository.UserRepository
import com.yeshuwahane.fairsplit.user.repository.UserRepositoryImpl
import io.ktor.server.config.*
import org.koin.dsl.module

fun appModule(applicationConfig: ApplicationConfig) = module {
    // Configurations
    single { AppConfig.fromConfig(applicationConfig) }
    single { DatabaseConfig.fromConfig(applicationConfig) }
    single { JwtConfig.fromConfig(applicationConfig) }
    single { StorageConfig.fromConfig(applicationConfig) }

    // Database & Infrastructure
    single { DatabaseFactory(get()).init() }
    single { JwtProvider(get()) }

    // Repositories
    single<UserRepository> { UserRepositoryImpl() }
    single<AuthIdentityRepository> { AuthIdentityRepositoryImpl() }
    single<OtpRepository> { OtpRepositoryImpl() }
    single<RefreshSessionRepository> { RefreshSessionRepositoryImpl() }
    single<com.yeshuwahane.fairsplit.domain.repository.EpicRepository> { com.yeshuwahane.fairsplit.data.repository.EpicRepositoryImpl() }
    single<com.yeshuwahane.fairsplit.domain.repository.ExpenseRepository> { com.yeshuwahane.fairsplit.data.repository.ExpenseRepositoryImpl() }
    single<com.yeshuwahane.fairsplit.domain.repository.SettlementRepository> { com.yeshuwahane.fairsplit.data.repository.SettlementRepositoryImpl() }
    single<com.yeshuwahane.fairsplit.domain.repository.ActivityRepository> { com.yeshuwahane.fairsplit.data.repository.ActivityRepositoryImpl() }
    single<com.yeshuwahane.fairsplit.domain.repository.IdempotencyRepository> { com.yeshuwahane.fairsplit.data.repository.IdempotencyRepositoryImpl() }

    // Services
    single { TokenService(get(), get(), get()) }
    single { PhoneAuthService(get(), get(), get(), get(), get()) }
    single { com.yeshuwahane.fairsplit.user.service.UserService(get()) }
    single { com.yeshuwahane.fairsplit.domain.service.EpicService(get(), get(), get(), get()) }
    single { com.yeshuwahane.fairsplit.domain.service.ExpenseService(get(), get(), get(), get()) }
    single { com.yeshuwahane.fairsplit.domain.service.SettlementService(get(), get(), get(), get()) }
    single { com.yeshuwahane.fairsplit.domain.service.IdempotencyService(get()) }

    // Realtime Broadcaster
    single<com.yeshuwahane.fairsplit.realtime.broadcaster.EpicRealtimeBroadcaster> {
        com.yeshuwahane.fairsplit.realtime.broadcaster.InMemoryEpicRealtimeBroadcaster()
    }
}


