package com.example

import android.content.Context
import com.example.data.AppRepository
import com.example.data.local.AppDatabase
import com.example.model.Transaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * The in-memory lists are fed by Room flows and can briefly lag the database.
 * Payments must be sized from the database, or a bill gets skipped or paid twice.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class StaleCacheTest {

    private lateinit var repo: AppRepository
    private lateinit var db: AppDatabase

    @Before
    fun setUp() {
        val context: Context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("ghanshyam_billing_prefs", Context.MODE_PRIVATE).edit().clear().commit()
        db = AppDatabase.getInstance(context)
        runBlocking(Dispatchers.IO) { db.clearAllTables() }
        repo = AppRepository(context, asyncInit = false)
        repo.clearAllData()
    }

    /** What a late flow emission does: put an older list back into the cache. */
    private fun makeCacheStale(older: List<Transaction>) {
        val field = AppRepository::class.java.getDeclaredField("_transactions").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        (field.get(repo) as MutableStateFlow<List<Transaction>>).value = older
    }

    private fun paidInDatabase(): Double = runBlocking(Dispatchers.IO) {
        db.transactionDao().getAllList().filter { it.type == "GOT" }.sumOf { it.amount }
    }

    @Test
    fun markAllDoesNotSkipABillTheCacheHasNotSeenYet() {
        val party = repo.addCustomer("Test Party", "9876500001")
        val a = repo.addTransaction(party.id, 100.0, "GAVE")
        val b = repo.addTransaction(party.id, 200.0, "GAVE")
        makeCacheStale(listOf(a))
        repo.markBillsAllReceived(billIds = listOf(a.id, b.id))
        assertEquals(300.0, paidInDatabase(), 0.001)
    }

    @Test
    fun markReceivedDoesNotPayTwiceWhenTheCacheMissesAPayment() {
        val party = repo.addCustomer("Test Party", "9876500001")
        val bill = repo.addTransaction(party.id, 500.0, "GAVE")
        repo.markBillReceived(bill.id, 300.0)
        makeCacheStale(listOf(bill))
        repo.markBillReceived(bill.id, 500.0)
        assertEquals(500.0, paidInDatabase(), 0.001)
    }
}
