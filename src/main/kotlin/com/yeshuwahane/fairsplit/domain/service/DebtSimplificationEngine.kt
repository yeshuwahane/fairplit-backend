package com.yeshuwahane.fairsplit.domain.service

import com.yeshuwahane.fairsplit.domain.model.PairwiseDebt
import java.util.UUID

object DebtSimplificationEngine {

    /**
     * Minimizes the number of cash transactions needed to settle all debts.
     * Takes net balances (debtors have negative net, creditors have positive net)
     * and greedily matches the largest debtor with the largest creditor.
     */
    fun simplifyDebts(netBalances: Map<UUID, Long>): List<PairwiseDebt> {
        val debtors = mutableListOf<Pair<UUID, Long>>()  // (userId, amountOwedPositive)
        val creditors = mutableListOf<Pair<UUID, Long>>() // (userId, amountCreditedPositive)

        for ((userId, balance) in netBalances) {
            if (balance < 0) {
                debtors.add(Pair(userId, -balance))
            } else if (balance > 0) {
                creditors.add(Pair(userId, balance))
            }
        }

        debtors.sortByDescending { it.second }
        creditors.sortByDescending { it.second }

        val simplified = mutableListOf<PairwiseDebt>()
        var dIdx = 0
        var cIdx = 0

        while (dIdx < debtors.size && cIdx < creditors.size) {
            val (debtorId, debtorOwes) = debtors[dIdx]
            val (creditorId, creditorGets) = creditors[cIdx]

            val settleAmount = minOf(debtorOwes, creditorGets)
            if (settleAmount > 0) {
                simplified.add(
                    PairwiseDebt(
                        debtorId = debtorId,
                        creditorId = creditorId,
                        amountMinor = settleAmount
                    )
                )
            }

            val remainingDebtor = debtorOwes - settleAmount
            val remainingCreditor = creditorGets - settleAmount

            if (remainingDebtor == 0L) {
                dIdx++
            } else {
                debtors[dIdx] = Pair(debtorId, remainingDebtor)
            }

            if (remainingCreditor == 0L) {
                cIdx++
            } else {
                creditors[cIdx] = Pair(creditorId, remainingCreditor)
            }
        }

        return simplified
    }
}
