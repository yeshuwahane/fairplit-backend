package com.yeshuwahane.fairsplit.common.pagination

import kotlinx.serialization.Serializable

data class PageRequest(
    val page: Int = 1,
    val size: Int = 20
) {
    init {
        require(page >= 1) { "Page must be >= 1" }
        require(size in 1..100) { "Page size must be between 1 and 100" }
    }

    val offset: Long
        get() = ((page - 1).toLong()) * size
}

@Serializable
data class PageResult<T>(
    val items: List<T>,
    val total: Long,
    val page: Int,
    val size: Int,
    val hasNext: Boolean
) {
    companion object {
        fun <T> of(items: List<T>, total: Long, request: PageRequest): PageResult<T> = PageResult(
            items = items,
            total = total,
            page = request.page,
            size = request.size,
            hasNext = (request.page.toLong() * request.size) < total
        )
    }
}
