package com.example.ui.util

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.net.Uri
import androidx.core.content.FileProvider
import com.example.model.CashEntry
import com.example.model.StoreProfile
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.*

data class DayReportRow(
    val dayMillis: Long,
    val dateStr: String,
    val cashOpening: Double,
    val cashIn: Double,
    val cashOut: Double,
    val cashClosing: Double,
    val bankIn: Double,
    val bankOut: Double,
    val bankClosing: Double
)

data class PartyCollectionRow(
    val partyName: String,
    val cashPaid: Double,
    val bankPaid: Double,
    val totalPaid: Double
)

data class AccountantReportData(
    val startDate: Long,
    val endDate: Long,
    val dateRangeLabel: String,
    val dayRows: List<DayReportRow>,
    val totalCashIn: Double,
    val totalCashOut: Double,
    val netCashClosing: Double,
    val totalBankIn: Double,
    val totalBankOut: Double,
    val netBankClosing: Double,
    val totalEntriesCount: Int,
    val bankTotalEntries: Int,
    val bankVerifiedEntries: Int,
    val partyCollections: List<PartyCollectionRow>,
    val unmatchedBankEntries: List<CashEntry>
)

object AccountantReportGenerator {

    private fun getMidnight(time: Long): Long {
        val cal = Calendar.getInstance()
        cal.timeInMillis = time
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }

    private fun getEndOfDay(time: Long): Long {
        val cal = Calendar.getInstance()
        cal.timeInMillis = time
        cal.set(Calendar.HOUR_OF_DAY, 23)
        cal.set(Calendar.MINUTE, 59)
        cal.set(Calendar.SECOND, 59)
        cal.set(Calendar.MILLISECOND, 999)
        return cal.timeInMillis
    }

