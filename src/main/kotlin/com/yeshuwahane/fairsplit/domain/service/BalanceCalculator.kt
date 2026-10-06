package com.yeshuwahane.fairsplit.domain.service

import com.yeshuwahane.fairsplit.domain.model.Expense
import com.yeshuwahane.fairsplit.domain.model.ExpenseSplit
import com.yeshuwahane.fairsplit.domain.model.PairwiseDebt
import com.yeshuwahane.fairsplit.domain.model.Settlement
import com.yeshuwahane.fairsplit.domain.model.UserBalance
import java.util.UUID

object BalanceCalculator {

    /**
     * Computes net balance for each user across an epic:
     * totalPaid: Total amount this user paid for expenses + total amount this user paid in settlements
     * totalOwed: Total amount of expense splits assigned to this user + total settlements received by this user
     * netBalance: totalPaid - totalOwed. Positive means user is owed money; negative means user owes money.
     */
    fun calculateNetBalances(
        memberIds: Set<UUID>,
        expenses: List<Expense>,
        splits: List<ExpenseSplit>,
        settlements: List<Settlement>
    ): Map<UUID, UserBalance> {
        val paidMap = mutableMapOf<UUID, Long>().withDefault { 0L }
        val owedMap = mutableMapOf<UUID, Long>().withDefault { 0L }

        // Expenses paid
        for (expense in expenses) {
            paidMap[expense.paidByUserId] = paidMap.getValue(expense.paidByUserId) + expense.amountMinor
        }

        // Expense splits owed
        for (split in splits) {
            owedMap[split.userId] = owedMap.getValue(split.userId) + split.amountMinor
        }

        // Settlements: fromUser paid -> paidMap increases; toUser received -> owedMap increases
        for (settlement in settlements) {
            if (settlement.status.equals("VOIDED", ignoreCase = true)) continue
            paidMap[settlement.fromUserId] = paidMap.getValue(settlement.fromUserId) + settlement.amountMinor
            owedMap[settlement.toUserId] = owedMap.getValue(settlement.toUserId) + settlement.amountMinor
        }

        return memberIds.associateWith { userId ->
            val paid = paidMap.getValue(userId)
            val owed = owedMap.getValue(userId)
            UserBalance(
                userId = userId,
                totalPaidMinor = paid,
                totalOwedMinor = owed,
                netBalanceMinor = paid - owed
            )
        }
    }

    /**
     * Computes bilateral pairwise debts:
     * For each expense:
     *   Payer P paid for participants {S_i}.
     *   For each participant S_i != P: S_i owes P splitAmount.
     * For each settlement:
     *   FromUser paid ToUser amount. This directly reduces the debt from FromUser to ToUser.
     * Debts are netted pairwise:
     *   If A owes B 100 and B owes A 40, the net pairwise debt is A owes B 60.
     */
    fun calculatePairwiseDebts(
        expenses: List<Expense>,
        splitsByExpenseId: Map<UUID, List<ExpenseSplit>>,
        settlements: List<Settlement>
    ): List<PairwiseDebt> {
        // matrix: matrix[A][B] = net amount A owes B
        val debtMatrix = mutableMapOf<Pair<UUID, UUID>, Long>().withDefault { 0L }

        // 1. Accumulate debts from expenses
        for (expense in expenses) {
            val payer = expense.paidByUserId
            val splits = splitsByExpenseId[expense.id] ?: emptyList()
            for (split in splits) {
                if (split.userId != payer) {
                    val key = Pair(split.userId, payer) // split.userId owes payer
                    debtMatrix[key] = debtMatrix.getValue(key) + split.amountMinor
                }
            }
        }

        // 2. Reduce debts from settlements (ignore voided)
        for (settlement in settlements) {
            if (settlement.status.equals("VOIDED", ignoreCase = true)) continue
            val from = settlement.fromUserId
            val to = settlement.toUserId
            // from paid to, so from's debt to to decreases
            val key = Pair(from, to)
            debtMatrix[key] = debtMatrix.getValue(key) - settlement.amountMinor
        }

        // 3. Pairwise net cancellation (if A owes B and B owes A, cancel out)
        val allPairs = debtMatrix.keys.map { setOf(it.first, it.second) }.toSet()
        val result = mutableListOf<PairwiseDebt>()

        for (pairSet in allPairs) {
            val list = pairSet.toList()
            if (list.size == 2) {
                val u1 = list[0]
                val u2 = list[1]
                val u1OwesU2 = debtMatrix.getValue(Pair(u1, u2))
                val u2OwesU1 = debtMatrix.getValue(Pair(u2, u1))
                val net = u1OwesU2 - u2OwesU1

                if (net > 0) {
                    result.add(PairwiseDebt(debtorId = u1, creditorId = u2, amountMinor = net))
                } else if (net < 0) {
                    result.add(PairwiseDebt(debtorId = u2, creditorId = u1, amountMinor = -net))
                }
            }
        }

        return result
    }

    /**
     * Gets bilateral outstanding debt between two specific users:
     * How much fromUserId directly owes toUserId.
     */
    fun getBilateralDebt(
        fromUserId: UUID,
        toUserId: UUID,
        expenses: List<Expense>,
        splitsByExpenseId: Map<UUID, List<ExpenseSplit>>,
        settlements: List<Settlement>
    ): Long {
        val debts = calculatePairwiseDebts(expenses, splitsByExpenseId, settlements)
        return debts.firstOrNull { it.debtorId == fromUserId && it.creditorId == toUserId }?.amountMinor ?: 0L
    }
}
