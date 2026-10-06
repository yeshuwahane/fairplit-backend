package com.yeshuwahane.fairsplit.user.service

import com.yeshuwahane.fairsplit.common.error.AppException
import com.yeshuwahane.fairsplit.common.error.ErrorCode
import com.yeshuwahane.fairsplit.user.dto.UpdateProfileRequest
import com.yeshuwahane.fairsplit.user.dto.UserDto
import com.yeshuwahane.fairsplit.user.repository.UserRepository
import java.util.UUID

class UserService(private val userRepository: UserRepository) {

    suspend fun getProfile(userId: UUID): UserDto {
        val user = userRepository.findById(userId)
            ?: throw AppException.NotFound("User not found", ErrorCode.USER_NOT_FOUND)
        return UserDto.fromDomain(user)
    }

    suspend fun updateProfile(userId: UUID, req: UpdateProfileRequest): UserDto {
        val updated = userRepository.update(
            userId = userId,
            name = req.name?.trim(),
            email = req.email?.trim(),
            phone = req.phone?.trim(),
            upiId = req.upiId?.trim(),
            avatarUrl = req.avatarUrl
        ) ?: throw AppException.NotFound("User not found", ErrorCode.USER_NOT_FOUND)
        return UserDto.fromDomain(updated)
    }
}