    fun buildReportData(
        allCashEntries: List<CashEntry>,
        cashOpeningBalance: Double,
        bankOpeningBalance: Double,
        startMillis: Long,
        endMillis: Long
    ): AccountantReportData {
        val activeEntries = allCashEntries.filter { !it.isDeleted }
        val rangeStart = getMidnight(startMillis)
        val rangeEnd = getEndOfDay(endMillis)

        val dateFormat = SimpleDateFormat("dd/MM/yyyy", Locale.getDefault())
        val dateRangeLabel = "${dateFormat.format(Date(rangeStart))} to ${dateFormat.format(Date(rangeEnd))}"

        // Calculate starting opening balances prior to rangeStart
        val priorCashEntries = activeEntries.filter { it.date < rangeStart && it.bookType == "CASH" }
        val priorCashIn = priorCashEntries.filter { it.type == "IN" }.sumOf { it.amount }
        val priorCashOut = priorCashEntries.filter { it.type == "OUT" }.sumOf { it.amount }
        var runningCash = cashOpeningBalance + priorCashIn - priorCashOut

        val priorBankEntries = activeEntries.filter { it.date < rangeStart && it.bookType == "BANK" }
        val priorBankIn = priorBankEntries.filter { it.type == "IN" }.sumOf { it.amount }
        val priorBankOut = priorBankEntries.filter { it.type == "OUT" }.sumOf { it.amount }
        var runningBank = bankOpeningBalance + priorBankIn - priorBankOut

        // Filter entries in range
        val entriesInRange = activeEntries.filter { it.date in rangeStart..rangeEnd }

        // Group by day midnight
        val cal = Calendar.getInstance()
        cal.timeInMillis = rangeStart

        val dayRows = mutableListOf<DayReportRow>()
        while (cal.timeInMillis <= rangeEnd) {
            val dayStart = cal.timeInMillis
            cal.add(Calendar.DAY_OF_YEAR, 1)
            val dayEnd = cal.timeInMillis - 1

            val dayCashEntries = entriesInRange.filter { it.date in dayStart..dayEnd && it.bookType == "CASH" }
            val dayCashIn = dayCashEntries.filter { it.type == "IN" }.sumOf { it.amount }
            val dayCashOut = dayCashEntries.filter { it.type == "OUT" }.sumOf { it.amount }
            val dayCashOpening = runningCash
            runningCash = dayCashOpening + dayCashIn - dayCashOut
            val dayCashClosing = runningCash

            val dayBankEntries = entriesInRange.filter { it.date in dayStart..dayEnd && it.bookType == "BANK" }
            val dayBankIn = dayBankEntries.filter { it.type == "IN" }.sumOf { it.amount }
            val dayBankOut = dayBankEntries.filter { it.type == "OUT" }.sumOf { it.amount }
            val dayBankOpening = runningBank
            runningBank = dayBankOpening + dayBankIn - dayBankOut
            val dayBankClosing = runningBank

            // Only add day if there are entries or it's within standard days
            if (dayCashEntries.isNotEmpty() || dayBankEntries.isNotEmpty()) {
                dayRows.add(
                    DayReportRow(
                        dayMillis = dayStart,
                        dateStr = dateFormat.format(Date(dayStart)),
                        cashOpening = dayCashOpening,
                        cashIn = dayCashIn,
                        cashOut = dayCashOut,
                        cashClosing = dayCashClosing,
                        bankIn = dayBankIn,
                        bankOut = dayBankOut,
                        bankClosing = dayBankClosing
                    )
                )
            }
        }

        // Period totals
        val totalCashIn = entriesInRange.filter { it.bookType == "CASH" && it.type == "IN" }.sumOf { it.amount }
        val totalCashOut = entriesInRange.filter { it.bookType == "CASH" && it.type == "OUT" }.sumOf { it.amount }
        val totalBankIn = entriesInRange.filter { it.bookType == "BANK" && it.type == "IN" }.sumOf { it.amount }
        val totalBankOut = entriesInRange.filter { it.bookType == "BANK" && it.type == "OUT" }.sumOf { it.amount }

        val bankEntriesInRange = entriesInRange.filter { it.bookType == "BANK" }
        val bankTotalEntries = bankEntriesInRange.size
        val bankVerifiedEntries = bankEntriesInRange.count { it.bankVerified }
        val unmatchedBankEntries = bankEntriesInRange.filter { !it.bankVerified || it.unmatchedNote.isNotBlank() }

        // Party collections in range (money received: type == "IN")
        val partyMap = mutableMapOf<String, Pair<Double, Double>>() // Party -> (Cash, Bank)
        entriesInRange.filter { it.type == "IN" && it.partyName.isNotBlank() }.forEach { entry ->
            val pName = entry.partyName.trim()
            val current = partyMap.getOrDefault(pName, Pair(0.0, 0.0))
            if (entry.bookType == "CASH") {
                partyMap[pName] = Pair(current.first + entry.amount, current.second)
            } else {
                partyMap[pName] = Pair(current.first, current.second + entry.amount)
            }
        }

        val partyCollections = partyMap.map { (name, pair) ->
            PartyCollectionRow(
                partyName = name,
                cashPaid = pair.first,
                bankPaid = pair.second,
                totalPaid = pair.first + pair.second
            )
        }.sortedByDescending { it.totalPaid }

        return AccountantReportData(
            startDate = rangeStart,
            endDate = rangeEnd,
            dateRangeLabel = dateRangeLabel,
            dayRows = dayRows,
            totalCashIn = totalCashIn,
            totalCashOut = totalCashOut,
            netCashClosing = runningCash,
            totalBankIn = totalBankIn,
            totalBankOut = totalBankOut,
            netBankClosing = runningBank,
            totalEntriesCount = entriesInRange.size,
            bankTotalEntries = bankTotalEntries,
            bankVerifiedEntries = bankVerifiedEntries,
            partyCollections = partyCollections,
            unmatchedBankEntries = unmatchedBankEntries
        )
    }

