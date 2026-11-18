package com.example.data

import com.example.model.Transaction

/**
 * One shared "is this party overdue?" rule, used by the Khata home, the daily report, the AI and the summary.
 *
 * A party is overdue only when a credit bill is STILL UNPAID and either its due date has passed, or (no due date)
 * it is older than [graceDays]. Payments are applied the same way as the bill screens do it: payments linked to a
 * bill first, then every other payment to the oldest bills first. So a party who already paid an old bill and only
 * owes a small new udhar is NOT overdue and gets no reminder.
 */
object OverdueLogic {
    private const val DAY_MS = 86_400_000L

    /** Unpaid amount of every customer credit bill ("GAVE" without linkedToTxId) of ONE party. */
    private fun outstandingByBill(partyTxs: List<Transaction>): Map<String, Double> {
        val bills = partyTxs.filter { it.type == "GAVE" && it.linkedToTxId == null }.sortedBy { it.date }
        val billIds = bills.map { it.id }.toHashSet()
        val payments = partyTxs.filter { it.type == "GOT" }

        val received = HashMap<String, Double>()
        var pool = 0.0
        val linkedByBill = payments
            .filter { it.linkedToTxId != null && it.linkedToTxId in billIds }
            .groupBy { it.linkedToTxId!! }
        for (bill in bills) {
            val linked = linkedByBill[bill.id]?.sumOf { it.amount } ?: 0.0
            val applied = minOf(linked, bill.amount)
            received[bill.id] = applied
            pool += (linked - applied)
        }
        pool += payments
            .filter { it.linkedToTxId == null || it.linkedToTxId !in billIds }
            .sumOf { it.amount }
        for (bill in bills) {
            if (pool <= 0.0) break
            val room = bill.amount - (received[bill.id] ?: 0.0)
            if (room > 0.0) {
                val take = minOf(room, pool)
                received[bill.id] = (received[bill.id] ?: 0.0) + take
                pool -= take
            }
        }
        return bills.associate { it.id to (it.amount - (received[it.id] ?: 0.0)).coerceAtLeast(0.0) }
    }

    /** [partyTxs] must be the transactions of a single customer (not a supplier). */
    fun isOverdue(
        partyTxs: List<Transaction>,
        now: Long = System.currentTimeMillis(),
        graceDays: Int = 15
    ): Boolean {
        val balance = partyTxs.filter { it.type == "GAVE" }.sumOf { it.amount } -
            partyTxs.filter { it.type == "GOT" }.sumOf { it.amount }
        if (balance <= 0.01) return false

        val open = outstandingByBill(partyTxs)
        return partyTxs.any { t ->
            t.type == "GAVE" && t.linkedToTxId == null && (open[t.id] ?: 0.0) > 0.01 &&
                (if (t.dueDate > 0L) t.dueDate <= now else now - t.date >= graceDays * DAY_MS)
        }
    }
}
