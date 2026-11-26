package com.example

import android.content.Context
import com.example.data.AppRepository
import com.example.data.local.AppDatabase
import com.example.ui.components.ChallanNo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Challan numbers are kept on the entry, survive backup/restore and edits, and never show "CH. NO." twice. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ChallanTest {

    private lateinit var repo: AppRepository

    @Before
    fun setUp() {
        val context: Context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("ghanshyam_billing_prefs", Context.MODE_PRIVATE).edit().clear().commit()
        val db = AppDatabase.getInstance(context)
        runBlocking(Dispatchers.IO) { db.clearAllTables() }
        repo = AppRepository(context, asyncInit = false)
        repo.clearAllData()
    }

    @Test
    fun typedPrefixIsNotDoubled() {
        listOf("985", " 985 ", "CH. NO. 985", "ch no 985", "CH.NO.985", "Challan No: 985", "ch-985").forEach {
            assertEquals(it, "985", ChallanNo.clean(it))
        }
        assertEquals("A12", ChallanNo.clean("A12"))
        assertEquals("CH. NO. 985", ChallanNo.label("985"))
        assertEquals("", ChallanNo.label(""))
    }

    @Test
    fun challanSurvivesBackupAndRestore() {
        val party = repo.addCustomer("Test Party", "9876500001")
        repo.addTransaction(party.id, 750.0, "GAVE", note = "blue frocks", challanNo = "985")
        val backup = repo.exportDataAsJsonString()

        repo.clearAllData()
        assertTrue(repo.importDataFromJsonString(backup).success)
        repo.refreshAllInMemoryCaches()

        val tx = repo.transactions.value.single()
        assertEquals("985", tx.challanNo)
        assertEquals("blue frocks", tx.note)
        assertEquals(750.0, tx.amount, 0.001)
    }

    @Test
    fun editingAmountKeepsChallanUnlessChanged() {
        val party = repo.addCustomer("Test Party", "9876500001")
        val tx = repo.addTransaction(party.id, 750.0, "GAVE", challanNo = "985")
        repo.editTransaction(tx.id, 800.0, tx.note, tx.date, tx.paymentMode)
        assertEquals("985", repo.transactions.value.single().challanNo)
        repo.editTransaction(tx.id, 800.0, tx.note, tx.date, tx.paymentMode, challanNo = "986")
        assertEquals("986", repo.transactions.value.single().challanNo)
    }
}
