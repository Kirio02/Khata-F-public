package com.example

import android.content.Context
import com.example.data.AppRepository
import com.example.data.local.AppDatabase
import com.example.model.AppLanguage
import com.example.model.Customer
import com.example.model.Invoice
import com.example.ui.security.SecureSettingsManager
import com.example.ui.util.AccountantReportGenerator
import com.example.ui.util.VoiceEntryParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.Calendar

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class VerifiedFixesTest {

    private lateinit var context: Context
    private lateinit var repository: AppRepository

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        val prefs = context.getSharedPreferences("ghanshyam_billing_prefs", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        val db = AppDatabase.getInstance(context)
        runBlocking(Dispatchers.IO) {
            db.clearAllTables()
        }
        repository = AppRepository(context, asyncInit = false)
        repository.clearAllData()
    }

    @Test
    fun testVoiceEntryParserMultipliersAndNoFakeDefaults() {
        val customers = listOf(Customer(id = "c1", name = "Suresh Bhai", phone = "9876543210"))

        val thousandEntry = VoiceEntryParser.parse("Suresh ne 5 thousand diye", customers)
        assertEquals(5000.0, thousandEntry.amount, 0.01)
        assertEquals("Suresh Bhai", thousandEntry.partyName)
        assertEquals("GOT", thousandEntry.type)

        val hundredEntry = VoiceEntryParser.parse("Rohan ko 3 hundred diye", customers)
        assertEquals(300.0, hundredEntry.amount, 0.01)
        assertEquals("Rohan", hundredEntry.partyName)

        val wordMultEntry = VoiceEntryParser.parse("five thousand from Amit", customers)
        assertEquals(5000.0, wordMultEntry.amount, 0.01)

        // Word boundary: "so" inside "Suresh" or "Sonal" must not match "so" (100)
        val boundaryEntry = VoiceEntryParser.parse("Sonal ne 250 diye", customers)
        assertEquals(250.0, boundaryEntry.amount, 0.01)
        assertEquals("Sonal", boundaryEntry.partyName)

        // Missing party & amount must NOT default to "Ramesh" and 5000.0
        val emptyEntry = VoiceEntryParser.parse("maine diye", customers)
        assertEquals("", emptyEntry.partyName)
        assertNull(emptyEntry.matchedCustomer)
        assertEquals(0.0, emptyEntry.amount, 0.01)
    }

    @Test
    fun testCreateInvoicePopulatesCashEntryFieldsAndScopesCancellation() {
        val partyA = repository.addCustomer("Party A", "9800000001")
        val partyB = repository.addCustomer("Party B", "9800000002")

        val fy25Date = Calendar.getInstance().apply { set(2025, Calendar.MAY, 10, 12, 0, 0) }.timeInMillis
        val fy26Date = Calendar.getInstance().apply { set(2026, Calendar.MAY, 10, 12, 0, 0) }.timeInMillis

        val invA = repository.createInvoice(
            invoice = Invoice(
                billNumber = "1",
                date = fy25Date,
                customerId = partyA.id,
                customerName = partyA.name,
                customerPhone = partyA.phone,
                subtotal = 1000.0,
                totalAmount = 1000.0,
                paymentMode = "UPI"
            ),
            receivedNow = 1000.0,
            receivedPaymentMode = "UPI"
        )

        // Verify CashEntryEntity fields populated properly for UPI
        val cashEntryA = repository.cashbook.value.find { it.linkedId == invA.id }
        assertNotNull(cashEntryA)
        assertEquals("BANK", cashEntryA!!.bookType)
        assertEquals(fy25Date, cashEntryA.date)
        assertEquals(partyA.name, cashEntryA.partyName)
        assertEquals(partyA.id, cashEntryA.partyId)

        val invB = repository.createInvoice(
            invoice = Invoice(
                billNumber = "1",
                date = fy26Date,
                customerId = partyB.id,
                customerName = partyB.name,
                customerPhone = partyB.phone,
                subtotal = 2000.0,
                totalAmount = 2000.0,
                paymentMode = "CREDIT"
            ),
            receivedNow = 0.0,
            receivedPaymentMode = "CASH"
        )

        assertEquals(1000.0, repository.getInvoiceReceivedAmount(invA), 0.01)
        assertEquals(0.0, repository.getInvoiceReceivedAmount(invB), 0.01)

        // Cancelling invB (billNumber "1" in FY26) must NOT affect invA (billNumber "1" in FY25)
        repository.cancelInvoice(invB.id, "Test cancel")
        assertEquals(1000.0, repository.getInvoiceReceivedAmount(invA), 0.01)
        assertTrue(repository.transactions.value.any { it.customerId == partyA.id && it.billNumber == "1" })
        assertFalse(repository.transactions.value.any { it.customerId == partyB.id && it.billNumber == "1" })
    }

    @Test
    fun testJsonBackupRestorePreservesAllFields() {
        repository.updateStoreProfile(
            storeName = "Test Kids Shop",
            address = "123 Market Road",
            phone = "9876543210",
            upiId = "test@upi",
            gstin = "24ABCDE1234F1Z5",
            ownerName = "Test Owner",
            state = "Gujarat",
            logoUri = "file:///data/user/0/app/files/shop_logo.jpg",
            email = "shop@example.com"
        )
        repository.setLanguage(AppLanguage.GUJARATI)
        repository.updateCashOpeningBalance(1500.0, 1700000000000L)
        repository.updateBankOpeningBalance(3500.0, 1700000000000L)

        val item = repository.addInventoryItem(
            name = "Cotton Frock",
            itemCode = "CF-1",
            hsnCode = "6204",
            salePrice = 450.0,
            purchasePrice = 300.0,
            sizeStock = mapOf("S" to 10, "M" to 15),
            lowStockThreshold = 3,
            packSize = 5,
            packRatio = mapOf("S" to 2, "M" to 3),
            gstPercent = 5.0
        )
        val cust = repository.addCustomer("Meera", "9811122233")
        val due = 1800000000000L
        repository.addTransaction(cust.id, 900.0, "GAVE", "Credit bill", dueDate = due)
        repository.addDeliveryTask(partyName = "Meera", note = "2 boxes")

        val json = repository.exportDataAsJsonString()
        repository.clearAllData()

        val result = repository.importDataFromJsonString(json)
        assertTrue(result.success)

        val restoredProfile = repository.storeProfile.value
        assertEquals("Test Kids Shop", restoredProfile.storeName)
        assertEquals("123 Market Road", restoredProfile.address)
        assertEquals("file:///data/user/0/app/files/shop_logo.jpg", restoredProfile.logoUri)

        assertEquals(AppLanguage.GUJARATI, repository.language.value)
        assertEquals(1500.0, repository.cashOpeningBalance.value, 0.01)
        assertEquals(3500.0, repository.bankOpeningBalance.value, 0.01)

        val restoredItem = repository.inventory.value.find { it.id == item.id }!!
        assertEquals("6204", restoredItem.hsnCode)
        assertEquals(mapOf("S" to 10, "M" to 15), restoredItem.sizeStock)
        assertEquals(3, restoredItem.lowStockThreshold)
        assertEquals(5, restoredItem.packSize)
        assertEquals(mapOf("S" to 2, "M" to 3), restoredItem.packRatio)

        val restoredTx = repository.transactions.value.first()
        assertEquals(due, restoredTx.dueDate)
        assertEquals(1, repository.deliveryTasks.value.size)
    }

    @Test
    fun testAccountantReportHasNoHardcodedAddressOrBank() {
        val profile = com.example.model.StoreProfile(
            storeName = "My Shop",
            address = "MG Road, Ahmedabad"
        )
        val data = AccountantReportGenerator.buildReportData(emptyList(), 0.0, 0.0, 1700000000000L, 1700086400000L)
        val csv = AccountantReportGenerator.generateAccountantReportCsv(context, data, profile)
        val content = csv.readText()
        assertFalse(content.contains("Chota Bazar"))
        assertFalse(content.contains("SPCB"))
        assertTrue(content.contains("MG Road, Ahmedabad"))
    }

    @Test
    fun testGstBillsSettingDefaultsFalseAndPersists() {
        assertFalse(SecureSettingsManager.isGstBillsEnabled(context))
        SecureSettingsManager.setGstBillsEnabled(context, true)
        assertTrue(SecureSettingsManager.isGstBillsEnabled(context))
    }
}
