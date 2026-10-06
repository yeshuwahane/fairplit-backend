package com.yeshuwahane.fairsplit.user.dto

import com.yeshuwahane.fairsplit.user.model.User
import kotlinx.serialization.Serializable

@Serializable
data class UserDto(
    val id: String,
    val name: String,
    val email: String? = null,
    val phoneNumber: String? = null,
    val upiId: String? = null,
    val avatarUrl: String? = null,
    val profileCompleted: Boolean = false,
    val createdAt: String
) {
    companion object {
        fun fromDomain(user: User): UserDto = UserDto(
            id = user.id.toString(),
            name = user.name,
            email = user.email,
            phoneNumber = user.phoneNumber,
            upiId = user.upiId,
            avatarUrl = user.avatarUrl,
            profileCompleted = user.profileCompleted,
            createdAt = user.createdAt.toString()
        )
    }
}

@Serializable
data class UpdateProfileRequest(
    val name: String? = null,
    val email: String? = null,
    val phone: String? = null,
    val upiId: String? = null,
    val avatarUrl: String? = null
)
