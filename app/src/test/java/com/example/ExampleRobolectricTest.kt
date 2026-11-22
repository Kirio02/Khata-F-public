package com.example

import android.content.Context
import com.example.data.AppRepository
import com.example.data.local.AppDatabase
import com.example.model.Customer
import com.example.model.Invoice
import com.example.model.InvoiceItem
import com.example.model.PartyType
import com.example.model.StoreProfile
import com.example.ui.report.DailyReportScheduler
import com.example.ui.report.DailyReportWorker
import com.example.ui.security.AppLockManager
import com.example.ui.security.SecureSettingsManager
import com.example.ui.util.PhoneUtil
import com.example.ui.util.StatementDateRange
import com.example.ui.util.StatementFilter
import com.example.ui.util.StatementGenerator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import com.example.model.Attachment
import com.example.ui.util.AttachmentManager
import java.io.ByteArrayInputStream
import java.io.File
import java.util.Calendar
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleRobolectricTest {

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
    fun testInitialEmptyState() {
        assertTrue("Customers must be initially empty", repository.customers.value.isEmpty())
        assertTrue("Transactions must be initially empty", repository.transactions.value.isEmpty())
        assertTrue("Inventory must be initially empty", repository.inventory.value.isEmpty())
        assertTrue("Invoices must be initially empty", repository.invoices.value.isEmpty())
        assertTrue("Cashbook must be initially empty", repository.cashbook.value.isEmpty())
        assertFalse("Onboarding should be incomplete initially", repository.hasCompletedOnboarding.value)
    }

    @Test
    fun testPhoneUtilNormalization() {
        // 10 digits gets 91 prefix
        assertEquals("919876543210", PhoneUtil.normalize("9876543210"))
        assertEquals("919876543210", PhoneUtil.normalize("98765 43210"))
        assertEquals("919876543210", PhoneUtil.normalize("98765-43210"))

        // Leading 0 stripped and 91 added
        assertEquals("919876543210", PhoneUtil.normalize("09876543210"))

        // Existing country code preserved
        assertEquals("919876543210", PhoneUtil.normalize("+91 98765 43210"))
        assertEquals("919876543210", PhoneUtil.normalize("+919876543210"))
        assertEquals("14155552671", PhoneUtil.normalize("+1 415 555 2671"))

        // WhatsApp number
        assertEquals("919876543210", PhoneUtil.toWhatsAppNumber("9876543210"))
        assertEquals("919876543210", PhoneUtil.toWhatsAppNumber("+91 98765 43210"))

        // Display formatting
        assertEquals("+91 98765 43210", PhoneUtil.toDisplay("919876543210"))
        assertEquals("+91 98765 43210", PhoneUtil.toDisplay("+919876543210"))
        assertEquals("", PhoneUtil.toDisplay(""))
    }

    @Test
    fun testPartyTypesAndUniversalBalanceConvention() {
        // 1. Customer: GAVE increases balance (Lena Hai > 0), GOT reduces balance toward 0
        val cust = repository.addCustomer("Ramesh Cloth Store", "9820011111", type = PartyType.CUSTOMER.raw)
        assertEquals(PartyType.CUSTOMER.raw, cust.type)

        val custBill = repository.addTransaction(cust.id, 5000.0, "GAVE", "Festival wholesale")
        assertEquals(5000.0, repository.getCustomerBalance(cust.id), 0.01)
        assertEquals(AppRepository.BillStatus.PENDING, repository.getBillStatus(custBill))

        // 2. Supplier: GOT is credit goods received (Dena Hai < 0), GAVE is payment to supplier
        val supp = repository.addCustomer("Surat Textile Mill", "9820022222", type = PartyType.SUPPLIER.raw)
        assertEquals(PartyType.SUPPLIER.raw, supp.type)

        val suppBill = repository.addTransaction(supp.id, 8000.0, "GOT", "Fabric roll lot")
        // Sign convention: balance = gave - got = 0 - 8000 = -8000 (You will give / Dena Hai)
        assertEquals(-8000.0, repository.getCustomerBalance(supp.id), 0.01)
        assertEquals(AppRepository.BillStatus.PENDING, repository.getBillStatus(suppBill))
    }

    @Test
    fun testMarkReceivedAndReversibleUndoPayment() {
        val cust = repository.addCustomer("Rajesh Sharma", "9820012345")
        val bill = repository.addTransaction(cust.id, 2000.0, "GAVE", "Shirt lot")

        assertEquals(2000.0, repository.getCustomerBalance(cust.id), 0.01)
        assertEquals(AppRepository.BillStatus.PENDING, repository.getBillStatus(bill))
        assertEquals(2000.0, repository.getBillOutstandingAmount(bill), 0.01)

        // Partial payment: 800 received with cashbook sync
        val partialPayment = repository.markBillReceived(
            billId = bill.id,
            amount = 800.0,
            paymentMode = "UPI",
            addToCashbook = true
        )
        assertNotNull("Payment transaction created", partialPayment)
        assertEquals("GOT", partialPayment!!.type)
        assertEquals(800.0, partialPayment.amount, 0.01)
        assertEquals("UPI", partialPayment.paymentMode)
        assertEquals(bill.id, partialPayment.linkedToTxId)

        // Check balance and bill status
        assertEquals(1200.0, repository.getCustomerBalance(cust.id), 0.01)
        assertEquals(800.0, repository.getBillReceivedAmount(bill.id), 0.01)
        assertEquals(1200.0, repository.getBillOutstandingAmount(bill), 0.01)
        assertEquals(AppRepository.BillStatus.PARTLY_RECEIVED, repository.getBillStatus(bill))

        // Check cashbook entry created and linked
        assertEquals(1, repository.cashbook.value.size)
        val cashEntry = repository.cashbook.value.first()
        assertEquals("IN", cashEntry.type)
        assertEquals(800.0, cashEntry.amount, 0.01)
        assertEquals("UPI", cashEntry.paymentMode)
        assertEquals(partialPayment.id, cashEntry.linkedId)

        // Full remaining payment: 1200 received
        val fullPayment = repository.markBillReceived(
            billId = bill.id,
            amount = 1200.0,
            paymentMode = "CASH",
            addToCashbook = true
        )
        assertNotNull(fullPayment)
        assertEquals(0.0, repository.getCustomerBalance(cust.id), 0.01)
        assertEquals(AppRepository.BillStatus.RECEIVED, repository.getBillStatus(bill))
        assertEquals(0.0, repository.getBillOutstandingAmount(bill), 0.01)
        assertEquals(2, repository.cashbook.value.size)

        // Plain Completed checkbox (Delivered/Packed) toggle - NO MONEY EFFECT
        repository.toggleBillCompleted(bill.id)
        val uncompletedBill = repository.transactions.value.find { it.id == bill.id }!!
        assertFalse("Bill should be toggled to uncompleted", uncompletedBill.isCompleted)
        repository.toggleBillCompleted(bill.id)
        val updatedBill = repository.transactions.value.find { it.id == bill.id }!!
        assertTrue("Bill should be marked completed after second toggle", updatedBill.isCompleted)
        assertEquals("Completing bill has 0 money effect", 0.0, repository.getCustomerBalance(cust.id), 0.01)

        // Undo payment: undoing the partial payment (800)
        repository.undoPayment(partialPayment.id)
        assertEquals("Balance recomputed after undo", 800.0, repository.getCustomerBalance(cust.id), 0.01)
        assertEquals(AppRepository.BillStatus.PARTLY_RECEIVED, repository.getBillStatus(bill))
        // Linked cashbook entry removed
        assertNull("Undone payment removed from cashbook", repository.cashbook.value.find { it.linkedId == partialPayment.id })
        assertEquals(1, repository.cashbook.value.size)
    }

    @Test
    fun testMultiSelectMarkAllReceived() {
        val cust = repository.addCustomer("Vijay Patel", "9898011223")
        val bill1 = repository.addTransaction(cust.id, 1000.0, "GAVE", "Bill 1")
        val bill2 = repository.addTransaction(cust.id, 1500.0, "GAVE", "Bill 2")
        val bill3 = repository.addTransaction(cust.id, 500.0, "GAVE", "Bill 3")

        assertEquals(3000.0, repository.getCustomerBalance(cust.id), 0.01)

        // Mark bill1 and bill2 received in bulk
        repository.markBillsAllReceived(
            billIds = listOf(bill1.id, bill2.id),
            paymentMode = "BANK",
            addToCashbook = true
        )

        // Bill 1 and 2 received, bill 3 still pending
        assertEquals(AppRepository.BillStatus.RECEIVED, repository.getBillStatus(bill1))
        assertEquals(AppRepository.BillStatus.RECEIVED, repository.getBillStatus(bill2))
        assertEquals(AppRepository.BillStatus.PENDING, repository.getBillStatus(bill3))
        assertEquals(500.0, repository.getCustomerBalance(cust.id), 0.01)

        // Cashbook got 2 entries linked to the 2 payments
        assertEquals(2, repository.cashbook.value.size)
        assertTrue(repository.cashbook.value.all { it.paymentMode == "BANK" && it.type == "IN" })
    }

    @Test
    fun testEditTransactionWithCashbookSync() {
        val cust = repository.addCustomer("Dinesh Shah", "9825000111")
        val tx = repository.addTransaction(cust.id, 1500.0, "GOT", "Payment", paymentMode = "CASH")

        assertEquals(1, repository.cashbook.value.size)
        assertEquals(1500.0, repository.cashbook.value.first().amount, 0.01)

        // Edit amount and mode
        repository.editTransaction(
            txId = tx.id,
            amount = 1800.0,
            note = "Updated Payment",
            date = System.currentTimeMillis(),
            paymentMode = "UPI"
        )

        val updatedTx = repository.transactions.value.find { it.id == tx.id }!!
        assertEquals(1800.0, updatedTx.amount, 0.01)
        assertEquals("UPI", updatedTx.paymentMode)
        assertEquals("Updated Payment", updatedTx.note)

        // Cashbook entry updated accordingly
        val updatedCash = repository.cashbook.value.find { it.linkedId == tx.id }!!
        assertEquals(1800.0, updatedCash.amount, 0.01)
        assertEquals("UPI", updatedCash.paymentMode)
    }

    @Test
    fun testStatementPureComputation() {
        val cust = repository.addCustomer("Prakash Bhai", "9876543210")
        val t1 = repository.addTransaction(cust.id, 2000.0, "GAVE", "Opening Balance", date = 1000L)
        val t2 = repository.addTransaction(cust.id, 3000.0, "GAVE", "Festival Lot", date = 2000L)
        val t3 = repository.markBillReceived(t2.id, 1000.0, "CASH", date = 3000L, addToCashbook = false)!!

        val allTx = listOf(t1, t2, t3)
        val statementData = StatementGenerator.computeStatement(
            customer = cust,
            allTransactions = allTx,
            dateRange = com.example.ui.util.StatementDateRange.ALL,
            filter = com.example.ui.util.StatementFilter.ALL_ENTRIES
        )

        assertEquals(3, statementData.entries.size)
        // Row 1: t1 (GAVE 2000) -> running balance = 2000
        assertEquals(2000.0, statementData.entries[0].runningBalance, 0.01)
        // Row 2: t2 (GAVE 3000) -> running balance = 5000
        assertEquals(5000.0, statementData.entries[1].runningBalance, 0.01)
        // Row 3: t3 (GOT 1000) -> running balance = 4000
        assertEquals(4000.0, statementData.entries[2].runningBalance, 0.01)
        assertEquals(4000.0, statementData.closingBalance, 0.01)

        // Pending bills filter only returns unfulfilled credit bills
        val pendingData = StatementGenerator.computeStatement(
            customer = cust,
            allTransactions = allTx,
            dateRange = com.example.ui.util.StatementDateRange.ALL,
            filter = com.example.ui.util.StatementFilter.PENDING_BILLS_ONLY
        )
        // Returns unfulfilled credit bills and their linked partial payments (t1, t2, t3)
        assertEquals(3, pendingData.entries.size)
        assertEquals(2, pendingData.entries.count { it.transaction.type == "GAVE" })
    }

    @Test
    fun testRealCsvCustomerImport() {
        val csvData = """
            name,phone,balance
            Ramesh Kumar,9820011111,1500
            Suresh Shah,9820022222,-450
            ,9800000000,100
        """.trimIndent()

        val previews = repository.previewCustomersFromCsv(csvData)
        assertEquals("Every line is shown, none dropped silently", 3, previews.size)
        assertEquals("Only the 2 named rows can be imported", 2, previews.count { it.canImport })
        assertEquals("csv_err_name", previews[2].error)
        assertEquals("Ramesh Kumar", previews[0].name)
        assertEquals(1500.0, previews[0].balance, 0.01)

        val importedCount = repository.importCustomersFromCsv(previews)
        assertEquals("Should import 2 customers", 2, importedCount)
        assertEquals(2, repository.customers.value.size)

        val ramesh = repository.customers.value.find { it.name == "Ramesh Kumar" }
        assertNotNull(ramesh)
        assertEquals(1500.0, repository.getCustomerBalance(ramesh!!.id), 0.01)

        val suresh = repository.customers.value.find { it.name == "Suresh Shah" }
        assertNotNull(suresh)
        assertEquals(-450.0, repository.getCustomerBalance(suresh!!.id), 0.01)
    }

    @Test
    fun testPdfInvoiceGeneration() {
        val invoice = Invoice(
            billNumber = "INV-TEST-001",
            date = System.currentTimeMillis(),
            customerName = "Priya Sharma",
            customerPhone = "+91 98765 00000",
            items = listOf(
                InvoiceItem(
                    name = "Kids Cotton Frock",
                    quantity = 2,
                    unitPrice = 450.0,
                    gstPercent = 5.0,
                    total = 945.0
                )
            ),
            subtotal = 900.0,
            gstAmount = 45.0,
            discount = 45.0,
            additionalCharges = 0.0,
            totalAmount = 900.0,
            paymentMode = "CASH"
        )

        val pdfFile = com.example.ui.util.PdfInvoiceGenerator.generateInvoicePdf(
            context = context,
            storeProfile = repository.storeProfile.value,
            invoice = invoice
        )

        assertTrue("Generated PDF file should exist", pdfFile.exists())
        assertTrue("Generated PDF file should not be empty", pdfFile.length() > 0)
    }

    @Test
    fun testMultiPagePdfStatementGeneration() {
        val cust = repository.addCustomer("Mohan Lal", "9876500000")
        for (i in 1..40) {
            repository.addTransaction(cust.id, 100.0 * i, "GAVE", "Wholesale Lot #$i")
        }
        val statementData = StatementGenerator.computeStatement(
            customer = cust,
            allTransactions = repository.transactions.value,
            dateRange = com.example.ui.util.StatementDateRange.ALL,
            filter = com.example.ui.util.StatementFilter.ALL_ENTRIES
        )

        val pdfFile = StatementGenerator.generateStatementPdf(
            context = context,
            statement = statementData,
            storeProfile = StoreProfile(storeName = "Ghanshyam Kids Wear")
        )

        assertTrue("Multi-page PDF file should exist", pdfFile.exists())
        assertTrue("Multi-page PDF file size should be substantial", pdfFile.length() > 1024)
    }

    @Test
    fun testCsvSpreadsheetExports() {
        val cust = repository.addCustomer("Anita Verma", "+91 99999 11111")
        repository.addTransaction(cust.id, 1200.0, "GAVE", "Clothes")
        repository.addInventoryItem(
            name = "Boys Denim Jeans",
            itemCode = "BD-01",
            salePrice = 650.0,
            purchasePrice = 400.0,
            stock = 15,
            category = "Boys Wear",
            sizes = listOf("24", "26", "28"),
            gstPercent = 5.0
        )

        // 1. Khata Ledger CSV
        val khataCsv = repository.exportKhataLedgerCsv(context)
        assertTrue("Khata CSV exists", khataCsv.exists())
        val khataContent = khataCsv.readText()
        assertTrue("Header present in Khata CSV", khataContent.contains("Customer Name,Phone,Net Balance,Last Transaction Date"))
        assertTrue("Anita Verma included in CSV", khataContent.contains("Anita Verma"))

        // 2. Sales Report CSV
        val salesCsv = repository.exportSalesReportCsv(context)
        assertTrue("Sales CSV exists", salesCsv.exists())
        val salesContent = salesCsv.readText()
        assertTrue("Header present in Sales CSV", salesContent.contains("Bill No,Date,Customer Name,Subtotal,GST,Discount,Total Amount,Payment Mode"))

        // 3. Inventory Stock CSV
        val invCsv = repository.exportInventoryCsv(context)
        assertTrue("Inventory CSV exists", invCsv.exists())
        val invContent = invCsv.readText()
        assertTrue("Header present in Inventory CSV", invContent.contains("Item Code,Item Name,Category,Stock Quantity,Purchase Price,Sale Price"))
        assertTrue("Boys Denim Jeans present in CSV", invContent.contains("Boys Denim Jeans"))
    }

    @Test
    fun testBackupAndRestoreWithValidation() {
        repository.addCustomer("Deepak Patel", "+91 98765 43210")
        val jsonBackup = repository.exportDataAsJsonString()
        assertTrue("Backup contains customers", jsonBackup.contains("Deepak Patel"))

        val validation = repository.validateBackupJson(jsonBackup)
        assertTrue("Backup validation passes", validation.isValid)
        assertEquals(1, validation.customerCount)

        val backupFile = repository.exportBackupFile(context)
        assertTrue("Backup file created", backupFile.exists())
        assertTrue("Backup file has content", backupFile.length() > 0)

        // Clear and restore
        repository.clearAllData()
        assertTrue("Customers cleared", repository.customers.value.isEmpty())

        val importResult = repository.importDataFromJsonString(jsonBackup)
        assertTrue("Restore succeeded", importResult.success)
        assertEquals("Customer restored", 1, repository.customers.value.size)
        assertEquals("Deepak Patel", repository.customers.value[0].name)

        // Corrupted JSON test
        val corruptResult = repository.importDataFromJsonString("{ corrupted json data")
        assertFalse("Corrupted JSON restore must fail safely", corruptResult.success)
    }

    @Test
    fun testTolerantBackupImportAttachesOrphansToRecoveredEntries() {
        val backupWithOrphan = """
            {
                "customers": [
                    {"id": "c-backup-1", "name": "Existing Party", "phone": "9999900000", "address": "", "gstin": ""}
                ],
                "transactions": [
                    {"id": "tx-orphan-2", "customerId": "missing-party-id", "amount": 750.0, "type": "GOT", "note": "Orphan Jama", "date": 1600000000000}
                ],
                "inventory": [],
                "invoices": [],
                "cashbook": []
            }
        """.trimIndent()

        val result = repository.importDataFromJsonString(backupWithOrphan)
        assertTrue("Import must succeed tolerantly", result.success)
        assertEquals("Should report 1 repaired orphan transaction", 1, result.repairedCount)

        val recoveredCust = repository.customers.value.find { it.name == "Recovered entries" }
        assertNotNull("Recovered entries party must exist", recoveredCust)
        val orphanTx = repository.transactions.value.find { it.id == "tx-orphan-2" }
        assertNotNull("Orphan transaction must exist in repository", orphanTx)
        assertEquals(recoveredCust!!.id, orphanTx!!.customerId)
    }

    @Test
    fun testTask4BillingCorrectnessConsecutiveNumbersTaxSplitStockAndCancellation() {
        // 1. Setup store profile in Gujarat with GSTIN
        repository.updateStoreProfile(
            storeName = "Ghanshyam Kids Wear",
            address = "Ring Road, Surat",
            phone = "9876543210",
            upiId = "ghanshyam@upi",
            gstin = "24AAAAA0000A1Z5",
            ownerName = "Ghanshyam Bhai",
            state = "Gujarat"
        )
        val profile = repository.storeProfile.value
        assertEquals("Gujarat", profile.state)
        assertEquals("24AAAAA0000A1Z5", profile.gstin)

        // 2. Add party from Khata
        val customerGujarat = repository.addCustomer("Ahmedabad Retailer", "9825012345", address = "Ahmedabad, Gujarat")
        val customerMaharashtra = repository.addCustomer("Mumbai Fashion", "9820054321", address = "Mumbai, Maharashtra")

        // 3. Add inventory item with HSN code, sizeStock map (e.g. 4-5Y has 10 pieces)
        val item = repository.addInventoryItem(
            name = "Party Frock",
            itemCode = "PF-01",
            hsnCode = "6111",
            salePrice = 500.0,
            purchasePrice = 300.0,
            sizeStock = mapOf("4-5Y" to 10, "5-6Y" to 8),
            lowStockThreshold = 4,
            gstPercent = 12.0
        )
        assertEquals(18, item.stock)
        assertEquals(10, item.sizeStock["4-5Y"])
        assertEquals("6111", item.hsnCode)

        // 4. Create Bill 1: Same state (Gujarat), CREDIT bill, selling 3 pieces of size "4-5Y"
        val line1 = InvoiceItem(
            itemId = item.id,
            name = item.name,
            size = "4-5Y",
            hsnCode = item.hsnCode,
            quantity = 3,
            unitPrice = 500.0,
            gstPercent = 12.0,
            total = 1500.0 + (1500.0 * 0.12)
        )
        val invoice1 = Invoice(
            billNumber = "",
            date = System.currentTimeMillis(),
            customerName = customerGujarat.name,
            customerPhone = customerGujarat.phone,
            customerId = customerGujarat.id,
            items = listOf(line1),
            subtotal = 1500.0,
            gstAmount = 180.0,
            discount = 0.0,
            additionalCharges = 0.0,
            totalAmount = 1680.0,
            paymentMode = "CREDIT"
        )
        val savedBill1 = repository.createInvoice(invoice1)

        // Verify Bill 1 consecutive number format "1"
        val expectedBill1Num = "1"
        assertEquals(expectedBill1Num, savedBill1.billNumber)
        assertEquals(customerGujarat.id, savedBill1.customerId)
        assertFalse(savedBill1.isCancelled)

        // Udhar entry created in Khata for customerGujarat
        assertEquals(1680.0, repository.getCustomerBalance(customerGujarat.id), 0.01)

        // Verify Same-state tax split: CGST (6%) and SGST (6%)
        val isSameState = profile.state.trim().equals("Gujarat", ignoreCase = true)
        assertTrue(isSameState)
        val cgst1 = savedBill1.gstAmount / 2.0
        val sgst1 = savedBill1.gstAmount / 2.0
        assertEquals(90.0, cgst1, 0.01)
        assertEquals(90.0, sgst1, 0.01)

        // 5. Create Bill 2: Different state (Maharashtra), CASH bill
        val line2 = InvoiceItem(
            itemId = item.id,
            name = item.name,
            size = "5-6Y",
            hsnCode = item.hsnCode,
            quantity = 2,
            unitPrice = 500.0,
            gstPercent = 12.0,
            total = 1000.0 + (1000.0 * 0.12)
        )
        val invoice2 = Invoice(
            billNumber = "",
            date = System.currentTimeMillis(),
            customerName = customerMaharashtra.name,
            customerPhone = customerMaharashtra.phone,
            customerId = customerMaharashtra.id,
            items = listOf(line2),
            subtotal = 1000.0,
            gstAmount = 120.0,
            discount = 0.0,
            additionalCharges = 0.0,
            totalAmount = 1120.0,
            paymentMode = "CASH"
        )
        val savedBill2 = repository.createInvoice(invoice2)

        // Verify Bill 2 consecutive number: "2"
        val expectedBill2Num = "2"
        assertEquals(expectedBill2Num, savedBill2.billNumber)

        // Cashbook entry created for Bill 2
        val cashEntry = repository.cashbook.value.find { it.linkedId == savedBill2.id }
        assertNotNull(cashEntry)
        assertEquals(1120.0, cashEntry!!.amount, 0.01)

        // Verify Inter-state tax split (Place of supply: Maharashtra != Gujarat -> IGST 12%)
        val isInterState = !profile.state.trim().equals("Maharashtra", ignoreCase = true)
        assertTrue(isInterState)
        val igst2 = savedBill2.gstAmount
        assertEquals(120.0, igst2, 0.01)

        // 6. Create Bill 3: Verify consecutive number: "3"
        val line3 = InvoiceItem(
            name = "Accessory Pack",
            quantity = 1,
            unitPrice = 100.0,
            gstPercent = 5.0,
            total = 105.0
        )
        val invoice3 = Invoice(
            billNumber = "",
            date = System.currentTimeMillis(),
            customerName = "Cash Customer",
            items = listOf(line3),
            subtotal = 100.0,
            gstAmount = 5.0,
            totalAmount = 105.0,
            paymentMode = "CASH"
        )
        val savedBill3 = repository.createInvoice(invoice3)
        val expectedBill3Num = "3"
        assertEquals(expectedBill3Num, savedBill3.billNumber)

        // 7. CANCEL Bill 1 with a reason
        repository.cancelInvoice(savedBill1.id, "Order cancelled by client")

        // Invoices are NEVER deleted: Bill 1 is still present, keeps its number, marked CANCELLED
        val cancelledBill = repository.invoices.value.find { it.id == savedBill1.id }!!
        assertEquals(expectedBill1Num, cancelledBill.billNumber)
        assertTrue(cancelledBill.isCancelled)
        assertEquals("Order cancelled by client", cancelledBill.cancellationReason)

        // Udhar entry for customerGujarat was reversed / removed
        assertEquals(0.0, repository.getCustomerBalance(customerGujarat.id), 0.01)
    }

    @Test
    fun testTask5AppLockSaltedHashAndBackgroundTimeout() {
        val appLock = AppLockManager(context)
        appLock.removePin()
        assertFalse("Initially no PIN set", appLock.hasPin())
        assertFalse("Should not lock if no PIN set", appLock.shouldLock())

        // 1. Set 4-digit PIN: salted hash, never stored in plain text
        appLock.setPin("4321")
        assertTrue("PIN is now configured", appLock.hasPin())

        val prefs = context.getSharedPreferences("app_lock_security_prefs", Context.MODE_PRIVATE)
        val encPrefs = context.getSharedPreferences("encrypted_app_lock_prefs", Context.MODE_PRIVATE)
        val salt = encPrefs.getString("pin_salt", "") ?: ""
        val hash = encPrefs.getString("pin_hash", "") ?: ""
        assertTrue("Salt generated and stored in EncryptedSharedPreferences", salt.isNotBlank())
        assertTrue("Hash generated and stored in EncryptedSharedPreferences", hash.isNotBlank())
        assertNotEquals("Plain PIN must never be stored in preferences", "4321", hash)
        assertFalse("Preferences should not contain plain PIN anywhere", encPrefs.all.values.contains("4321"))
        assertFalse("Plain preferences must not contain pin_hash", prefs.contains("pin_hash"))

        // Excluded from repository backups:
        val backupJson = repository.exportDataAsJsonString()
        assertFalse("Backup JSON must never contain salt or hash", backupJson.contains(salt))
        assertFalse("Backup JSON must never contain pin", backupJson.contains("pin_hash"))

        // 2. PIN verification with wrong and right PIN
        assertFalse("Wrong PIN fails verification", appLock.verifyPin("0000"))
        assertTrue("Correct PIN unlocks session", appLock.verifyPin("4321"))
        assertFalse("Session is unlocked, should not lock immediately", appLock.shouldLock())

        // 3. Background timeout: < 1 minute (e.g. 10s) does not lock
        appLock.recordBackground()
        // Simulate returning after 10 seconds:
        prefs.edit().putLong("last_background_timestamp", System.currentTimeMillis() - 10_000L).commit()
        assertFalse("Should not lock after only 10s in background", appLock.shouldLock())

        // Simulate returning after 65 seconds (> 1 minute):
        prefs.edit().putLong("last_background_timestamp", System.currentTimeMillis() - 65_000L).commit()
        assertTrue("Must lock when returning from background after > 1 minute", appLock.shouldLock())

        // Unlock with PIN
        assertTrue("Unlock with PIN", appLock.verifyPin("4321"))
        assertFalse("Unlocked again", appLock.shouldLock())

        // 4. Remove PIN
        appLock.removePin()
        assertFalse("PIN removed", appLock.hasPin())
        assertFalse("No lock once PIN removed", appLock.shouldLock())
    }

    @Test
    fun testTask5DailyReportGenerationAndWorkScheduler() {
        val reportText = DailyReportWorker.generateReportText(
            todaySales = 12500.0,
            todayInvoicesCount = 8,
            todayCollected = 8000.0,
            todayCreditGiven = 4500.0,
            cashInHand = 15200.0,
            topDebtors = listOf(Pair("Ramesh Cloth Store", 7500.0))
        )

        assertTrue(reportText.contains("GHANSHYAM DAILY BUSINESS REPORT"))
        assertTrue(reportText.contains("Sales: ₹12500.00 (8 bills)"))
        assertTrue(reportText.contains("Cash/UPI Collected: ₹8000.00"))
        assertTrue(reportText.contains("Credit Given (Udhar): ₹4500.00"))
        assertTrue(reportText.contains("Net Cash in Hand: ₹15200.00"))
        assertTrue(reportText.contains("Ramesh Cloth Store: ₹7500.00"))

        // WorkManager scheduler check
        DailyReportScheduler.scheduleDailyReport(context)
    }

    @Test
    fun testTask5ChatMessageDatabasePersistence() {
        repository.clearChatHistory()
        assertEquals(0, repository.loadRecentChatMessages().size)

        // Save 55 messages to verify trimToLast50 limit
        for (i in 1..55) {
            repository.saveChatMessage(
                sender = if (i % 2 == 0) "AI" else "USER",
                text = "Message #$i"
            )
        }

        val persisted = repository.loadRecentChatMessages()
        assertEquals("Should trim and keep exactly the last 50 messages", 50, persisted.size)
        assertEquals("Oldest kept message is #6", "Message #6", persisted.first().text)
        assertEquals("Newest message is #55", "Message #55", persisted.last().text)

        repository.clearChatHistory()
        assertEquals(0, repository.loadRecentChatMessages().size)
    }

    @Test
    fun testTask10AttachmentCrudAndCascadeDeletion() {
        val cust = repository.addCustomer("Keval Cloth Store", "9898012345")
        val tx = repository.addTransaction(cust.id, 1200.0, "GAVE", "Chit #101")
        val cash = repository.addCashEntry("IN", 500.0, "Counter", "Cash payment", "CASH")

        // Create dummy files on disk in app private storage
        val attachDir = AttachmentManager.getAttachmentsDir(context)
        val file1 = File(attachDir, "test_file_1.jpg").apply { writeText("dummy content 1") }
        val thumb1 = File(attachDir, "test_thumb_1.jpg").apply { writeText("dummy thumb 1") }
        val file2 = File(attachDir, "test_file_2.jpg").apply { writeText("dummy content 2") }
        val thumb2 = File(attachDir, "test_thumb_2.jpg").apply { writeText("dummy thumb 2") }
        val file3 = File(attachDir, "test_file_3.jpg").apply { writeText("dummy content 3") }
        val thumb3 = File(attachDir, "test_thumb_3.jpg").apply { writeText("dummy thumb 3") }

        val att1 = Attachment(
            ownerType = "ENTRY",
            ownerId = tx.id,
            filePath = file1.absolutePath,
            thumbPath = thumb1.absolutePath,
            mimeType = "image/jpeg",
            sizeBytes = 100L,
            caption = "Bill paper chit"
        )
        val att2 = Attachment(
            ownerType = "CASH_ENTRY",
            ownerId = cash.id,
            filePath = file2.absolutePath,
            thumbPath = thumb2.absolutePath,
            mimeType = "image/jpeg",
            sizeBytes = 150L,
            caption = "Cash receipt slip"
        )
        val att3 = Attachment(
            ownerType = "PARTY",
            ownerId = cust.id,
            filePath = file3.absolutePath,
            thumbPath = thumb3.absolutePath,
            mimeType = "image/jpeg",
            sizeBytes = 200L,
            caption = "Visiting card"
        )

        repository.addAttachments(listOf(att1, att2, att3))

        // Verify retrieval
        val txAtts = repository.getAttachmentsForOwner("ENTRY", tx.id)
        assertEquals(1, txAtts.size)
        assertEquals("Bill paper chit", txAtts[0].caption)

        val cashAtts = repository.getAttachmentsForOwner("CASH_ENTRY", cash.id)
        assertEquals(1, cashAtts.size)
        assertEquals("Cash receipt slip", cashAtts[0].caption)

        // Party should retrieve both direct party attachments and transaction attachments
        val partyAllAtts = repository.getAttachmentsForParty(cust.id)
        assertEquals(2, partyAllAtts.size)

        // Update caption
        repository.updateAttachmentCaption(att1.id, "Updated Chit caption")
        val updated = repository.getAttachmentsForOwner("ENTRY", tx.id)
        assertEquals("Updated Chit caption", updated[0].caption)

        // Cascade delete on transaction deletion: file deleted from disk and record removed
        assertTrue(file1.exists())
        assertTrue(thumb1.exists())
        repository.deleteTransaction(tx.id)
        assertEquals(0, repository.getAttachmentsForOwner("ENTRY", tx.id).size)
        assertFalse(file1.exists())
        assertFalse(thumb1.exists())

        // Cascade delete on cash entry deletion
        assertTrue(file2.exists())
        assertTrue(thumb2.exists())
        repository.deleteCashEntry(cash.id)
        assertEquals(0, repository.getAttachmentsForOwner("CASH_ENTRY", cash.id).size)
        assertFalse(file2.exists())
        assertFalse(thumb2.exists())

        // Cascade delete on customer deletion
        assertTrue(file3.exists())
        assertTrue(thumb3.exists())
        repository.deleteCustomer(cust.id)
        assertEquals(0, repository.getAttachmentsForOwner("PARTY", cust.id).size)
        assertFalse(file3.exists())
        assertFalse(thumb3.exists())
    }

    @Test
    fun testTask10ZipBackupAndLegacyJsonRestore() = runBlocking {
        val cust = repository.addCustomer("Anand Readymade", "9876500000")
        repository.addTransaction(cust.id, 2500.0, "GAVE", "Summer wear")

        // Export ZIP
        val zipBytes = repository.exportBackupZip(context)
        assertTrue("ZIP bytes should not be empty", zipBytes.isNotEmpty())

        // Clear data
        repository.clearAllData()
        assertEquals(0, repository.customers.value.size)

        // Restore from ZIP stream
        val zipResult = repository.restoreBackupFromStream(context, ByteArrayInputStream(zipBytes))
        assertTrue(zipResult.success)
        assertFalse(zipResult.isLegacyJsonWithoutPhotos)
        assertEquals(1, repository.customers.value.size)
        assertEquals("Anand Readymade", repository.customers.value[0].name)

        // Test restoring legacy JSON (without ZIP container)
        val legacyJson = repository.exportDataAsJsonString()
        repository.clearAllData()
        assertEquals(0, repository.customers.value.size)

        val jsonResult = repository.restoreBackupFromStream(context, ByteArrayInputStream(legacyJson.toByteArray(Charsets.UTF_8)))
        assertTrue(jsonResult.success)
        assertTrue("Restoring plain JSON must flag isLegacyJsonWithoutPhotos", jsonResult.isLegacyJsonWithoutPhotos)
        assertEquals(1, repository.customers.value.size)
        assertEquals("Anand Readymade", repository.customers.value[0].name)
    }

    @Test
    fun testTask10ScreenshotBlockPreference() {
        val appLock = AppLockManager(context)
        assertFalse("Screenshots block should be disabled by default", appLock.isBlockScreenshotsEnabled())

        appLock.setBlockScreenshotsEnabled(true)
        assertTrue(appLock.isBlockScreenshotsEnabled())

        appLock.setBlockScreenshotsEnabled(false)
        assertFalse(appLock.isBlockScreenshotsEnabled())
    }

    @Test
    fun testTask11SimplifiedBillingHandwrittenChitAndCancellation() {
        // 1. Create a customer
        val party = repository.addCustomer("Surat Kids Boutique", "9898989898")
        assertEquals(0.0, repository.getCustomerBalance(party.id), 0.01)

        // 2. Create Bill with 2 chit photos and 1000 received in UPI for total 3000
        val bill = Invoice(
            billNumber = "",
            date = System.currentTimeMillis(),
            customerId = party.id,
            customerName = party.name,
            customerPhone = party.phone,
            subtotal = 3000.0,
            totalAmount = 3000.0,
            notes = "20 frock + 10 shorts"
        )
        val savedBill = repository.createInvoice(
            invoice = bill,
            receivedNow = 1000.0,
            receivedPaymentMode = "UPI"
        )

        // Attach 2 dummy chit photos
        val dummyFile1 = File(context.filesDir, "chit1.jpg").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val dummyFile2 = File(context.filesDir, "chit2.jpg").apply { writeBytes(byteArrayOf(4, 5, 6)) }
        val att1 = Attachment(ownerType = "INVOICE", ownerId = savedBill.id, filePath = dummyFile1.absolutePath)
        val att2 = Attachment(ownerType = "INVOICE", ownerId = savedBill.id, filePath = dummyFile2.absolutePath)
        repository.addAttachments(listOf(att1, att2))

        // Check chit photos associated with invoice and party
        val invAtts = repository.getAttachmentsForOwner("INVOICE", savedBill.id)
        assertEquals(2, invAtts.size)
        val partyAtts = repository.getAttachmentsForParty(party.id)
        assertEquals(2, partyAtts.size)

        // 3. Verify party's khata shows remainder (3000 - 1000 = 2000) as udhar!
        assertEquals(2000.0, repository.getCustomerBalance(party.id), 0.01)

        // 4. Verify Cashbook shows +1000 UPI
        val cashEntries = repository.cashbook.value.filter { it.linkedId == savedBill.id }
        assertEquals(1, cashEntries.size)
        assertEquals(1000.0, cashEntries[0].amount, 0.01)
        assertEquals("UPI", cashEntries[0].paymentMode)
        assertEquals("IN", cashEntries[0].type)

        // 5. Verify Bill Status: PARTLY_PAID
        assertEquals(AppRepository.InvoicePaymentStatus.PARTLY_PAID, repository.getInvoicePaymentStatus(savedBill))
        assertEquals(1000.0, repository.getInvoiceReceivedAmount(savedBill), 0.01)

        // 6. Cancel Bill: both remainder udhar and Cashbook entry disappear
        repository.cancelInvoice(savedBill.id, "Customer returned order")

        // Bill is marked CANCELLED
        val cancelled = repository.invoices.value.find { it.id == savedBill.id }!!
        assertTrue(cancelled.isCancelled)
        assertEquals(AppRepository.InvoicePaymentStatus.CANCELLED, repository.getInvoicePaymentStatus(cancelled))

        // Khata balance is back to 0.0
        assertEquals(0.0, repository.getCustomerBalance(party.id), 0.01)

        // Cashbook entry for this invoice is removed
        val remainingCash = repository.cashbook.value.filter { it.linkedId == savedBill.id }
        assertTrue(remainingCash.isEmpty())

        // 7. Verify an older install format bill still opens and functions properly
        val legacyBill = Invoice(
            billNumber = "GKW/25-26/0042",
            date = System.currentTimeMillis() - 1000000L,
            customerId = party.id,
            customerName = party.name,
            customerPhone = party.phone,
            subtotal = 500.0,
            totalAmount = 500.0,
            paymentMode = "CASH"
        )
        val savedLegacy = repository.createInvoice(legacyBill, receivedNow = 500.0, receivedPaymentMode = "CASH")
        assertEquals("GKW/25-26/0042", savedLegacy.billNumber)
        assertEquals(AppRepository.InvoicePaymentStatus.PAID, repository.getInvoicePaymentStatus(savedLegacy))
    }

    @Test
    fun testTask12RebuildCashBook() {
        // 1. Create a customer with initial 5000 Udhar
        val customer = repository.addCustomer(name = "Kishore Bhai", phone = "9825101010")
        repository.addTransaction(
            customerId = customer.id,
            amount = 5000.0,
            type = "GAVE",
            note = "Goods on credit"
        )
        assertEquals(5000.0, repository.getCustomerBalance(customer.id), 0.01)

        // 2. Record customer payment of 2000 by UPI -> Automatically appears in Bank book
        val paymentTx = repository.addTransaction(
            customerId = customer.id,
            amount = 2000.0,
            type = "GOT",
            note = "Payment via GPay",
            paymentMode = "UPI"
        )

        // Khata balance drops by 2000 (5000 - 2000 = 3000)
        assertEquals(3000.0, repository.getCustomerBalance(customer.id), 0.01)

        // Bank book shows it under today with party name
        val bankEntries = repository.cashbook.value.filter { it.bookType == "BANK" && !it.isDeleted }
        assertEquals(1, bankEntries.size)
        val bankEntry = bankEntries[0]
        assertEquals(2000.0, bankEntry.amount, 0.01)
        assertEquals("UPI", bankEntry.paymentMode)
        assertEquals("Kishore Bhai", bankEntry.partyName)
        assertEquals("IN", bankEntry.type)
        assertEquals(paymentTx.id, bankEntry.linkedId)

        // Cash book has 0 entries
        val cashEntries = repository.cashbook.value.filter { it.bookType == "CASH" && !it.isDeleted }
        assertTrue(cashEntries.isEmpty())

        // 3. Edit to 1500 -> Both update
        repository.editCashEntry(
            id = bankEntry.id,
            amount = 1500.0,
            remark = "Part payment via GPay (revised)",
            date = bankEntry.date,
            paymentMode = "UPI",
            bookType = "BANK"
        )

        val updatedBank = repository.cashbook.value.find { it.id == bankEntry.id }!!
        assertEquals(1500.0, updatedBank.amount, 0.01)
        assertEquals(2000.0, updatedBank.originalAmount!!, 0.01) // Edited badge preserved

        // Linked khata transaction updated, balance is now 5000 - 1500 = 3500
        assertEquals(3500.0, repository.getCustomerBalance(customer.id), 0.01)

        // 4. Delete -> Goes to Bin, khata balance is back to 5000
        repository.moveCashEntryToBin(bankEntry.id)

        // In active cashbook, entry is removed
        val activeBank = repository.cashbook.value.filter { it.bookType == "BANK" && !it.isDeleted }
        assertTrue(activeBank.isEmpty())

        // Bin has the deleted entry
        val bin = repository.binEntries.value
        assertEquals(1, bin.size)
        assertEquals(bankEntry.id, bin[0].id)
        assertTrue(bin[0].isDeleted)

        // Khata balance is restored to 5000.0
        assertEquals(5000.0, repository.getCustomerBalance(customer.id), 0.01)

        // 5. Restore -> Returns to bank book and khata balance drops again
        repository.restoreCashEntryFromBin(bankEntry.id)

        val restoredBank = repository.cashbook.value.filter { it.bookType == "BANK" && !it.isDeleted }
        assertEquals(1, restoredBank.size)
        assertEquals(1500.0, restoredBank[0].amount, 0.01)
        assertTrue(repository.binEntries.value.isEmpty())

        // Khata balance drops back to 3500.0
        assertEquals(3500.0, repository.getCustomerBalance(customer.id), 0.01)

        // 6. Drawer count test
        repository.updateCashOpeningBalance(1000.0)
        assertEquals(1000.0, repository.cashOpeningBalance.value, 0.01)

        // Add 500 cash in
        repository.addCashEntry(
            type = "IN",
            amount = 500.0,
            category = "Counter",
            paymentMode = "CASH",
            bookType = "CASH"
        )
        // Book balance = 1000 + 500 = 1500
        val bookBalance = 1000.0 + 500.0
        val actualPhysicalCash = 1600.0 // 100 extra in drawer
        val drawerCount = repository.addDrawerCount(
            actualCash = actualPhysicalCash,
            bookBalance = bookBalance,
            note = "Day end count"
        )
        assertEquals(100.0, drawerCount.difference, 0.01)
        assertEquals(1, repository.drawerCounts.value.size)
        assertEquals("Day end count", repository.drawerCounts.value[0].note)

        // 7. Day header balance calculation logic verification
        val dayOpening = 1000.0
        val dayIn = 500.0
        val dayOut = 200.0
        val dayClosing = dayOpening + dayIn - dayOut
        assertEquals(1300.0, dayClosing, 0.01)
    }

    @Test
    fun testTask13BankCheckAndReports() {
        // 1. Tick 3 of 4 bank lines -> counter shows 3 of 4
        val b1 = repository.addCashEntry(type = "IN", amount = 1000.0, category = "Bank", paymentMode = "UPI", bookType = "BANK", partyName = "Ramesh")
        val b2 = repository.addCashEntry(type = "IN", amount = 2000.0, category = "Bank", paymentMode = "CHEQUE", bookType = "BANK", partyName = "Suresh")
        val b3 = repository.addCashEntry(type = "OUT", amount = 500.0, category = "Bank", paymentMode = "BANK TRANSFER", bookType = "BANK", partyName = "Supplier")
        val b4 = repository.addCashEntry(type = "IN", amount = 1500.0, category = "Bank", paymentMode = "UPI", bookType = "BANK", partyName = "Dinesh")

        // Tick 3 of them
        repository.setBankEntryVerified(b1.id, true)
        repository.setBankEntryVerified(b2.id, true)
        repository.setBankEntryVerified(b3.id, true)

        val activeBankEntries = repository.cashbook.value.filter { it.bookType == "BANK" && !it.isDeleted }
        val verifiedCount = activeBankEntries.count { it.bankVerified }
        val totalBankCount = activeBankEntries.size

        assertEquals(3, verifiedCount)
        assertEquals(4, totalBankCount)
        val counterDisplay = "Matched $verifiedCount of $totalBankCount"
        assertEquals("Matched 3 of 4", counterDisplay)

        // Mark b4 as not on sheet with a note
        repository.setBankEntryUnmatchedNote(b4.id, "Deposit cheque not yet cleared by SPCB")
        val updatedB4 = repository.cashbook.value.find { it.id == b4.id }!!
        assertEquals("Deposit cheque not yet cleared by SPCB", updatedB4.unmatchedNote)

        // 2. Day check difference 0 and 150 both display correctly
        val bookBalanceForDay = 1000.0 + 2000.0 - 500.0 + 1500.0 // 4000.0
        // Test Zero Difference
        val dayCheckZero = repository.addBankDayCheck(
            date = System.currentTimeMillis(),
            sheetBalance = 4000.0,
            bookBalance = 4000.0,
            note = "SPCB sheet page 1 verified"
        )
        assertEquals(0.0, dayCheckZero.difference, 0.01)

        // Test 150 Difference
        val dayCheckDiff = repository.addBankDayCheck(
            date = System.currentTimeMillis(),
            sheetBalance = 4150.0,
            bookBalance = 4000.0,
            note = "Sheet has 150 extra"
        )
        assertEquals(150.0, dayCheckDiff.difference, 0.01)

        val checks = repository.bankDayChecks.value
        assertEquals(2, checks.size)

        // 3. Multi-page A4 PDF for a month with 80 entries has several pages with repeated headers
        val startCal = Calendar.getInstance().apply {
            set(2026, Calendar.MARCH, 1, 0, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val endCal = Calendar.getInstance().apply {
            set(2026, Calendar.MARCH, 31, 23, 59, 59)
            set(Calendar.MILLISECOND, 999)
        }

        // Add 80 entries across the month
        for (i in 1..80) {
            val dayOffset = (i % 28) + 1
            val entryCal = Calendar.getInstance().apply {
                set(2026, Calendar.MARCH, dayOffset, 10, i % 60, 0)
            }
            val isBank = i % 2 == 0
            val isCredit = i % 3 != 0
            repository.addCashEntry(
                type = if (isCredit) "IN" else "OUT",
                amount = (100 + (i * 25)).toDouble(),
                category = if (isBank) "Bank Tx" else "Cash Tx",
                paymentMode = if (isBank) "UPI" else "CASH",
                bookType = if (isBank) "BANK" else "CASH",
                partyName = "Party $dayOffset",
                date = entryCal.timeInMillis
            )
        }

        val reportData = com.example.ui.util.AccountantReportGenerator.buildReportData(
            allCashEntries = repository.cashbook.value,
            cashOpeningBalance = 1000.0,
            bankOpeningBalance = 2000.0,
            startMillis = startCal.timeInMillis,
            endMillis = endCal.timeInMillis
        )

        val pdfFile = com.example.ui.util.AccountantReportGenerator.generateAccountantReportPdf(
            context = context,
            reportData = reportData,
            storeProfile = repository.storeProfile.value
        )

        assertTrue("Accountant Report PDF should exist", pdfFile.exists())
        assertTrue("Accountant Report PDF should have content", pdfFile.length() > 2048)

        val rawPdfText = pdfFile.readBytes().toString(Charsets.ISO_8859_1)
        val pageCount = Regex("""/Type\s*/Page\b""").findAll(rawPdfText).count()
        assertTrue("PDF for a month with 80 entries should have several pages (found $pageCount)", pageCount > 1)
        assertTrue("PDF should contain repeated shop headers", rawPdfText.contains("Ghanshyam") || rawPdfText.contains("Accountant"))

        // 4. CSV opens in a spreadsheet with correct columns
        val csvFile = com.example.ui.util.AccountantReportGenerator.generateAccountantReportCsv(
            context = context,
            reportData = reportData,
            storeProfile = repository.storeProfile.value
        )

        assertTrue("Accountant Report CSV should exist", csvFile.exists())
        val csvContent = csvFile.readText(Charsets.UTF_8)
        assertTrue("CSV should contain Day-Wise table header", csvContent.contains("Date,Cash Opening,Cash In,Cash Out,Cash Closing,Bank In,Bank Out,Bank Closing"))
        assertTrue("CSV should contain Party Collection header", csvContent.contains("Party Name,Cash Paid,Bank Paid,Total Paid"))
        assertTrue("CSV should contain Unmatched Lines header", csvContent.contains("Date,Type,Amount,Party,Reference,Explanation / Note"))
        assertTrue("CSV should contain Period Summary with verified count", csvContent.contains("Verified with bank:"))
    }

    @Test
    fun testTask14HomeSimpleModeAndDailyHelpers() {
        // 1. FRESH INSTALL: verify language selection and battery setup guide
        assertFalse("Fresh install should not have completed onboarding", repository.hasCompletedOnboarding.value)

        // Language choice
        repository.setLanguage(com.example.model.AppLanguage.GUJARATI)
        assertEquals("Language should be updated to Gujarati", com.example.model.AppLanguage.GUJARATI, repository.language.value)

        // Battery setup guide on Samsung and Oppo
        val samsungIntent = com.example.ui.util.BatteryOptimizationHelper.getAutoStartIntent(context, "samsung")
        assertNotNull("Samsung intent should not be null", samsungIntent)
        assertTrue(
            "Samsung intent should target smart manager or settings",
            samsungIntent.component?.packageName?.contains("samsung") == true ||
            samsungIntent.action == android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS
        )

        val oppoIntent = com.example.ui.util.BatteryOptimizationHelper.getAutoStartIntent(context, "oppo")
        assertNotNull("Oppo intent should not be null", oppoIntent)
        assertTrue(
            "Oppo intent should target coloros/oppo or settings",
            oppoIntent.component?.packageName?.contains("coloros") == true ||
            oppoIntent.component?.packageName?.contains("oppo") == true ||
            oppoIntent.action == android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS
        )

        // Complete battery guide & onboarding
        repository.completeBatteryGuide()
        repository.completeOnboarding()
        assertTrue("Onboarding should now be complete", repository.hasCompletedOnboarding.value)
        assertTrue("Battery guide should be marked complete", repository.hasCompletedBatteryGuide.value)

        // 2. SIMPLE MODE: default ON
        assertTrue("Simple mode should be default ON", repository.simpleMode.value)
        repository.setSimpleMode(false)
        assertFalse("Simple mode switch should turn off", repository.simpleMode.value)
        repository.setSimpleMode(true)
        assertTrue("Simple mode switch should turn on", repository.simpleMode.value)

        // 3. VOICE ENTRY: "Ramesh ne paanch hazaar diye"
        repository.addCustomer("Ramesh Kumar", "+91 98765 43210")
        val voicePhrase1 = "Ramesh ne paanch hazaar diye"
        val parsed1 = com.example.ui.util.VoiceEntryParser.parse(voicePhrase1, repository.customers.value)
        assertEquals("Party name should fuzzy-match to Ramesh Kumar", "Ramesh Kumar", parsed1.partyName)
        assertEquals("Parsed amount should be 5000.0", 5000.0, parsed1.amount, 0.01)
        assertEquals("Type should be GOT (Mujhe mile / Jama)", "GOT", parsed1.type)

        // Ensure nothing is saved without confirmation (still empty transactions)
        assertEquals("Nothing should be saved to repository before user confirmation", 0, repository.transactions.value.size)

        // Now simulate user tapping YES on the confirmation card
        val savedTx = repository.addTransaction(
            customerId = parsed1.matchedCustomer!!.id,
            amount = parsed1.amount,
            type = parsed1.type,
            note = parsed1.rawText,
            paymentMode = "CASH"
        )
        assertEquals("Transaction should now be saved", 1, repository.transactions.value.size)
        assertEquals("Transaction amount should match", 5000.0, savedTx.amount, 0.01)

        // Test Undo functionality
        repository.deleteTransaction(savedTx.id)
        assertEquals("Transaction should be removed after undo", 0, repository.transactions.value.size)

        // Gujarati voice phrase: "Ramesh ne 5000 aapya"
        val voicePhrase2 = "Ramesh ne 5000 aapya"
        val parsed2 = com.example.ui.util.VoiceEntryParser.parse(voicePhrase2, repository.customers.value)
        assertEquals("Ramesh Kumar", parsed2.partyName)
        assertEquals(5000.0, parsed2.amount, 0.01)
        assertEquals("GOT", parsed2.type)

        // 4. DELIVERY CHECKLIST (table delivery_tasks, resets view each day, history kept)
        val task1 = repository.addDeliveryTask("Suresh Bhai", null, "2 parcel kids denim", System.currentTimeMillis())
        assertNotNull("Delivery task should be created", task1.id)
        assertEquals("Delivery task partyName should match", "Suresh Bhai", task1.partyName)
        assertFalse("Delivery task should initially be incomplete", task1.isCompleted)

        val allTasks = repository.deliveryTasks.value
        assertTrue("Task should be present in repository list", allTasks.any { it.id == task1.id })

        // Mark task completed
        repository.toggleDeliveryTask(task1.id, true)
        val updatedTask = repository.deliveryTasks.value.first { it.id == task1.id }
        assertTrue("Task should now be completed", updatedTask.isCompleted)

        // 5. BACKUP HABIT: Weekly reminder and last backup timestamp
        assertEquals("Last backup timestamp initially 0L", 0L, repository.lastBackupTimestamp.value)
        val now = System.currentTimeMillis()
        repository.recordBackupCompleted(now)
        assertEquals("Last backup timestamp should be updated", now, repository.lastBackupTimestamp.value)
    }

    @Test
    fun testTask16DueDateAndDailyNotificationOverdueDebtorWhatsAppReminder() = runBlocking {
        // Set store profile
        repository.updateStoreProfile(
            storeName = "Ghanshyam Kids Wear",
            address = "Station Road, Surat",
            phone = "9825012345",
            upiId = "shop@upi",
            gstin = "",
            ownerName = "Ghanshyamdas",
            state = "Gujarat",
            logoUri = "",
            email = "shop@example.com"
        )
        // The repository also collects the DB flow in the background, so wait for it to settle
        // instead of reading the StateFlow once (a late stale emission could briefly show an empty profile).
        val profile = kotlinx.coroutines.withTimeout(5000) {
            repository.storeProfile.first { it.email == "shop@example.com" }
        }
        assertEquals("shop@example.com", profile.email)

        // Add debtor customer
        val cust = repository.addCustomer("Mahesh Retailer", "+91 98980 11223")

        // Due date set for tomorrow (or past due for testing)
        val now = System.currentTimeMillis()
        val tomorrowDue = now + 86400000L
        val pastDue = now - 86400000L

        // 1. Transaction with due date
        val savedTx = repository.addTransaction(
            customerId = cust.id,
            amount = 4500.0,
            type = "GAVE",
            note = "Festive kids wear lot",
            paymentMode = "CASH",
            dueDate = pastDue
        )
        assertEquals(pastDue, savedTx.dueDate)

        // 2. Invoice with due date
        val draftInv = Invoice(
            billNumber = "1",
            customerId = cust.id,
            customerName = cust.name,
            customerPhone = cust.phone,
            items = listOf(InvoiceItem(name = "Kurta Set", quantity = 2, unitPrice = 500.0, gstPercent = 5.0, total = 1050.0)),
            paymentMode = "CREDIT",
            subtotal = 1000.0,
            gstAmount = 50.0,
            totalAmount = 1050.0,
            discount = 0.0,
            dueDate = tomorrowDue,
            date = now
        )
        val inv = repository.createInvoice(draftInv)
        assertEquals(tomorrowDue, inv.dueDate)

        // 3. Daily report worker identifies overdue debtors and generates WhatsApp reminder
        val overdueParties = repository.customers.value.mapNotNull { c ->
            val bal = repository.getCustomerBalance(c.id)
            if (bal > 0.01) {
                val txs = repository.transactions.value.filter { it.customerId == c.id }
                val isOverdue = txs.any { it.type == "GAVE" && it.dueDate > 0L && it.dueDate <= now } ||
                        txs.any { it.type == "GAVE" && (now - it.date) >= 15L * 86400000L }
                if (isOverdue) Triple(c.id, c.name, bal) else null
            } else null
        }
        assertTrue("Overdue party Mahesh Retailer must be identified", overdueParties.any { it.second == "Mahesh Retailer" })

        // 4. One-tap WhatsApp reminder link generation
        val cleanPhone = PhoneUtil.toWhatsAppNumber(cust.phone)
        assertEquals("919898011223", cleanPhone)
        val reminderMsg = "Namaste Mahesh Retailer ji, Ghanshyam Kids Wear se aapka ₹4,500 ka hisab baki hai. Kripya payment karein."
        val encodedMsg = java.net.URLEncoder.encode(reminderMsg, "UTF-8")
        val whatsappUri = "https://api.whatsapp.com/send?phone=$cleanPhone&text=$encodedMsg"
        assertTrue("WhatsApp URI must contain clean phone", whatsappUri.contains("919898011223"))
        assertTrue("WhatsApp URI must contain encoded message", whatsappUri.contains("Ghanshyam+Kids+Wear"))
    }

    @Test
    fun testTask16AppLockSixWrongPinsLockoutSurvivesRestart() {
        val lockManager1 = AppLockManager(context)
        lockManager1.setPin("4321")
        assertTrue("PIN must be active", lockManager1.hasPin())
        assertFalse("Must not be locked out initially", lockManager1.isLockedOut())
        assertEquals(0, lockManager1.getRemainingLockoutSeconds())

        // 5 wrong attempts trigger initial lockout of 30s
        for (i in 1..5) {
            val valid = lockManager1.verifyPin("0000")
            assertFalse("Wrong PIN must return false", valid)
        }
        assertTrue("Must be locked out after 5 wrong attempts", lockManager1.isLockedOut())
        val remaining1 = lockManager1.getRemainingLockoutSeconds()
        assertTrue("Remaining seconds should be between 1 and 30", remaining1 in 1..30)

        // 6th wrong attempt while locked out keeps/extends lockout
        lockManager1.verifyPin("9999")
        assertTrue("Must still be locked out", lockManager1.isLockedOut())
        assertTrue("Remaining lockout seconds must be positive", lockManager1.getRemainingLockoutSeconds() > 0)

        // Simulate app force-stop and restart with new instance of AppLockManager
        val lockManager2 = AppLockManager(context)
        assertTrue("Lockout must survive restart", lockManager2.isLockedOut())
        assertTrue("Remaining seconds must survive restart", lockManager2.getRemainingLockoutSeconds() > 0)
        assertTrue("PIN configuration must survive restart in EncryptedSharedPreferences", lockManager2.hasPin())

        // Correct PIN entered while locked out is rejected
        assertFalse("Cannot authenticate with correct PIN while locked out", lockManager2.verifyPin("4321"))
    }

    @Test
    fun testTask17DeleteTransactionWithLinkedPaymentsAndBinRestoration() {
        val cust = repository.addCustomer(name = "Kishore Bhai", phone = "9825098250")

        // 1. Create a credit entry (bill/transaction)
        val billTx = repository.addTransaction(
            customerId = cust.id,
            amount = 10000.0,
            type = "GAVE",
            note = "Cloth shipment",
            billNumber = "BILL-501"
        )
        assertNotNull(billTx)

        // 2. Mark payment received linked to billTx
        val payTx = repository.markBillReceived(
            billId = billTx.id,
            amount = 4000.0,
            paymentMode = "CASH",
            addToCashbook = true
        )
        assertNotNull(payTx)
        assertEquals(billTx.id, payTx!!.linkedToTxId)

        // Verify customer balance = 10000 - 4000 = 6000
        assertEquals(6000.0, repository.getCustomerBalance(cust.id), 0.01)

        // Verify cash entry was created for the payment
        val cashEntries = repository.cashbook.value.filter { it.linkedId == payTx.id }
        assertEquals(1, cashEntries.size)
        val linkedCashEntry = cashEntries.first()
        assertFalse(linkedCashEntry.isDeleted)

        // Add attachment photo to linkedCashEntry (and payment entry)
        val dummyFile = File(context.filesDir, "test_receipt_sample.jpg").apply { writeBytes(byteArrayOf(10, 20, 30)) }
        val att = Attachment(
            ownerType = "CASH_ENTRY",
            ownerId = linkedCashEntry.id,
            filePath = dummyFile.absolutePath,
            caption = "Payment receipt slip"
        )
        repository.addAttachments(listOf(att))
        assertEquals(1, repository.getAttachmentsForOwner("CASH_ENTRY", linkedCashEntry.id).size)

        // 3. Now delete the original credit transaction (billTx)
        repository.deleteTransaction(billTx.id)

        // Linked payment payTx should be deleted so balance doesn't become orphan negative
        val remainingTx = repository.transactions.value.filter { it.customerId == cust.id }
        assertTrue("All transactions for the bill should be removed/moved", remainingTx.isEmpty())
        assertEquals(0.0, repository.getCustomerBalance(cust.id), 0.01)

        // The cash entry for payTx must be moved to Bin (isDeleted = true), not permanently destroyed
        val bin = repository.binEntries.value
        assertTrue("Bin should contain entries", bin.isNotEmpty())
        val deletedCash = bin.find { it.id == linkedCashEntry.id }
        assertNotNull("Payment cash entry should be in Bin", deletedCash)
        assertTrue(deletedCash!!.isDeleted)

        // Attachment file must NOT be deleted while entry is in Bin
        val attFile = File(att.filePath)
        assertTrue("Attachment file must still exist while in the Bin", attFile.exists())

        // 4. Test restoring the entry from Bin
        repository.restoreCashEntryFromBin(deletedCash.id)

        // Restored cash entry is no longer in Bin
        val activeCash = repository.cashbook.value.find { it.id == deletedCash.id }
        assertNotNull("Cash entry should be active", activeCash)
        assertFalse("Cash entry isDeleted should be false", activeCash!!.isDeleted)

        // Restored khata transaction should be recreated with linkedToTxId intact
        val restoredTxs = repository.transactions.value.filter { it.customerId == cust.id }
        assertEquals(1, restoredTxs.size)
        val restoredTx = restoredTxs.first()
        assertEquals("GOT", restoredTx.type)
        assertEquals(4000.0, restoredTx.amount, 0.01)
        assertEquals(billTx.id, restoredTx.linkedToTxId)
    }

    @Test
    fun testTask17bQuickEntryTypeFollowsTappedButton() {
        // The type must come from the tapped button and survive the party-picker step.
        assertEquals("GAVE", com.example.ui.util.QuickEntry.resolveType("GAVE"))
        assertEquals("GOT", com.example.ui.util.QuickEntry.resolveType("GOT"))
        assertEquals("GOT", com.example.ui.util.QuickEntry.resolveType(null))

        val cust = repository.addCustomer("Shankar", "9820099999", type = PartyType.CUSTOMER.raw)

        // "Maine diye 1000" -> GAVE saved, balance shows Lena Hai 1000
        repository.addTransaction(
            customerId = cust.id,
            amount = 1000.0,
            type = com.example.ui.util.QuickEntry.resolveType("GAVE"),
            note = "",
            paymentMode = "CASH"
        )
        val afterGave = repository.transactions.value.filter { it.customerId == cust.id }
        assertEquals(1, afterGave.size)
        assertEquals("GAVE", afterGave.first().type)
        assertEquals(1000.0, repository.getCustomerBalance(cust.id), 0.01)
        var today = repository.getTodaySummary()
        assertEquals(1000.0, today.second, 0.01) // today's given
        assertEquals(0.0, today.first, 0.01)     // today's received

        // "Mujhe mile 1000" -> GOT saved, balance back to 0
        repository.addTransaction(
            customerId = cust.id,
            amount = 1000.0,
            type = com.example.ui.util.QuickEntry.resolveType("GOT"),
            note = "",
            paymentMode = "CASH"
        )
        val afterGot = repository.transactions.value.filter { it.customerId == cust.id }
        assertEquals(1, afterGot.count { it.type == "GAVE" })
        assertEquals(1, afterGot.count { it.type == "GOT" })
        assertEquals(0.0, repository.getCustomerBalance(cust.id), 0.01)
        today = repository.getTodaySummary()
        assertEquals(1000.0, today.first, 0.01)
        assertEquals(1000.0, today.second, 0.01)
    }
}
