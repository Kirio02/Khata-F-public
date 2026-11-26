package com.example

import android.content.Context
import com.example.data.AppRepository
import com.example.data.local.AppDatabase
import com.example.ui.components.ChallanNo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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

    @Test
    fun fromOldNoteExtractsChallanOnlyWhenWholeNoteIsAChallanNumber() {
        assertEquals("852", ChallanNo.fromOldNote("852"))
        assertEquals("985", ChallanNo.fromOldNote(" CH. NO. 985 "))
        assertEquals("12", ChallanNo.fromOldNote("ch no 12"))
        assertEquals("77", ChallanNo.fromOldNote("challan no. 77"))
        assertNull(ChallanNo.fromOldNote("4 frock"))
        assertNull(ChallanNo.fromOldNote("985 + 986"))
        assertNull(ChallanNo.fromOldNote("upi 500"))
        assertNull(ChallanNo.fromOldNote(""))
    }

    @Test
    fun moveOldChallanNotesMovesOnlyBillEntriesWithChallanNotes() {
        val customer = repo.addCustomer("Cust", "9876500002", type = "CUSTOMER")
        val supplier = repo.addCustomer("Supp", "9876500003", type = "SUPPLIER")

        val custBill = repo.addTransaction(customer.id, 500.0, "GAVE", note = " CH. NO. 985 ")
        val suppBill = repo.addTransaction(supplier.id, 700.0, "GOT", note = "852")
        val custPayment = repo.addTransaction(customer.id, 200.0, "GOT", note = "999")
        val alreadyHasChallan = repo.addTransaction(customer.id, 300.0, "GAVE", note = "111", challanNo = "50")
        val textNoteBill = repo.addTransaction(customer.id, 400.0, "GAVE", note = "4 frock")

        val moved = runBlocking { repo.moveOldChallanNotes() }
        assertEquals(2, moved)

        val byId = repo.transactions.value.associateBy { it.id }
        assertEquals("985", byId.getValue(custBill.id).challanNo)
        assertEquals("", byId.getValue(custBill.id).note)
        assertEquals("852", byId.getValue(suppBill.id).challanNo)
        assertEquals("", byId.getValue(suppBill.id).note)
        assertEquals("", byId.getValue(custPayment.id).challanNo)
        assertEquals("999", byId.getValue(custPayment.id).note)
        assertEquals("50", byId.getValue(alreadyHasChallan.id).challanNo)
        assertEquals("111", byId.getValue(alreadyHasChallan.id).note)
        assertEquals("", byId.getValue(textNoteBill.id).challanNo)
        assertEquals("4 frock", byId.getValue(textNoteBill.id).note)
    }

    @Test
    fun restoringOldBackupMovesOldChallanNotes() {
        val customer = repo.addCustomer("Old Party", "9876500004")
        val bill = repo.addTransaction(customer.id, 600.0, "GAVE", note = "challan no. 77")
        val backup = repo.exportDataAsJsonString()

        repo.clearAllData()
        assertTrue(repo.importDataFromJsonString(backup).success)

        val restored = repo.transactions.value.single { it.id == bill.id }
        assertEquals("77", restored.challanNo)
        assertEquals("", restored.note)
    }
}
