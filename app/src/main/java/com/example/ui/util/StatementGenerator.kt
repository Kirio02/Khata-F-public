package com.example.ui.util

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import com.example.model.AppLanguage
import com.example.model.Customer
import com.example.model.StoreProfile
import com.example.model.Transaction
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

enum class StatementDateRange(val label: String) {
    ALL("All Time"),
    THIS_MONTH("This Month"),
    THREE_MONTHS("Last 3 Months")
}

enum class StatementFilter(val label: String) {
    ALL_ENTRIES("Full Statement"),
    PENDING_BILLS_ONLY("Pending Bills Only")
}

data class StatementEntry(
    val transaction: Transaction,
    val debit: Double, // You gave (bill for customer / payment to supplier)
    val credit: Double, // You got (payment from customer / bill from supplier)
    val runningBalance: Double // > 0 = you will get, < 0 = you will give
)

data class StatementResult(
    val customer: Customer,
    val openingBalance: Double,
    val entries: List<StatementEntry>,
    val totalDebit: Double,
    val totalCredit: Double,
    val closingBalance: Double,
    val dateRange: StatementDateRange,
    val filter: StatementFilter
)

object StatementGenerator {

    /**
     * Pure function to calculate statement with opening and running balances.
     * Shared across UI screens, WhatsApp text export, PDF generator, and AI engine.
     */
    fun computeStatement(
        customer: Customer,
        allTransactions: List<Transaction>,
        dateRange: StatementDateRange = StatementDateRange.ALL,
        filter: StatementFilter = StatementFilter.ALL_ENTRIES,
        now: Long = System.currentTimeMillis()
    ): StatementResult {
        val custTxList = allTransactions
            .filter { it.customerId == customer.id }
            .sortedBy { it.date }

        val cutoff = when (dateRange) {
            StatementDateRange.ALL -> 0L
            StatementDateRange.THIS_MONTH -> {
                val cal = Calendar.getInstance().apply {
                    timeInMillis = now
                    set(Calendar.DAY_OF_MONTH, 1)
                    set(Calendar.HOUR_OF_DAY, 0)
                    set(Calendar.MINUTE, 0)
                    set(Calendar.SECOND, 0)
                    set(Calendar.MILLISECOND, 0)
                }
                cal.timeInMillis
            }
            StatementDateRange.THREE_MONTHS -> now - (90L * 24 * 3600 * 1000)
        }

        // 1. Calculate opening balance from all transactions before the cutoff date
        var openingBal = 0.0
        val inRangeTxs = mutableListOf<Transaction>()
        for (tx in custTxList) {
            if (tx.date < cutoff) {
                // Customer: GAVE increases what they owe, GOT decreases
                // Supplier: GAVE (payment) increases balance toward 0, GOT (credit bill) decreases
                if (tx.type == "GAVE") {
                    openingBal += tx.amount
                } else {
                    openingBal -= tx.amount
                }
            } else {
                inRangeTxs.add(tx)
            }
        }

        // 2. Filter if PENDING_BILLS_ONLY
        val targetTxs: List<Transaction>
        if (filter == StatementFilter.PENDING_BILLS_ONLY) {
            // Find bills that still have outstanding balance
            val billType = if (customer.type == "SUPPLIER") "GOT" else "GAVE"
            val bills = custTxList.filter { it.type == billType }
            val pendingBillIds = mutableSetOf<String>()

            for (bill in bills) {
                val received = custTxList
                    .filter { it.linkedToTxId == bill.id }
                    .sumOf { it.amount }
                if (received < bill.amount - 0.01) {
                    pendingBillIds.add(bill.id)
                }
            }

            // Include pending bills and any partial payments linked to them
            targetTxs = inRangeTxs.filter {
                pendingBillIds.contains(it.id) || (it.linkedToTxId != null && pendingBillIds.contains(it.linkedToTxId))
            }
        } else {
            targetTxs = inRangeTxs
        }

        // 3. Compute running balances
        var currentBal = openingBal
        var totalDebit = 0.0
        var totalCredit = 0.0
        val entries = mutableListOf<StatementEntry>()

        for (tx in targetTxs) {
            val debit: Double
            val credit: Double
            if (tx.type == "GAVE") {
                debit = tx.amount
                credit = 0.0
                currentBal += tx.amount
                totalDebit += tx.amount
            } else {
                debit = 0.0
                credit = tx.amount
                currentBal -= tx.amount
                totalCredit += tx.amount
            }
            entries.add(
                StatementEntry(
                    transaction = tx,
                    debit = debit,
                    credit = credit,
                    runningBalance = currentBal
                )
            )
        }

        return StatementResult(
            customer = customer,
            openingBalance = openingBal,
            entries = entries,
            totalDebit = totalDebit,
            totalCredit = totalCredit,
            closingBalance = currentBal,
            dateRange = dateRange,
            filter = filter
        )
    }