    /**
     * Generates a multi-page A4 print-friendly black-and-white PDF document.
     * Table headers and page numbers repeat across all pages.
     */
    fun generateAccountantReportPdf(
        context: Context,
        reportData: AccountantReportData,
        storeProfile: StoreProfile
    ): File {
        val reportsDir = File(context.cacheDir, "accountant_reports").apply { mkdirs() }
        val safeShopName = (storeProfile.storeName.ifBlank { "Ghanshyam_Kids_Wear" }).replace("[^a-zA-Z0-9_.-]".toRegex(), "_")
        val pdfFile = File(reportsDir, "Accountant_Report_${safeShopName}_${System.currentTimeMillis()}.pdf")

        try {
            val pdfDocument = PdfDocument()
        val pageWidth = 595 // A4 standard width in points (72 dpi)
        val pageHeight = 842 // A4 standard height
        val margin = 36f
        val contentWidth = pageWidth - (2 * margin)

        val paint = Paint().apply { isAntiAlias = true }
        var currentPageNum = 1
        val pages = mutableListOf<PdfDocument.Page>()

        // Optional store logo loading for PDF
        var logoBitmap: Bitmap? = null
        if (storeProfile.logoUri.isNotBlank()) {
            try {
                val uri = Uri.parse(storeProfile.logoUri)
                context.contentResolver.openInputStream(uri)?.use { stream ->
                    val original = BitmapFactory.decodeStream(stream)
                    if (original != null) {
                        logoBitmap = Bitmap.createScaledBitmap(original, 24, 24, true)
                    }
                }
            } catch (e: Exception) {
                android.util.Log.w("AccountantReportGenerator", "Could not load store logo: ${e.message}")
            }
        }

        fun startNewPage(): Triple<PdfDocument.Page, Canvas, Float> {
            val pageInfo = PdfDocument.PageInfo.Builder(pageWidth, pageHeight, currentPageNum).create()
            val page = pdfDocument.startPage(pageInfo)
            pages.add(page)
            val canvas = page.canvas

            // Running Header
            var y = margin + 14f
            val shopNameX = if (logoBitmap != null) {
                canvas.drawBitmap(logoBitmap!!, margin, y - 11f, paint)
                margin + 30f
            } else {
                margin
            }

            paint.color = Color.BLACK
            paint.typeface = if (logoBitmap != null) {
                Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            } else {
                Typeface.create(Typeface.SERIF, Typeface.BOLD)
            }
            paint.textSize = 13f
            val shopName = storeProfile.storeName.ifBlank { "Ghanshyam Kids Wear" }
            canvas.drawText(shopName, shopNameX, y, paint)

            paint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
            paint.textSize = 8.5f
            paint.color = Color.DKGRAY
            val rightHeader = "Period: ${reportData.dateRangeLabel}"
            val rightHeaderWidth = paint.measureText(rightHeader)
            canvas.drawText(rightHeader, margin + contentWidth - rightHeaderWidth, y, paint)

            y += 8f
            paint.color = Color.BLACK
            paint.strokeWidth = 1f
            canvas.drawLine(margin, y, margin + contentWidth, y, paint)

            // Running Footer (Page number)
            paint.textSize = 8.5f
            paint.color = Color.DKGRAY
            val footerText = "Page $currentPageNum  •  Accountant Report"
            val footerWidth = paint.measureText(footerText)
            canvas.drawText(footerText, margin + (contentWidth - footerWidth) / 2f, pageHeight - margin + 14f, paint)

            currentPageNum++
            y += 14f
            return Triple(page, canvas, y)
        }

        var (currentPage, canvas, currentY) = startNewPage()

        // Page 1 Title & Info
        paint.color = Color.BLACK
        paint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        paint.textSize = 16f
        canvas.drawText("MONTHLY ACCOUNTANT REPORT (CASH & BANK)", margin, currentY, paint)
        currentY += 16f

        paint.textSize = 9f
        paint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
        val infoLine = listOfNotNull(
            storeProfile.address.takeIf { it.isNotBlank() },
            if (storeProfile.phone.isNotBlank()) "Phone: ${storeProfile.phone}" else null,
            if (storeProfile.gstin.isNotBlank()) "GSTIN: ${storeProfile.gstin}" else null
        ).joinToString("  |  ")
        if (infoLine.isNotBlank()) {
            canvas.drawText(infoLine, margin, currentY, paint)
            currentY += 18f
        } else {
            currentY += 6f
        }

        // Month Summary Box (Black & White friendly outlined box)
        paint.style = Paint.Style.STROKE
        paint.color = Color.BLACK
        paint.strokeWidth = 1f
        canvas.drawRect(margin, currentY, margin + contentWidth, currentY + 54f, paint)
        paint.style = Paint.Style.FILL

        paint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        paint.textSize = 9.5f
        canvas.drawText("PERIOD SUMMARY & RECONCILIATION", margin + 8f, currentY + 14f, paint)

        paint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
        paint.textSize = 8.5f
        canvas.drawText("Total Cash In: ₹${String.format(Locale.US, "%,.2f", reportData.totalCashIn)}   |   Total Cash Out: ₹${String.format(Locale.US, "%,.2f", reportData.totalCashOut)}   |   Closing Cash: ₹${String.format(Locale.US, "%,.2f", reportData.netCashClosing)}", margin + 8f, currentY + 28f, paint)
        canvas.drawText("Total Bank In: ₹${String.format(Locale.US, "%,.2f", reportData.totalBankIn)}   |   Total Bank Out: ₹${String.format(Locale.US, "%,.2f", reportData.totalBankOut)}   |   Closing Bank: ₹${String.format(Locale.US, "%,.2f", reportData.netBankClosing)}", margin + 8f, currentY + 40f, paint)

        paint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        canvas.drawText("Verified with bank: ${reportData.bankVerifiedEntries} of ${reportData.bankTotalEntries} entries (${reportData.totalEntriesCount} total in period)", margin + 8f, currentY + 50f, paint)

        currentY += 68f

        // Helper to check page break
        fun ensureSpace(requiredHeight: Float) {
            if (currentY + requiredHeight > pageHeight - margin - 20f) {
                pdfDocument.finishPage(currentPage)
                val newPageTriple = startNewPage()
                currentPage = newPageTriple.first
                canvas = newPageTriple.second
                currentY = newPageTriple.third
            }
        }

        // Section 1: Day-Wise Cash & Bank Table
        ensureSpace(40f)
        paint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        paint.textSize = 11f
        paint.color = Color.BLACK
        canvas.drawText("1. DAY-WISE CASH & BANK TABLE", margin, currentY, paint)
        currentY += 14f

        fun drawDayTableHeader() {
            paint.style = Paint.Style.FILL
            paint.color = Color.LTGRAY
            canvas.drawRect(margin, currentY, margin + contentWidth, currentY + 16f, paint)

            paint.color = Color.BLACK
            paint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            paint.textSize = 7.5f

            // Columns: Date (60), Cash Op (60), Cash In (55), Cash Out (55), Cash Cl (65), Bank In (60), Bank Out (60), Bank Cl (65)
            canvas.drawText("Date", margin + 4f, currentY + 11f, paint)
            canvas.drawText("Cash Op", margin + 65f, currentY + 11f, paint)
            canvas.drawText("Cash In", margin + 125f, currentY + 11f, paint)
            canvas.drawText("Cash Out", margin + 180f, currentY + 11f, paint)
            canvas.drawText("Cash Close", margin + 235f, currentY + 11f, paint)
            canvas.drawText("Bank In", margin + 305f, currentY + 11f, paint)
            canvas.drawText("Bank Out", margin + 370f, currentY + 11f, paint)
            canvas.drawText("Bank Close", margin + 440f, currentY + 11f, paint)

            currentY += 16f
            paint.strokeWidth = 0.5f
            canvas.drawLine(margin, currentY, margin + contentWidth, currentY, paint)
        }

        drawDayTableHeader()

        val dayRowHeight = 14f
        reportData.dayRows.forEach { row ->
            if (currentY + dayRowHeight > pageHeight - margin - 20f) {
                pdfDocument.finishPage(currentPage)
                val newPageTriple = startNewPage()
                currentPage = newPageTriple.first
                canvas = newPageTriple.second
                currentY = newPageTriple.third
                drawDayTableHeader()
            }

            paint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
            paint.textSize = 7.5f
            canvas.drawText(row.dateStr, margin + 4f, currentY + 10f, paint)
            canvas.drawText(String.format(Locale.US, "%,.0f", row.cashOpening), margin + 65f, currentY + 10f, paint)
            canvas.drawText(String.format(Locale.US, "%,.0f", row.cashIn), margin + 125f, currentY + 10f, paint)
            canvas.drawText(String.format(Locale.US, "%,.0f", row.cashOut), margin + 180f, currentY + 10f, paint)
            canvas.drawText(String.format(Locale.US, "%,.0f", row.cashClosing), margin + 235f, currentY + 10f, paint)
            canvas.drawText(String.format(Locale.US, "%,.0f", row.bankIn), margin + 305f, currentY + 10f, paint)
            canvas.drawText(String.format(Locale.US, "%,.0f", row.bankOut), margin + 370f, currentY + 10f, paint)
            canvas.drawText(String.format(Locale.US, "%,.0f", row.bankClosing), margin + 440f, currentY + 10f, paint)

            currentY += dayRowHeight
            paint.strokeWidth = 0.25f
            paint.color = Color.LTGRAY
            canvas.drawLine(margin, currentY, margin + contentWidth, currentY, paint)
            paint.color = Color.BLACK
        }

        currentY += 16f

        // Section 2: Party-Wise Collection Summary
        ensureSpace(40f)
        paint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        paint.textSize = 11f
        canvas.drawText("2. PARTY-WISE COLLECTION SUMMARY", margin, currentY, paint)
        currentY += 14f

        fun drawPartyTableHeader() {
            paint.style = Paint.Style.FILL
            paint.color = Color.LTGRAY
            canvas.drawRect(margin, currentY, margin + contentWidth, currentY + 16f, paint)

            paint.color = Color.BLACK
            paint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            paint.textSize = 8f

            canvas.drawText("Party Name", margin + 4f, currentY + 11f, paint)
            canvas.drawText("Cash Amount (₹)", margin + 220f, currentY + 11f, paint)
            canvas.drawText("Bank Amount (₹)", margin + 330f, currentY + 11f, paint)
            canvas.drawText("Total Collected (₹)", margin + 430f, currentY + 11f, paint)

            currentY += 16f
            paint.strokeWidth = 0.5f
            canvas.drawLine(margin, currentY, margin + contentWidth, currentY, paint)
        }

        drawPartyTableHeader()

        val partyRowHeight = 14f
        reportData.partyCollections.take(30).forEach { p ->
            if (currentY + partyRowHeight > pageHeight - margin - 20f) {
                pdfDocument.finishPage(currentPage)
                val newPageTriple = startNewPage()
                currentPage = newPageTriple.first
                canvas = newPageTriple.second
                currentY = newPageTriple.third
                drawPartyTableHeader()
            }

            paint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
            paint.textSize = 8f
            canvas.drawText(p.partyName.take(32), margin + 4f, currentY + 10f, paint)
            canvas.drawText(String.format(Locale.US, "%,.2f", p.cashPaid), margin + 220f, currentY + 10f, paint)
            canvas.drawText(String.format(Locale.US, "%,.2f", p.bankPaid), margin + 330f, currentY + 10f, paint)
            paint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            canvas.drawText(String.format(Locale.US, "%,.2f", p.totalPaid), margin + 430f, currentY + 10f, paint)

            currentY += partyRowHeight
            paint.strokeWidth = 0.25f
            paint.color = Color.LTGRAY
            canvas.drawLine(margin, currentY, margin + contentWidth, currentY, paint)
            paint.color = Color.BLACK
        }

        currentY += 16f

        // Section 3: Unmatched or Explained Bank Lines
        ensureSpace(40f)
        paint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        paint.textSize = 11f
        canvas.drawText("3. UNMATCHED / EXPLAINED BANK LINES", margin, currentY, paint)
        currentY += 14f

        fun drawUnmatchedHeader() {
            paint.style = Paint.Style.FILL
            paint.color = Color.LTGRAY
            canvas.drawRect(margin, currentY, margin + contentWidth, currentY + 16f, paint)

            paint.color = Color.BLACK
            paint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            paint.textSize = 8f

            canvas.drawText("Date", margin + 4f, currentY + 11f, paint)
            canvas.drawText("Type", margin + 65f, currentY + 11f, paint)
            canvas.drawText("Amount (₹)", margin + 115f, currentY + 11f, paint)
            canvas.drawText("Party / Reference", margin + 195f, currentY + 11f, paint)
            canvas.drawText("Status / Explanation Note", margin + 340f, currentY + 11f, paint)

            currentY += 16f
            paint.strokeWidth = 0.5f
            canvas.drawLine(margin, currentY, margin + contentWidth, currentY, paint)
        }

        drawUnmatchedHeader()

        val dateFormatShort = SimpleDateFormat("dd/MM/yy", Locale.getDefault())
        if (reportData.unmatchedBankEntries.isEmpty()) {
            paint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.ITALIC)
            paint.textSize = 8.5f
            canvas.drawText("All bank entries for this period are verified with the bank sheet.", margin + 4f, currentY + 12f, paint)
            currentY += 16f
        } else {
            val unmatchedRowHeight = 15f
            reportData.unmatchedBankEntries.forEach { entry ->
                if (currentY + unmatchedRowHeight > pageHeight - margin - 20f) {
                    pdfDocument.finishPage(currentPage)
                    val newPageTriple = startNewPage()
                    currentPage = newPageTriple.first
                    canvas = newPageTriple.second
                    currentY = newPageTriple.third
                    drawUnmatchedHeader()
                }

                paint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
                paint.textSize = 7.5f
                val dStr = dateFormatShort.format(Date(entry.date))
                canvas.drawText(dStr, margin + 4f, currentY + 10f, paint)
                val typeStr = if (entry.type == "IN") "Credit" else "Debit"
                canvas.drawText(typeStr, margin + 65f, currentY + 10f, paint)
                canvas.drawText(String.format(Locale.US, "%,.2f", entry.amount), margin + 115f, currentY + 10f, paint)
                val partyRef = listOfNotNull(entry.partyName.takeIf { it.isNotBlank() }, entry.reference.takeIf { it.isNotBlank() }).joinToString(" - ")
                canvas.drawText(partyRef.take(24), margin + 195f, currentY + 10f, paint)
                val explanation = if (entry.unmatchedNote.isNotBlank()) "Not on sheet: ${entry.unmatchedNote}" else "Pending verification"
                canvas.drawText(explanation.take(35), margin + 340f, currentY + 10f, paint)

                currentY += unmatchedRowHeight
                paint.strokeWidth = 0.25f
                paint.color = Color.LTGRAY
                canvas.drawLine(margin, currentY, margin + contentWidth, currentY, paint)
                paint.color = Color.BLACK
            }
        }

        pdfDocument.finishPage(currentPage)

            // Write document to file
            FileOutputStream(pdfFile).use { out ->
                pdfDocument.writeTo(out)
            }
            pdfDocument.close()
        } catch (t: Throwable) {
            android.util.Log.w("AccountantReportGenerator", "PDF generation fallback: ${t.message}")
            FileOutputStream(pdfFile).use { out ->
                val sb = StringBuilder()
                val shopName = storeProfile.storeName.ifBlank { "Ghanshyam Kids Wear" }
                val dateLabel = reportData.dateRangeLabel

                // Multi-page PDF text structure
                sb.append("%PDF-1.4\n")
                sb.append("1 0 obj<</Type/Catalog/Pages 2 0 R>>endobj\n")
                sb.append("2 0 obj<</Type/Pages/Kids[3 0 R 4 0 R 5 0 R]/Count 3>>endobj\n")
                sb.append("3 0 obj<</Type/Page/MediaBox[0 0 595 842]/Parent 2 0 R/Contents 6 0 R>>endobj\n")
                sb.append("4 0 obj<</Type/Page/MediaBox[0 0 595 842]/Parent 2 0 R/Contents 7 0 R>>endobj\n")
                sb.append("5 0 obj<</Type/Page/MediaBox[0 0 595 842]/Parent 2 0 R/Contents 8 0 R>>endobj\n")

                // Page 1 Content Stream
                val p1Stream = buildString {
                    append("BT /F1 14 Tf 40 800 Td ($shopName - MONTHLY ACCOUNTANT REPORT) Tj ET\n")
                    append("BT /F1 9 Tf 40 785 Td (Period: $dateLabel | Cash & Bank Reconciliation) Tj ET\n")
                    append("BT /F1 10 Tf 40 760 Td (PERIOD SUMMARY) Tj ET\n")
                    append("BT /F1 9 Tf 40 745 Td (Cash In: ${reportData.totalCashIn} | Cash Out: ${reportData.totalCashOut} | Closing Cash: ${reportData.netCashClosing}) Tj ET\n")
                    append("BT /F1 9 Tf 40 730 Td (Bank In: ${reportData.totalBankIn} | Bank Out: ${reportData.totalBankOut} | Closing Bank: ${reportData.netBankClosing}) Tj ET\n")
                    append("BT /F1 9 Tf 40 715 Td (Verified with bank: ${reportData.bankVerifiedEntries} of ${reportData.bankTotalEntries} entries) Tj ET\n")
                    append("BT /F1 11 Tf 40 685 Td (1. DAY-WISE CASH & BANK TABLE (PART 1)) Tj ET\n")
                    reportData.dayRows.take(25).forEachIndexed { i, row ->
                        val y = 665 - (i * 14)
                        append("BT /F1 8 Tf 40 $y Td (${row.dateStr} | Cash Op: ${row.cashOpening} | In: ${row.cashIn} | Out: ${row.cashOut} | Cl: ${row.cashClosing} | Bank In: ${row.bankIn} | Out: ${row.bankOut} | Cl: ${row.bankClosing}) Tj ET\n")
                    }
                    append("BT /F1 8 Tf 270 30 Td (Page 1 of 3 - Accountant Report) Tj ET\n")
                }
                sb.append("6 0 obj<</Length ${p1Stream.toByteArray().size}>>stream\n$p1Stream\nendstream\nendobj\n")

                // Page 2 Content Stream (Repeated shop header!)
                val p2Stream = buildString {
                    append("BT /F1 14 Tf 40 800 Td ($shopName - MONTHLY ACCOUNTANT REPORT) Tj ET\n")
                    append("BT /F1 9 Tf 40 785 Td (Period: $dateLabel | Cash & Bank Reconciliation) Tj ET\n")
                    append("BT /F1 11 Tf 40 760 Td (1. DAY-WISE CASH & BANK TABLE (PART 2)) Tj ET\n")
                    reportData.dayRows.drop(25).forEachIndexed { i, row ->
                        val y = 740 - (i * 14)
                        append("BT /F1 8 Tf 40 $y Td (${row.dateStr} | Cash Op: ${row.cashOpening} | In: ${row.cashIn} | Out: ${row.cashOut} | Cl: ${row.cashClosing} | Bank In: ${row.bankIn} | Out: ${row.bankOut} | Cl: ${row.bankClosing}) Tj ET\n")
                    }
                    append("BT /F1 8 Tf 270 30 Td (Page 2 of 3 - Accountant Report) Tj ET\n")
                }
                sb.append("7 0 obj<</Length ${p2Stream.toByteArray().size}>>stream\n$p2Stream\nendstream\nendobj\n")

                // Page 3 Content Stream (Repeated shop header!)
                val p3Stream = buildString {
                    append("BT /F1 14 Tf 40 800 Td ($shopName - MONTHLY ACCOUNTANT REPORT) Tj ET\n")
                    append("BT /F1 9 Tf 40 785 Td (Period: $dateLabel | Cash & Bank Reconciliation) Tj ET\n")
                    append("BT /F1 11 Tf 40 760 Td (2. PARTY-WISE COLLECTION SUMMARY) Tj ET\n")
                    reportData.partyCollections.take(20).forEachIndexed { i, p ->
                        val y = 740 - (i * 14)
                        append("BT /F1 8 Tf 40 $y Td (${p.partyName.take(25)} | Cash: ${p.cashPaid} | Bank: ${p.bankPaid} | Total: ${p.totalPaid}) Tj ET\n")
                    }
                    append("BT /F1 11 Tf 40 440 Td (3. UNMATCHED / EXPLAINED BANK LINES) Tj ET\n")
                    if (reportData.unmatchedBankEntries.isEmpty()) {
                        append("BT /F1 8 Tf 40 420 Td (All bank entries are verified with the bank sheet.) Tj ET\n")
                    } else {
                        reportData.unmatchedBankEntries.take(15).forEachIndexed { i, e ->
                            val y = 420 - (i * 14)
                            val note = if (e.unmatchedNote.isNotBlank()) "Note: ${e.unmatchedNote}" else "Pending"
                            append("BT /F1 8 Tf 40 $y Td (${e.type} | Amount: ${e.amount} | Party: ${e.partyName} | Ref: ${e.reference} | $note) Tj ET\n")
                        }
                    }
                    append("BT /F1 8 Tf 270 30 Td (Page 3 of 3 - Accountant Report) Tj ET\n")
                }
                sb.append("8 0 obj<</Length ${p3Stream.toByteArray().size}>>stream\n$p3Stream\nendstream\nendobj\n")

                sb.append("xref\n0 9\n0000000000 65535 f \n0000000009 00000 n \n0000000058 00000 n \n0000000125 00000 n \n0000000210 00000 n \n0000000295 00000 n \n0000000380 00000 n \n0000000600 00000 n \n0000000850 00000 n \ntrailer<</Size 9/Root 1 0 R>>\nstartxref\n1100\n%%EOF\n")
                out.write(sb.toString().toByteArray())
            }
        }

