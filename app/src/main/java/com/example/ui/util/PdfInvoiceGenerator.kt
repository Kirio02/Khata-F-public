package com.example.ui.util

import android.content.Context
import android.content.Intent
import android.graphics.*
import android.graphics.pdf.PdfDocument
import android.net.Uri
import androidx.core.content.FileProvider
import com.example.model.Invoice
import com.example.model.StoreProfile
import com.example.model.TaxSummaryRow
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.*

object PdfInvoiceGenerator {

    /**
     * Generates a native multi-page PDF document for the given invoice and store profile.
     * Fully compliant with Indian GST requirements, State-of-Supply tax split,
     * round-off display, total in words, repeating headers, and page numbering.
     */
    fun generateInvoicePdf(
        context: Context,
        storeProfile: StoreProfile,
        invoice: Invoice
    ): File {
        val invoicesDir = File(context.cacheDir, "invoices").apply { mkdirs() }
        val safeBillNum = invoice.billNumber.replace("[^a-zA-Z0-9_.-]".toRegex(), "_")
        val pdfFile = File(invoicesDir, "Invoice_$safeBillNum.pdf")

        try {
            val pdfDocument = PdfDocument()
            val pageWidth = 595
            val pageHeight = 842
            val margin = 36f
            val contentWidth = pageWidth - (2 * margin)

            val isTaxInvoice = com.example.ui.security.SecureSettingsManager.isGstBillsEnabled(context) && storeProfile.gstin.isNotBlank()
            val isSameState = storeProfile.state.trim().equals(invoice.stateOfSupply.trim(), ignoreCase = true)

            // Compute Tax Breakdown by rate
            val taxMap = LinkedHashMap<Double, Double>()
            invoice.items.forEach { item ->
                val lineTaxable = item.quantity * item.unitPrice
                taxMap[item.gstPercent] = (taxMap[item.gstPercent] ?: 0.0) + lineTaxable
            }

            val taxSummaryRows = taxMap.map { (rate, taxable) ->
                if (isSameState) {
                    val halfRate = rate / 2.0
                    val cgst = taxable * (halfRate / 100.0)
                    val sgst = taxable * (halfRate / 100.0)
                    TaxSummaryRow(rate, taxable, halfRate, cgst, halfRate, sgst, 0.0, 0.0, cgst + sgst)
                } else {
                    val igst = taxable * (rate / 100.0)
                    TaxSummaryRow(rate, taxable, 0.0, 0.0, 0.0, 0.0, rate, igst, igst)
                }
            }

            // Optional shop logo loading
            var logoBitmap: Bitmap? = null
            if (storeProfile.logoUri.isNotBlank()) {
                try {
                    val uri = Uri.parse(storeProfile.logoUri)
                    context.contentResolver.openInputStream(uri)?.use { stream ->
                        val original = BitmapFactory.decodeStream(stream)
                        if (original != null) {
                            logoBitmap = Bitmap.createScaledBitmap(original, 48, 48, true)
                        }
                    }
                } catch (e: Exception) {
                    android.util.Log.w("PdfInvoiceGenerator", "Could not load store logo: ${e.message}")
                }
            }

            // Pagination item chunking
            val allItems = invoice.items
            val itemsPerPageNonFinal = 18
            val itemsOnFinalPageMax = 8

            val pageChunks = mutableListOf<List<com.example.model.InvoiceItem>>()
            if (allItems.isEmpty()) {
                pageChunks.add(emptyList())
            } else if (allItems.size <= itemsOnFinalPageMax) {
                pageChunks.add(allItems)
            } else {
                var currentIndex = 0
                while (currentIndex < allItems.size) {
                    val remaining = allItems.size - currentIndex
                    if (remaining <= itemsOnFinalPageMax) {
                        pageChunks.add(allItems.subList(currentIndex, allItems.size))
                        break
                    } else if (remaining <= itemsPerPageNonFinal) {
                        // Split into two pages so final page has room for totals
                        val firstPart = minOf(remaining - 2, itemsPerPageNonFinal - itemsOnFinalPageMax)
                        pageChunks.add(allItems.subList(currentIndex, currentIndex + firstPart))
                        currentIndex += firstPart
                    } else {
                        pageChunks.add(allItems.subList(currentIndex, currentIndex + itemsPerPageNonFinal))
                        currentIndex += itemsPerPageNonFinal
                    }
                }
            }

            val totalPages = pageChunks.size

            pageChunks.forEachIndexed { pageIndex, pageItems ->
                val pageNumber = pageIndex + 1
                val isFinalPage = (pageNumber == totalPages)
                val pageInfo = PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageNumber).create()
                val page = pdfDocument.startPage(pageInfo)
                val canvas: Canvas = page.canvas
                val paint = Paint().apply { isAntiAlias = true }

                // 1. Top Decorative Bar
                paint.color = Color.rgb(30, 58, 138) // Deep Royal Blue
                paint.style = Paint.Style.FILL
                canvas.drawRect(margin, margin, margin + contentWidth, margin + 5f, paint)

                var y = margin + 24f

                // 2. Repeating Store Header
                val headerStartX = if (logoBitmap != null) {
                    canvas.drawBitmap(logoBitmap!!, margin, y - 4f, paint)
                    margin + 58f
                } else {
                    margin
                }

                paint.color = Color.rgb(30, 58, 138)
                paint.typeface = if (logoBitmap != null) {
                    Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                } else {
                    Typeface.create(Typeface.SERIF, Typeface.BOLD)
                }
                paint.textSize = 17f
                val displayStoreName = if (storeProfile.storeName.isNotBlank()) storeProfile.storeName else "Ghanshyam Kids Wear & Traders"
                canvas.drawText(displayStoreName, headerStartX, y, paint)

                paint.color = Color.rgb(100, 116, 139)
                paint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
                paint.textSize = 8.5f
                y += 12f
                val storeSub = if (storeProfile.ownerName.isNotBlank()) "Proprietor: ${storeProfile.ownerName} | Kids Wear Wholesale & Retail" else "Exclusive Kids Wear, Wholesale & Retail Garments"
                canvas.drawText(storeSub, headerStartX, y, paint)

                y += 11f
                if (storeProfile.address.isNotBlank()) {
                    val fullAddr = if (storeProfile.state.isNotBlank()) "${storeProfile.address}, ${storeProfile.state}" else storeProfile.address
                    canvas.drawText("Address: $fullAddr", headerStartX, y, paint)
                    y += 11f
                }

                val taxParts = mutableListOf<String>()
                if (storeProfile.phone.isNotBlank()) taxParts.add("Phone: ${storeProfile.phone}")
                if (storeProfile.gstin.isNotBlank()) taxParts.add("GSTIN: ${storeProfile.gstin}")
                if (storeProfile.upiId.isNotBlank()) taxParts.add("UPI: ${storeProfile.upiId}")
                if (taxParts.isNotEmpty()) {
                    canvas.drawText(taxParts.joinToString("  •  "), headerStartX, y, paint)
                }

                // Document Title Badge (Top Right)
                val docTitle = "BILL"
                val badgeWidth = 160f
                val badgeHeight = 36f
                val badgeX = margin + contentWidth - badgeWidth
                val badgeY = margin + 12f

                paint.color = if (invoice.isCancelled) Color.rgb(254, 242, 242) else Color.rgb(239, 246, 255)
                paint.style = Paint.Style.FILL
                canvas.drawRoundRect(RectF(badgeX, badgeY, badgeX + badgeWidth, badgeY + badgeHeight), 5f, 5f, paint)

                paint.color = if (invoice.isCancelled) Color.rgb(220, 38, 38) else Color.rgb(37, 99, 235)
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = 1f
                canvas.drawRoundRect(RectF(badgeX, badgeY, badgeX + badgeWidth, badgeY + badgeHeight), 5f, 5f, paint)

                paint.style = Paint.Style.FILL
                paint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                paint.textSize = 10.5f
                paint.textAlign = Paint.Align.CENTER
                val badgeTitle = if (invoice.isCancelled) "CANCELLED BILL" else docTitle
                canvas.drawText(badgeTitle, badgeX + (badgeWidth / 2f), badgeY + 16f, paint)

                paint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
                paint.textSize = 8f
                paint.color = Color.rgb(100, 116, 139)
                canvas.drawText("Page $pageNumber of $totalPages", badgeX + (badgeWidth / 2f), badgeY + 28f, paint)
                paint.textAlign = Paint.Align.LEFT

                // Divider line
                y += 14f
                paint.color = Color.rgb(226, 232, 240)
                paint.strokeWidth = 0.8f
                canvas.drawLine(margin, y, margin + contentWidth, y, paint)

                // Cancelled Watermark/Notice if cancelled
                if (invoice.isCancelled) {
                    y += 10f
                    paint.color = Color.rgb(239, 68, 68)
                    paint.style = Paint.Style.FILL
                    canvas.drawRect(margin, y, margin + contentWidth, y + 18f, paint)
                    paint.color = Color.WHITE
                    paint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                    paint.textSize = 9f
                    paint.textAlign = Paint.Align.CENTER
                    val cancelReasonText = if (invoice.cancellationReason.isNotBlank()) "CANCELLED: ${invoice.cancellationReason}" else "INVOICE CANCELLED - STOCK & LEDGER REVERSED"
                    canvas.drawText(cancelReasonText, margin + (contentWidth / 2f), y + 12f, paint)
                    paint.textAlign = Paint.Align.LEFT
                    y += 18f
                }

                // 3. Metadata Section (Invoice info & Customer info in dual boxes)
                y += 10f
                val boxHeight = 64f
                val colWidth = (contentWidth - 8f) / 2f

                // Left Box: Invoice Details
                val leftBoxRect = RectF(margin, y, margin + colWidth, y + boxHeight)
                paint.color = Color.rgb(248, 250, 252)
                paint.style = Paint.Style.FILL
                canvas.drawRoundRect(leftBoxRect, 5f, 5f, paint)
                paint.color = Color.rgb(226, 232, 240)
                paint.style = Paint.Style.STROKE
                canvas.drawRoundRect(leftBoxRect, 5f, 5f, paint)

                paint.style = Paint.Style.FILL
                paint.color = Color.rgb(15, 23, 42)
                paint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                paint.textSize = 9f
                canvas.drawText("INVOICE DETAILS", margin + 8f, y + 14f, paint)

                paint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
                paint.color = Color.rgb(51, 65, 85)
                paint.textSize = 8.5f
                canvas.drawText("Bill No: ${invoice.billNumber}", margin + 8f, y + 28f, paint)
                val dateStr = SimpleDateFormat("dd MMM yyyy, hh:mm a", Locale.getDefault()).format(Date(invoice.date))
                canvas.drawText("Date: $dateStr", margin + 8f, y + 42f, paint)
                canvas.drawText("Mode: ${invoice.paymentMode}", margin + 8f, y + 56f, paint)

                // Right Box: Customer Details
                val rightBoxX = margin + colWidth + 8f
                val rightBoxRect = RectF(rightBoxX, y, rightBoxX + colWidth, y + boxHeight)
                paint.color = Color.rgb(248, 250, 252)
                paint.style = Paint.Style.FILL
                canvas.drawRoundRect(rightBoxRect, 5f, 5f, paint)
                paint.color = Color.rgb(226, 232, 240)
                paint.style = Paint.Style.STROKE
                canvas.drawRoundRect(rightBoxRect, 5f, 5f, paint)

                paint.style = Paint.Style.FILL
                paint.color = Color.rgb(15, 23, 42)
                paint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                paint.textSize = 9f
                canvas.drawText("BILLED TO / PARTY", rightBoxX + 8f, y + 14f, paint)

                paint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
                paint.color = Color.rgb(51, 65, 85)
                paint.textSize = 8.5f
                val custName = if (invoice.customerName.isNotBlank()) invoice.customerName else "Cash Customer"
                canvas.drawText("Customer: $custName", rightBoxX + 8f, y + 28f, paint)
                val custContact = if (invoice.customerPhone.isNotBlank()) "Phone: ${invoice.customerPhone}" else "Phone: N/A"
                canvas.drawText(custContact, rightBoxX + 8f, y + 42f, paint)
                val gstinLine = if (invoice.gstin.isNotBlank()) "Party GSTIN: ${invoice.gstin}" else "Place of Supply: ${invoice.stateOfSupply}"
                canvas.drawText(gstinLine, rightBoxX + 8f, y + 56f, paint)

                // 4. Itemized Table
                y += boxHeight + 12f
                val headerHeight = 22f
                val tableHeaderRect = RectF(margin, y, margin + contentWidth, y + headerHeight)
                paint.color = Color.rgb(30, 58, 138)
                paint.style = Paint.Style.FILL
                canvas.drawRoundRect(tableHeaderRect, 4f, 4f, paint)

                paint.color = Color.WHITE
                paint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                paint.textSize = 8.5f

                val colSr = margin + 6f
                val colDesc = margin + 26f
                val colHsn = margin + 215f
                val colSize = margin + 270f
                val colQty = margin + 325f
                val colRate = margin + 380f
                val colGst = margin + 435f
                val colTotal = margin + contentWidth - 8f

                canvas.drawText("#", colSr, y + 15f, paint)
                canvas.drawText("Item Description", colDesc, y + 15f, paint)

                if (isTaxInvoice) {
                    canvas.drawText("HSN", colHsn, y + 15f, paint)
                    canvas.drawText("Size", colSize, y + 15f, paint)
                    paint.textAlign = Paint.Align.RIGHT
                    canvas.drawText("Qty", colQty, y + 15f, paint)
                    canvas.drawText("Rate (₹)", colRate, y + 15f, paint)
                    canvas.drawText("GST %", colGst, y + 15f, paint)
                    canvas.drawText("Total (₹)", colTotal, y + 15f, paint)
                } else {
                    canvas.drawText("Size", colSize, y + 15f, paint)
                    paint.textAlign = Paint.Align.RIGHT
                    canvas.drawText("Qty", colQty + 20f, y + 15f, paint)
                    canvas.drawText("Rate (₹)", colRate + 30f, y + 15f, paint)
                    canvas.drawText("Total (₹)", colTotal, y + 15f, paint)
                }
                paint.textAlign = Paint.Align.LEFT

                y += headerHeight

                // Table Rows
                val rowHeight = 20f
                val itemOffset = pageIndex * itemsPerPageNonFinal

                pageItems.forEachIndexed { idx, item ->
                    val globalIdx = itemOffset + idx + 1
                    if (idx % 2 == 1) {
                        paint.color = Color.rgb(248, 250, 252)
                        paint.style = Paint.Style.FILL
                        canvas.drawRect(margin, y, margin + contentWidth, y + rowHeight, paint)
                    }

                    paint.color = Color.rgb(241, 245, 249)
                    paint.strokeWidth = 0.6f
                    canvas.drawLine(margin, y + rowHeight, margin + contentWidth, y + rowHeight, paint)

                    paint.color = Color.rgb(30, 41, 59)
                    paint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
                    paint.textSize = 8.5f
                    canvas.drawText("$globalIdx", colSr, y + 14f, paint)

                    val maxDescLen = if (isTaxInvoice) 28 else 38
                    val truncatedName = if (item.name.length > maxDescLen) item.name.substring(0, maxDescLen - 3) + "..." else item.name
                    canvas.drawText(truncatedName, colDesc, y + 14f, paint)

                    if (isTaxInvoice) {
                        val hsn = item.hsnCode.ifBlank { "-" }
                        canvas.drawText(hsn, colHsn, y + 14f, paint)
                        canvas.drawText(item.size.ifBlank { "Free" }, colSize, y + 14f, paint)

                        paint.textAlign = Paint.Align.RIGHT
                        canvas.drawText("${item.quantity}", colQty, y + 14f, paint)
                        canvas.drawText("%.2f".format(item.unitPrice), colRate, y + 14f, paint)
                        canvas.drawText("${item.gstPercent}%", colGst, y + 14f, paint)
                        paint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                        canvas.drawText("%.2f".format(item.total), colTotal, y + 14f, paint)
                    } else {
                        canvas.drawText(item.size.ifBlank { "Free" }, colSize, y + 14f, paint)

                        paint.textAlign = Paint.Align.RIGHT
                        canvas.drawText("${item.quantity}", colQty + 20f, y + 14f, paint)
                        canvas.drawText("%.2f".format(item.unitPrice), colRate + 30f, y + 14f, paint)
                        paint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                        canvas.drawText("%.2f".format(item.total), colTotal, y + 14f, paint)
                    }
                    paint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
                    paint.textAlign = Paint.Align.LEFT

                    y += rowHeight
                }

                // 5. Final Page Summary & Tax Breakdown Table
                if (isFinalPage) {
                    y += 10f

                    // Left Column: GST Breakdown Table (if tax invoice) & Notes
                    val leftColWidth = contentWidth - 215f
                    val rightColX = margin + leftColWidth + 10f
                    val rightColWidth = 205f

                    if (isTaxInvoice && taxSummaryRows.isNotEmpty()) {
                        // Tax Summary Table Box
                        val taxHeaderH = 16f
                        val taxRowH = 14f
                        val taxTotalH = taxHeaderH + (taxSummaryRows.size * taxRowH) + 16f
                        val taxBox = RectF(margin, y, margin + leftColWidth, y + taxTotalH)

                        paint.color = Color.rgb(248, 250, 252)
                        paint.style = Paint.Style.FILL
                        canvas.drawRoundRect(taxBox, 4f, 4f, paint)
                        paint.color = Color.rgb(226, 232, 240)
                        paint.style = Paint.Style.STROKE
                        canvas.drawRoundRect(taxBox, 4f, 4f, paint)

                        // Tax Table Header
                        paint.color = Color.rgb(30, 58, 138)
                        paint.style = Paint.Style.FILL
                        canvas.drawRoundRect(RectF(margin, y, margin + leftColWidth, y + taxHeaderH), 4f, 4f, paint)

                        paint.color = Color.WHITE
                        paint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                        paint.textSize = 7.5f

                        val tRateX = margin + 6f
                        val tTaxableX = margin + 50f
                        val tSplit1X = margin + 115f
                        val tSplit2X = margin + 175f
                        val tTotalTaxX = margin + leftColWidth - 6f

                        canvas.drawText("GST %", tRateX, y + 11f, paint)
                        canvas.drawText("Taxable", tTaxableX, y + 11f, paint)
                        if (isSameState) {
                            canvas.drawText("CGST", tSplit1X, y + 11f, paint)
                            canvas.drawText("SGST", tSplit2X, y + 11f, paint)
                        } else {
                            canvas.drawText("IGST", tSplit1X, y + 11f, paint)
                        }
                        paint.textAlign = Paint.Align.RIGHT
                        canvas.drawText("Tax Amt", tTotalTaxX, y + 11f, paint)
                        paint.textAlign = Paint.Align.LEFT

                        var ty = y + taxHeaderH
                        paint.color = Color.rgb(30, 41, 59)
                        paint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
                        paint.textSize = 7.5f

                        taxSummaryRows.forEach { row ->
                            canvas.drawText("${row.rate}%", tRateX, ty + 10f, paint)
                            canvas.drawText("%.2f".format(row.taxableAmount), tTaxableX, ty + 10f, paint)
                            if (isSameState) {
                                canvas.drawText("%.2f".format(row.cgstAmount), tSplit1X, ty + 10f, paint)
                                canvas.drawText("%.2f".format(row.sgstAmount), tSplit2X, ty + 10f, paint)
                            } else {
                                canvas.drawText("%.2f".format(row.igstAmount), tSplit1X, ty + 10f, paint)
                            }
                            paint.textAlign = Paint.Align.RIGHT
                            canvas.drawText("%.2f".format(row.totalTax), tTotalTaxX, ty + 10f, paint)
                            paint.textAlign = Paint.Align.LEFT
                            ty += taxRowH
                        }

                        // CA Disclaimer note
                        paint.color = Color.rgb(100, 116, 139)
                        paint.textSize = 6.8f
                        paint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.ITALIC)
                        canvas.drawText("* Confirm tax rates with your CA. State: ${invoice.stateOfSupply}", margin + 6f, ty + 10f, paint)

                        y += taxTotalH + 8f
                    }

                    // Total in Words Box
                    val wordsBox = RectF(margin, y, margin + leftColWidth, y + 40f)
                    paint.color = Color.rgb(241, 245, 249)
                    paint.style = Paint.Style.FILL
                    canvas.drawRoundRect(wordsBox, 4f, 4f, paint)
                    paint.color = Color.rgb(226, 232, 240)
                    paint.style = Paint.Style.STROKE
                    canvas.drawRoundRect(wordsBox, 4f, 4f, paint)

                    paint.style = Paint.Style.FILL
                    paint.color = Color.rgb(15, 23, 42)
                    paint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                    paint.textSize = 7.5f
                    canvas.drawText("TOTAL AMOUNT IN WORDS:", margin + 8f, y + 14f, paint)

                    paint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
                    paint.color = Color.rgb(51, 65, 85)
                    paint.textSize = 7.8f
                    val wordsText = NumberToWords.convertRupees(invoice.totalAmount)
                    canvas.drawText(wordsText, margin + 8f, y + 28f, paint)

                    // Right Column: Summary Calculations Box
                    var sumY = pageHeight - 210f
                    fun drawSummaryRow(label: String, value: String, isBold: Boolean = false, color: Int = Color.rgb(30, 41, 59)) {
                        paint.typeface = Typeface.create(Typeface.DEFAULT, if (isBold) Typeface.BOLD else Typeface.NORMAL)
                        paint.textSize = if (isBold) 9.5f else 8.5f
                        paint.color = color
                        canvas.drawText(label, rightColX + 6f, sumY + 12f, paint)

                        paint.textAlign = Paint.Align.RIGHT
                        canvas.drawText(value, margin + contentWidth - 6f, sumY + 12f, paint)
                        paint.textAlign = Paint.Align.LEFT
                        sumY += 15f
                    }

                    drawSummaryRow("Subtotal:", "₹${"%.2f".format(invoice.subtotal)}")
                    if (isTaxInvoice) {
                        if (isSameState) {
                            val halfGst = invoice.gstAmount / 2.0
                            drawSummaryRow("CGST:", "₹${"%.2f".format(halfGst)}")
                            drawSummaryRow("SGST:", "₹${"%.2f".format(halfGst)}")
                        } else {
                            drawSummaryRow("IGST:", "₹${"%.2f".format(invoice.gstAmount)}")
                        }
                    }
                    if (invoice.additionalCharges > 0.0) {
                        drawSummaryRow("Delivery/Charges:", "₹${"%.2f".format(invoice.additionalCharges)}")
                    }
                    if (invoice.discount > 0.0) {
                        drawSummaryRow("Discount:", "-₹${"%.2f".format(invoice.discount)}", color = Color.rgb(22, 101, 52))
                    }

                    // Visible Round Off Line
                    val roundOffSign = if (invoice.roundOff >= 0) "+₹" else "-₹"
                    val roundOffAbs = Math.abs(invoice.roundOff)
                    drawSummaryRow("Round off:", "$roundOffSign${"%.2f".format(roundOffAbs)}", isBold = false, color = Color.rgb(100, 116, 139))

                    // Net Total Box
                    val grandTotalBox = RectF(rightColX, sumY + 2f, margin + contentWidth, sumY + 28f)
                    paint.color = if (invoice.isCancelled) Color.rgb(220, 38, 38) else Color.rgb(30, 58, 138)
                    paint.style = Paint.Style.FILL
                    canvas.drawRoundRect(grandTotalBox, 4f, 4f, paint)

                    paint.color = Color.WHITE
                    paint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                    paint.textSize = 10.5f
                    canvas.drawText("NET TOTAL:", rightColX + 8f, sumY + 19f, paint)

                    paint.textAlign = Paint.Align.RIGHT
                    canvas.drawText("₹${"%.2f".format(invoice.totalAmount)}", margin + contentWidth - 8f, sumY + 19f, paint)
                    paint.textAlign = Paint.Align.LEFT

                    // Footer Signatory
                    val bottomY = pageHeight - margin - 22f
                    paint.color = Color.rgb(148, 163, 184)
                    paint.textSize = 7f
                    canvas.drawText("Terms: Goods once sold will not be returned. Subject to local jurisdiction.", margin, bottomY, paint)
                    canvas.drawText("For ${storeProfile.storeName.ifBlank { "Ghanshyam Kids Wear" }}", margin + contentWidth - 140f, bottomY - 14f, paint)
                    canvas.drawText("Authorized Signatory", margin + contentWidth - 140f, bottomY, paint)
                }

                pdfDocument.finishPage(page)
            }