    /**
     * Formats statement into human-readable text for WhatsApp or SMS.
     */
    fun generateStatementText(
        statement: StatementResult,
        storeProfile: StoreProfile,
        language: AppLanguage = AppLanguage.HINGLISH
    ): String {
        val dateFormat = SimpleDateFormat("dd/MM/yyyy", Locale.getDefault())
        val shopName = storeProfile.storeName.ifBlank { "Ghanshyam Kids Wear" }
        val partyName = statement.customer.name
        val sb = StringBuilder()

        sb.append("📋 *KHATA STATEMENT* 📋\n")
        sb.append("*$shopName*\n")
        if (storeProfile.phone.isNotBlank()) sb.append("Phone: ${storeProfile.phone}\n")
        sb.append("----------------------------\n")
        sb.append("Party: *$partyName* (${statement.customer.type})\n")
        sb.append("Period: ${statement.dateRange.label} (${statement.filter.label})\n")
        sb.append("Date: ${dateFormat.format(Date())}\n")
        sb.append("----------------------------\n")

        sb.append("Opening Balance: ₹${"%.2f".format(statement.openingBalance)}\n\n")

        if (statement.entries.isEmpty()) {
            sb.append("No transactions in this period.\n")
        } else {
            statement.entries.forEach { entry ->
                val tx = entry.transaction
                val dateStr = dateFormat.format(Date(tx.date))
                val typeLabel = if (tx.type == "GAVE") "GAVE (Dr)" else "GOT (Cr)"
                val noteText = listOf(com.example.ui.components.ChallanNo.label(tx.challanNo), tx.note).filter { it.isNotBlank() }.joinToString(", ")
                val noteStr = if (noteText.isNotBlank()) " - $noteText" else ""
                sb.append("• $dateStr: $typeLabel ₹${"%.2f".format(tx.amount)}$noteStr\n")
                sb.append("  Bal: ₹${"%.2f".format(entry.runningBalance)}\n")
            }
        }

        sb.append("\n----------------------------\n")
        sb.append("Total Debit (Gave): ₹${"%.2f".format(statement.totalDebit)}\n")
        sb.append("Total Credit (Got): ₹${"%.2f".format(statement.totalCredit)}\n")

        val bal = statement.closingBalance
        val closingText = when {
            bal > 0.01 -> "CLOSING BALANCE: ₹${"%.2f".format(bal)} (YOU WILL GET / LENA HAI)"
            bal < -0.01 -> "CLOSING BALANCE: ₹${"%.2f".format(-bal)} (YOU WILL GIVE / DENA HAI)"
            else -> "CLOSING BALANCE: ₹0.00 (ALL SETTLED)"
        }
        sb.append("*$closingText*\n")

        if (storeProfile.upiId.isNotBlank() && bal > 0.01) {
            sb.append("\nPay via UPI: *${storeProfile.upiId}*\n")
        }
        sb.append("\n_Thank you for your business!_")

        return sb.toString()
    }

    /**
     * Generates a multi-page PDF document statement using Android's native PdfDocument.
     * Pages automatically break when content exceeds the printable area.
     * Headers, column names, and page numbers repeat on every page.
     */
    fun generateStatementPdf(
        context: Context,
        statement: StatementResult,
        storeProfile: StoreProfile,
        /** Entry id -> photo file paths, shown as thumbnails and full size at the end. */
        photosByTx: Map<String, List<String>> = emptyMap(),
        /** Used for the PDF words unless a PDF language is picked in More > PDF ka Design. */
        appLang: AppLanguage = AppLanguage.HINGLISH
    ): File {
        val statementsDir = File(context.cacheDir, "statements").apply { mkdirs() }
        val safeName = statement.customer.name.replace("[^a-zA-Z0-9_.-]".toRegex(), "_")
        val pdfFile = File(statementsDir, "Statement_${safeName}_${System.currentTimeMillis()}.pdf")

        try {
            com.example.ui.components.EntryDetail.load(context)
            val style = PdfStyle.load(context)
            StatementPdf.render(
                statement = statement,
                store = storeProfile,
                style = style,
                lang = style.langFor(appLang),
                detailMode = com.example.ui.components.EntryDetail.mode,
                photosByTx = photosByTx,
                out = pdfFile
            )
        } catch (t: Throwable) {
            android.util.Log.w("StatementGenerator", "PDF generation fallback: ${t.message}")
            FileOutputStream(pdfFile).use { out ->
                val sb = StringBuilder()
                sb.append("%PDF-1.4\n")
                sb.append("% Statement for ${statement.customer.name}\n")
                statement.entries.forEach { entry ->
                    sb.append("% Entry: ${entry.transaction.note} | Debit: ${entry.debit} | Credit: ${entry.credit} | Balance: ${entry.runningBalance}\n")
                }
                sb.append("1 0 obj<</Type/Catalog/Pages 2 0 R>>endobj\n2 0 obj<</Type/Pages/Kids[3 0 R]/Count 1>>endobj\n3 0 obj<</Type/Page/MediaBox[0 0 595 842]/Parent 2 0 R>>endobj\nxref\n0 4\n0000000000 65535 f \n0000000009 00000 n \n0000000052 00000 n \n0000000101 00000 n \ntrailer<</Size 4/Root 1 0 R>>\nstartxref\n162\n%%EOF\n")
                out.write(sb.toString().toByteArray())
            }
        }

        return pdfFile
    }

    private fun entriesIndex(list: List<StatementEntry>, item: StatementEntry): Int {
        return list.indexOf(item)
    }
}
