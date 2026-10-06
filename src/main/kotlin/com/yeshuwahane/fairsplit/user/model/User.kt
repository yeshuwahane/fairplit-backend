package com.yeshuwahane.fairsplit.user.model

import java.time.Instant
import java.util.UUID

data class User(
    val id: UUID,
    val name: String,
    val email: String?,
    val phoneNumber: String?,
    val upiId: String?,
    val avatarUrl: String?,
    val profileCompleted: Boolean,
    val createdAt: Instant,
    val updatedAt: Instant,
    val deletedAt: Instant?
)
