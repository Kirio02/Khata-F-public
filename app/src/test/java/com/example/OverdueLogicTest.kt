package com.example

import com.example.data.OverdueLogic
import com.example.model.Transaction
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OverdueLogicTest {
    private val day = 86_400_000L
    private val now = 1_000L * day

    private fun gave(id: String, amt: Double, daysAgo: Long, due: Long = 0L) =
        Transaction(id = id, customerId = "c", amount = amt, type = "GAVE", date = now - daysAgo * day, dueDate = due)

    private fun got(id: String, amt: Double, daysAgo: Long, linked: String? = null) =
        Transaction(id = id, customerId = "c", amount = amt, type = "GOT", date = now - daysAgo * day, linkedToTxId = linked)

    @Test
    fun oldBillFullyPaidButSmallNewUdharIsNotOverdue() {
        val txs = listOf(gave("b1", 5000.0, 40), got("p1", 5000.0, 30), gave("b2", 300.0, 2))
        assertFalse(OverdueLogic.isOverdue(txs, now))
    }

    @Test
    fun unpaidOldBillIsOverdue() {
        val txs = listOf(gave("b1", 5000.0, 40), got("p1", 1000.0, 30))
        assertTrue(OverdueLogic.isOverdue(txs, now))
    }

    @Test
    fun dueDateInFutureIsNotOverdueEvenIfOld() {
        val txs = listOf(gave("b1", 5000.0, 40, due = now + 5 * day))
        assertFalse(OverdueLogic.isOverdue(txs, now))
    }

    @Test
    fun pastDueDateUnpaidIsOverdueEvenIfNew() {
        val txs = listOf(gave("b1", 5000.0, 3, due = now - day))
        assertTrue(OverdueLogic.isOverdue(txs, now))
    }

    @Test
    fun zeroBalanceIsNeverOverdue() {
        val txs = listOf(gave("b1", 5000.0, 40), got("p1", 5000.0, 1, linked = "b1"))
        assertFalse(OverdueLogic.isOverdue(txs, now))
    }
}