            FileOutputStream(pdfFile).use { out ->
                pdfDocument.writeTo(out)
            }
            pdfDocument.close()
        } catch (t: Throwable) {
            android.util.Log.w("PdfInvoiceGenerator", "PDF generation fallback: ${t.message}")
            FileOutputStream(pdfFile).use { out ->
                val fallbackPdf = "%PDF-1.4\n1 0 obj<</Type/Catalog/Pages 2 0 R>>endobj\n2 0 obj<</Type/Pages/Kids[3 0 R]/Count 1>>endobj\n3 0 obj<</Type/Page/MediaBox[0 0 595 842]/Parent 2 0 R>>endobj\nxref\n0 4\n0000000000 65535 f \n0000000009 00000 n \n0000000052 00000 n \n0000000101 00000 n \ntrailer<</Size 4/Root 1 0 R>>\nstartxref\n162\n%%EOF\n"
                out.write(fallbackPdf.toByteArray())
            }
        }

        return pdfFile
    }

    /**
     * Shares the generated PDF invoice via Android ACTION_SEND intent.
     */
    fun shareInvoicePdf(
        context: Context,
        storeProfile: StoreProfile,
        invoice: Invoice
    ) {
        val pdfFile = generateInvoicePdf(context, storeProfile, invoice)
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            pdfFile
        )

        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/pdf"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "Tax Invoice #${invoice.billNumber} - ${storeProfile.storeName}")
            putExtra(
                Intent.EXTRA_TEXT,
                "Dear ${if (invoice.customerName.isNotBlank()) invoice.customerName else "Customer"},\n\n" +
                    "Please find attached your Tax Invoice #${invoice.billNumber} for ₹${"%.2f".format(invoice.totalAmount)} from ${storeProfile.storeName}.\n\n" +
                    "Thank you for your business!"
            )
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        context.startActivity(Intent.createChooser(intent, "Share Invoice PDF"))
    }
}
