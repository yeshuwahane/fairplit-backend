package com.yeshuwahane.fairsplit.common.api

import kotlinx.serialization.Serializable

@Serializable
data class ApiError(
    val code: String,
    val message: String,
    val details: String? = null,
    val correlationId: String? = null
)