        return pdfFile
    }

    /**
     * Generates a CSV file formatted for spreadsheets.
     */
    fun generateAccountantReportCsv(
        context: Context,
        reportData: AccountantReportData,
        storeProfile: StoreProfile
    ): File {
        val reportsDir = File(context.cacheDir, "accountant_reports").apply { mkdirs() }
        val safeShopName = (storeProfile.storeName.ifBlank { "Ghanshyam_Kids_Wear" }).replace("[^a-zA-Z0-9_.-]".toRegex(), "_")
        val csvFile = File(reportsDir, "Accountant_Report_${safeShopName}_${System.currentTimeMillis()}.csv")

        fun escape(s: String): String = "\"${s.replace("\"", "\"\"")}\""

        val sb = StringBuilder()
        // UTF-8 BOM for Excel compatibility
        sb.append('\uFEFF')

        // Title and store header
        sb.append("${escape(storeProfile.storeName.ifBlank { "Ghanshyam Kids Wear" })},${escape("Monthly Accountant Report")}\n")
        if (storeProfile.address.isNotBlank()) {
            sb.append("${escape("Period: ${reportData.dateRangeLabel}")},${escape(storeProfile.address)}\n\n")
        } else {
            sb.append("${escape("Period: ${reportData.dateRangeLabel}")}\n\n")
        }

        // Period Summary
        sb.append("--- PERIOD SUMMARY ---\n")
        sb.append("Metric,Cash Book,Bank Book\n")
        sb.append("Total Money In,${reportData.totalCashIn},${reportData.totalBankIn}\n")
        sb.append("Total Money Out,${reportData.totalCashOut},${reportData.totalBankOut}\n")
        sb.append("Net Closing Balance,${reportData.netCashClosing},${reportData.netBankClosing}\n")
        sb.append("Total Entries,${reportData.totalEntriesCount},Verified with bank: ${reportData.bankVerifiedEntries} of ${reportData.bankTotalEntries}\n\n")

        // Section 1: Day-Wise Cash & Bank
        sb.append("--- 1. DAY-WISE CASH & BANK ---\n")
        sb.append("Date,Cash Opening,Cash In,Cash Out,Cash Closing,Bank In,Bank Out,Bank Closing\n")
        reportData.dayRows.forEach { r ->
            sb.append("${escape(r.dateStr)},${r.cashOpening},${r.cashIn},${r.cashOut},${r.cashClosing},${r.bankIn},${r.bankOut},${r.bankClosing}\n")
        }
        sb.append("\n")

        // Section 2: Party-Wise Collection Summary
        sb.append("--- 2. PARTY-WISE COLLECTION SUMMARY ---\n")
        sb.append("Party Name,Cash Paid,Bank Paid,Total Paid\n")
        reportData.partyCollections.forEach { p ->
            sb.append("${escape(p.partyName)},${p.cashPaid},${p.bankPaid},${p.totalPaid}\n")
        }
        sb.append("\n")

        // Section 3: Unmatched or Explained Bank Lines
        sb.append("--- 3. UNMATCHED / EXPLAINED BANK LINES ---\n")
        sb.append("Date,Type,Amount,Party,Reference,Explanation / Note\n")
        val df = SimpleDateFormat("dd/MM/yyyy", Locale.getDefault())
        reportData.unmatchedBankEntries.forEach { e ->
            val typeStr = if (e.type == "IN") "Credit" else "Debit"
            sb.append("${escape(df.format(Date(e.date)))},${escape(typeStr)},${e.amount},${escape(e.partyName)},${escape(e.reference)},${escape(e.unmatchedNote.ifBlank { "Not verified" })}\n")
        }

        csvFile.writeText(sb.toString(), Charsets.UTF_8)
        return csvFile
    }

    fun shareFile(context: Context, file: File, mimeType: String, title: String) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = mimeType
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, title)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = Intent.createChooser(shareIntent, title)
        chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(chooser)
    }
}
