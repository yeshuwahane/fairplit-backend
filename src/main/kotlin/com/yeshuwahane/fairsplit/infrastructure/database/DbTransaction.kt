package com.yeshuwahane.fairsplit.infrastructure.database

import kotlinx.coroutines.Dispatchers
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.Transaction
import org.jetbrains.exposed.sql.transactions.TransactionManager
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction

suspend fun <T> dbQuery(db: Database? = null, block: suspend Transaction.() -> T): T {
    val currentTx = TransactionManager.currentOrNull()
    return if (currentTx != null) {
        // Re-use current active transaction to preserve atomicity across multiple services/repos
        currentTx.block()
    } else {
        newSuspendedTransaction(Dispatchers.IO, db) {
            block()
        }
    }
}
