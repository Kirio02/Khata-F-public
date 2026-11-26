package com.example.ui.util

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import com.example.model.StoreProfile
import com.example.ui.components.ChallanNo
import com.example.ui.components.EntryDetailMode
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Draws a party statement as a PDF in the owner's chosen [PdfStyle].
 *
 * PHONE pages are 360 points wide, about the width of a phone screen, so text shows at
 * full size on WhatsApp without zooming; each entry is a small block instead of a wide
 * table row. A4 keeps a table for printing. Both put the closing balance at the top.
 *
 * Entry photos appear as numbered thumbnails beside the entry and, if photos are on,
 * full size at the end with the same number. (Android's PDF writer cannot make a tap
 * open a picture, so the number is how a reader finds the big one.)
 */
object StatementPdf {

    private const val MAX_PHOTOS = 20
    private val RED = Color.rgb(200, 30, 30)
    private val GREEN = Color.rgb(21, 128, 61)
    private val INK = Color.rgb(30, 41, 59)
    private val MUTED = Color.rgb(100, 116, 139)

    private class Dim(
        val w: Int, val h: Int, val m: Float,
        val title: Float, val big: Float, val body: Float, val small: Float, val thumb: Float
    )

    private val PHONE = Dim(360, 640, 16f, 16f, 20f, 11.5f, 10f, 46f)
    private val A4 = Dim(595, 842, 32f, 18f, 20f, 10f, 8.5f, 30f)

    fun render(
        statement: StatementResult,
        store: StoreProfile,
        style: PdfStyle,
        detailMode: EntryDetailMode,
        photosByTx: Map<String, List<String>>,
        out: File
    ) {
        val d = if (style.layout == PdfLayout.PHONE) PHONE else A4
        val doc = PdfDocument()
        val pen = Pen(doc, d, style, store, statement)
        val dateFmt = SimpleDateFormat("dd MMM yyyy", Locale.US)

        // Numbered photos, in the order their entries appear.
        val photos = mutableListOf<Triple<Int, String, StatementEntry>>()
        val numbersByTx = mutableMapOf<String, List<Int>>()
        if (style.photos) {
            for (e in statement.entries) {
                val paths = photosByTx[e.transaction.id].orEmpty().filter { File(it).exists() }
                if (paths.isEmpty()) continue
                val nums = paths.map { path -> (photos.size + 1).also { photos += Triple(it, path, e) } }
                numbersByTx[e.transaction.id] = nums
            }
        }

        val anyChallan = statement.entries.any { it.transaction.challanNo.isNotBlank() }
        val showChallan = detailMode != EntryDetailMode.NOTE || anyChallan
        val showNote = detailMode != EntryDetailMode.CHALLAN

        pen.firstPageHeader()
        pen.summaryBox()

        if (style.layout == PdfLayout.PHONE) {
            phoneRows(pen, statement, d, style, dateFmt, showChallan, showNote, numbersByTx, photosByTx)
        } else {
            a4Rows(pen, statement, d, style, dateFmt, showChallan, showNote, numbersByTx, photosByTx)
        }
        pen.closingLine()

        photos.take(MAX_PHOTOS).forEach { (n, path, e) -> pen.photoPage(n, path, e, dateFmt) }
        if (photos.size > MAX_PHOTOS) pen.note("+${photos.size - MAX_PHOTOS} more photos are in the app.")

        pen.finish()
        FileOutputStream(out).use { doc.writeTo(it) }
        doc.close()
    }

    // ---------------------------------------------------------------- phone

    private fun phoneRows(
        pen: Pen, s: StatementResult, d: Dim, style: PdfStyle, fmt: SimpleDateFormat,
        showChallan: Boolean, showNote: Boolean,
        numbers: Map<String, List<Int>>, photosByTx: Map<String, List<String>>
    ) {
        val p = pen.p
        if (s.dateRange != StatementDateRange.ALL || Math.abs(s.openingBalance) > 0.009) {
            pen.ensure(30f)
            p.color = style.tint
            pen.c.drawRoundRect(RectF(d.m, pen.y, d.w - d.m, pen.y + 26f), 6f, 6f, p)
            text(pen, "Opening Balance", d.m + 8f, pen.y + 17f, d.body, INK, bold = true)
            textRight(pen, rupees(s.openingBalance), d.w - d.m - 8f, pen.y + 17f, d.body, INK, bold = true)
            pen.y += 32f
        }
        for (e in s.entries) {
            val tx = e.transaction
            val nums = numbers[tx.id].orEmpty()
            val hasThumb = nums.isNotEmpty()
            val left = d.m + if (hasThumb) d.thumb + 8f else 0f
            val right = d.w - d.m

            val detail = detailText(tx.challanNo, tx.note, showChallan, showNote, tx.type, style.accent.rgb)
            val detailLayout = detail?.let { layout(it, d.body, right - left, 2, INK) }
            val h = maxOf(
                (if (hasThumb) d.thumb + 6f else 0f),
                16f + (detailLayout?.height?.toFloat()?.plus(4f) ?: 0f) + 14f
            ) + 10f
            pen.ensure(h)
            val top = pen.y

            if (hasThumb) pen.thumb(photosByTx[tx.id]!!.first { File(it).exists() }, nums.first(), d.m, top, d.thumb, nums.size)

            text(pen, fmt.format(Date(tx.date)), left, top + 12f, d.body, INK, bold = true)
            val (amt, color) = if (e.debit > 0) rupees(e.debit) + " Dr" to RED else rupees(e.credit) + " Cr" to GREEN
            textRight(pen, amt, right, top + 12f, d.body + 1.5f, color, bold = true)

            var y = top + 18f
            detailLayout?.let {
                pen.c.save(); pen.c.translate(left, y); it.draw(pen.c); pen.c.restore()
                y += it.height + 4f
            }
            text(pen, tx.paymentMode, left, y + 9f, d.small, MUTED)
            textRight(pen, "Bal " + rupees(e.runningBalance), right, y + 9f, d.small, MUTED, bold = true)

            pen.y = top + h
            p.color = style.line; p.strokeWidth = 0.8f
            pen.c.drawLine(d.m, pen.y - 5f, right, pen.y - 5f, p)
        }
    }

    // ---------------------------------------------------------------- A4

    private fun a4Rows(
        pen: Pen, s: StatementResult, d: Dim, style: PdfStyle, fmt: SimpleDateFormat,
        showChallan: Boolean, showNote: Boolean,
        numbers: Map<String, List<Int>>, photosByTx: Map<String, List<String>>
    ) {
        val left = d.m
        val right = d.w - d.m
        val wDate = 66f; val wChallan = 80f; val wMoney = 74f; val wBal = 84f
        val xDate = left + 6f
        val xChallan = left + wDate
        val xNote = xChallan + if (showChallan) wChallan else 0f
        val xBalR = right - 6f
        val xCrR = xBalR - wBal
        val xDrR = xCrR - wMoney
        val noteRight = xDrR - wMoney + 4f

        fun header() {
            pen.p.color = style.accent.rgb
            pen.c.drawRoundRect(RectF(left, pen.y, right, pen.y + 22f), 4f, 4f, pen.p)
            val by = pen.y + 15f
            text(pen, "Date", xDate, by, d.body, Color.WHITE, bold = true)
            when {
                showChallan && showNote -> {
                    text(pen, "Challan No", xChallan, by, d.body, Color.WHITE, bold = true)
                    text(pen, "Note", xNote, by, d.body, Color.WHITE, bold = true)
                }
                showChallan -> text(pen, "Challan No", xChallan, by, d.body, Color.WHITE, bold = true)
                else -> text(pen, "Note", xChallan, by, d.body, Color.WHITE, bold = true)
            }
            textRight(pen, "Debit", xDrR, by, d.body, Color.WHITE, bold = true)
            textRight(pen, "Credit", xCrR, by, d.body, Color.WHITE, bold = true)
            textRight(pen, "Balance", xBalR, by, d.body, Color.WHITE, bold = true)
            pen.y += 26f
        }
        pen.onNewPage = { header() }
        header()

        if (s.dateRange != StatementDateRange.ALL || Math.abs(s.openingBalance) > 0.009) {
            text(pen, "Opening Balance", xDate, pen.y + 12f, d.body, INK, bold = true)
            textRight(pen, rupees(s.openingBalance), xBalR, pen.y + 12f, d.body, INK, bold = true)
            pen.y += 20f
        }

        // Both on: a narrow Challan column and a wide Note column (photo goes with the note).
        // Only one on: a single wide column holding the photo, then challan and/or note.
        val twoCols = showChallan && showNote
        s.entries.forEachIndexed { i, e ->
            val tx = e.transaction
            val nums = numbers[tx.id].orEmpty()
            val hasThumb = nums.isNotEmpty()
            val cellLeft = if (twoCols) xNote else xChallan
            val textLeft = cellLeft + if (hasThumb) d.thumb + 6f else 0f
            val cellText: CharSequence = if (twoCols) {
                tx.note.takeIf { it.isNotBlank() } ?: if (tx.challanNo.isBlank()) defaultText(tx.type) else ""
            } else {
                detailText(tx.challanNo, tx.note, showChallan, showNote, tx.type, style.accent.rgb)
            }
            val noteLayout = cellText.takeIf { it.isNotEmpty() }?.let { layout(it, d.body, noteRight - textLeft, 3, INK) }
            val rowH = maxOf(30f, (noteLayout?.height?.toFloat() ?: 0f) + 10f, if (hasThumb) d.thumb + 6f else 0f)
            pen.ensure(rowH)
            val top = pen.y
            if (i % 2 == 1) {
                pen.p.color = style.tint
                pen.c.drawRect(left, top, right, top + rowH, pen.p)
            }
            val by = top + 14f
            text(pen, SimpleDateFormat("dd/MM/yy", Locale.US).format(Date(tx.date)), xDate, by, d.body, INK)
            text(pen, tx.paymentMode, xDate, by + 12f, d.small, MUTED)
            if (twoCols && tx.challanNo.isNotBlank()) {
                text(pen, fitText(ChallanNo.label(tx.challanNo), d.body, wChallan - 6f, true), xChallan, by, d.body, style.accent.rgb, bold = true)
            }
            if (hasThumb) pen.thumb(photosByTx[tx.id]!!.first { File(it).exists() }, nums.first(), cellLeft, top + 3f, d.thumb, nums.size)
            noteLayout?.let { pen.c.save(); pen.c.translate(textLeft, top + 4f); it.draw(pen.c); pen.c.restore() }
            if (e.debit > 0) textRight(pen, rupees(e.debit), xDrR, by, d.body, RED, bold = true)
            if (e.credit > 0) textRight(pen, rupees(e.credit), xCrR, by, d.body, GREEN, bold = true)
            textRight(pen, rupees(e.runningBalance), xBalR, by, d.body, if (e.runningBalance > 0.009) RED else INK, bold = true)
            pen.y += rowH
        }
        pen.onNewPage = null
    }

    // ---------------------------------------------------------------- shared

    private fun defaultText(type: String) = if (type == "GAVE") "Goods on credit" else "Payment received"

    /** "CH. NO. 985" in bold accent colour, then the note; a stock phrase when there is neither. */
    private fun detailText(challanNo: String, note: String, showChallan: Boolean, showNote: Boolean, type: String, accent: Int): CharSequence {
        val sb = android.text.SpannableStringBuilder()
        if (showChallan && challanNo.isNotBlank()) {
            val label = ChallanNo.label(challanNo)
            sb.append(label)
            sb.setSpan(android.text.style.StyleSpan(Typeface.BOLD), 0, label.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            sb.setSpan(android.text.style.ForegroundColorSpan(accent), 0, label.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        if (note.isNotBlank() && (showNote || sb.isEmpty())) {
            if (sb.isNotEmpty()) sb.append("  •  ")
            sb.append(note)
        }
        if (sb.isEmpty()) sb.append(defaultText(type))
        return sb
    }

    private fun layout(t: CharSequence, size: Float, width: Float, maxLines: Int, color: Int): StaticLayout {
        val tp = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = size; this.color = color }
        return StaticLayout.Builder.obtain(t, 0, t.length, tp, width.toInt().coerceAtLeast(20))
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setMaxLines(maxLines)
            .setEllipsize(TextUtils.TruncateAt.END)
            .build()
    }

    private fun text(pen: Pen, t: String, x: Float, y: Float, size: Float, color: Int, bold: Boolean = false) {
        val p = pen.p
        p.textAlign = Paint.Align.LEFT; p.textSize = size; p.color = color
        p.typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        pen.c.drawText(t, x, y, p)
    }

    private fun textRight(pen: Pen, t: String, x: Float, y: Float, size: Float, color: Int, bold: Boolean = false) {
        val p = pen.p
        p.textAlign = Paint.Align.RIGHT; p.textSize = size; p.color = color
        p.typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        pen.c.drawText(t, x, y, p)
        p.textAlign = Paint.Align.LEFT
    }

    private fun fitText(t: String, size: Float, maxW: Float, bold: Boolean): String {
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = size; typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT }
        if (p.measureText(t) <= maxW) return t
        var end = t.length
        while (end > 1 && p.measureText(t.substring(0, end) + "…") > maxW) end--
        return t.substring(0, end) + "…"
    }

    fun rupees(v: Double): String = "₹" + String.format(Locale("en", "IN"), "%,.2f", v)

    private fun decode(path: String, maxPx: Int): Bitmap? = try {
        val b = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, b)
        var s = 1
        while (maxOf(b.outWidth, b.outHeight) / (s * 2) >= maxPx) s *= 2
        BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = s })
    } catch (e: Exception) {
        null
    }

    /** Keeps the page, the drawing position and page breaks in one place. */
    private class Pen(
        val doc: PdfDocument, val d: Dim, val style: PdfStyle,
        val store: StoreProfile, val s: StatementResult
    ) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        var pageNo = 0
        var page: PdfDocument.Page? = null
        lateinit var c: Canvas
        var y = 0f
        var onNewPage: (() -> Unit)? = null
        private val bottom get() = d.h - d.m - 14f

        private fun open(continuation: Boolean) {
            page?.let { footer(); doc.finishPage(it) }
            pageNo++
            page = doc.startPage(PdfDocument.PageInfo.Builder(d.w, d.h, pageNo).create())
            c = page!!.canvas
            p.color = style.paper.rgb
            c.drawRect(0f, 0f, d.w.toFloat(), d.h.toFloat(), p)
            if (continuation) {
                p.color = style.accent.rgb
                c.drawRect(0f, 0f, d.w.toFloat(), 26f, p)
                text(this, fitText("${shopName()}  •  ${s.customer.name}", d.small, d.w - 2 * d.m, true), d.m, 17f, d.small, Color.WHITE, bold = true)
                y = 38f
            }
        }

        fun shopName() = store.storeName.ifBlank { "Ghanshyam Kids Wear" }

        fun firstPageHeader() {
            open(continuation = false)
            val contact = listOfNotNull(
                store.phone.takeIf { it.isNotBlank() }?.let { "Ph: $it" },
                store.upiId.takeIf { it.isNotBlank() }?.let { "UPI: $it" },
                store.gstin.takeIf { it.isNotBlank() }?.let { "GSTIN: $it" }
            ).joinToString("   ")
            val band = (if (d === PHONE) 64f else 72f) - if (contact.isBlank()) 18f else 0f
            p.color = style.accent.rgb
            c.drawRect(0f, 0f, d.w.toFloat(), band, p)
            text(this, fitText(shopName(), d.title, d.w - 2 * d.m, true), d.m, 28f, d.title, Color.WHITE, bold = true)
            if (contact.isNotBlank()) {
                text(this, fitText(contact, d.small, d.w - 2 * d.m, false), d.m, 46f, d.small, PdfStyle.mix(Color.WHITE, style.accent.rgb, 0.8f))
            }
            y = band + 22f
            text(this, "ACCOUNT STATEMENT", d.m, y, d.small, style.accent.rgb, bold = true)
            y += d.title + 2f
            text(this, fitText(s.customer.name, d.title, d.w - 2 * d.m, true), d.m, y, d.title, INK, bold = true)
            y += d.small + 6f
            val phone = PhoneUtil.toDisplay(s.customer.phone)
            val sub = listOf(phone, "${s.dateRange.label} • ${s.filter.label}").filter { it.isNotBlank() }.joinToString("   ")
            text(this, fitText(sub, d.small, d.w - 2 * d.m, false), d.m, y, d.small, MUTED)
            y += 14f
        }

        fun summaryBox() {
            val bal = s.closingBalance
            val h = d.big + 46f
            p.color = style.tint
            c.drawRoundRect(RectF(d.m, y, d.w - d.m, y + h), 8f, 8f, p)
            val (label, color) = when {
                bal > 0.009 -> "Balance Due" to RED
                bal < -0.009 -> "Balance Payable" to GREEN
                else -> "All Settled" to INK
            }
            text(this, label, d.m + 12f, y + 18f, d.small, MUTED, bold = true)
            text(this, rupees(Math.abs(bal)), d.m + 12f, y + 20f + d.big, d.big, color, bold = true)
            text(this, "Debit ${rupees(s.totalDebit)}   •   Credit ${rupees(s.totalCredit)}", d.m + 12f, y + h - 9f, d.small, MUTED)
            y += h + 14f
        }

        fun closingLine() {
            ensure(30f)
            y += 6f
            p.color = style.accent.rgb; p.strokeWidth = 1.2f
            c.drawLine(d.m, y, d.w - d.m, y, p)
            y += 18f
            text(this, "Closing Balance", d.m, y, d.body, INK, bold = true)
            textRight(this, rupees(s.closingBalance), d.w - d.m, y, d.body + 1f, if (s.closingBalance > 0.009) RED else INK, bold = true)
            y += 12f
        }

        fun note(t: String) {
            ensure(20f)
            text(this, t, d.m, y + 12f, d.small, MUTED)
            y += 18f
        }

        /** Makes room for [h] points, starting a new page (with its table header) if needed. */
        fun ensure(h: Float) {
            if (y + h <= bottom) return
            open(continuation = true)
            onNewPage?.invoke()
        }

        fun thumb(path: String, number: Int, x: Float, top: Float, size: Float, count: Int) {
            val bmp = decode(path, 160) ?: return
            val side = minOf(bmp.width, bmp.height)
            val src = Rect((bmp.width - side) / 2, (bmp.height - side) / 2, (bmp.width + side) / 2, (bmp.height + side) / 2)
            val dst = RectF(x, top, x + size, top + size)
            c.save()
            val clip = android.graphics.Path().apply { addRoundRect(dst, 5f, 5f, android.graphics.Path.Direction.CW) }
            c.clipPath(clip)
            c.drawBitmap(bmp, src, dst, Paint(Paint.FILTER_BITMAP_FLAG))
            c.restore()
            bmp.recycle()
            // Number badge, matching the full-size photo at the end.
            val r = if (d === PHONE) 9f else 7f
            p.color = style.accent.rgb
            c.drawCircle(x + size - r + 2f, top + size - r + 2f, r, p)
            p.textAlign = Paint.Align.CENTER; p.color = Color.WHITE; p.typeface = Typeface.DEFAULT_BOLD
            p.textSize = r * 1.15f
            val label = if (count > 1) "$number+" else "$number"
            c.drawText(label, x + size - r + 2f, top + size - r + 2f + r * 0.42f, p)
            p.textAlign = Paint.Align.LEFT
        }

        fun photoPage(n: Int, path: String, e: StatementEntry, fmt: SimpleDateFormat) {
            val bmp = decode(path, if (d === PHONE) 900 else 1200) ?: return
            open(continuation = true)
            val tx = e.transaction
            val amount = if (e.debit > 0) rupees(e.debit) + " Dr" else rupees(e.credit) + " Cr"
            val cap = listOf("Photo $n", fmt.format(Date(tx.date)), ChallanNo.label(tx.challanNo), amount)
                .filter { it.isNotBlank() }.joinToString("  •  ")
            text(this, fitText(cap, d.body, d.w - 2 * d.m, true), d.m, y + 8f, d.body, INK, bold = true)
            y += 18f
            val availW = d.w - 2 * d.m
            val availH = bottom - y
            val scale = minOf(availW / bmp.width, availH / bmp.height)
            val w = bmp.width * scale; val h = bmp.height * scale
            val l = d.m + (availW - w) / 2f
            c.drawBitmap(bmp, null, RectF(l, y, l + w, y + h), Paint(Paint.FILTER_BITMAP_FLAG))
            bmp.recycle()
            y = bottom
        }

        private fun footer() {
            p.textAlign = Paint.Align.CENTER; p.textSize = d.small; p.color = MUTED; p.typeface = Typeface.DEFAULT
            c.drawText("Page $pageNo", d.w / 2f, d.h - d.m / 2f, p)
            p.textAlign = Paint.Align.LEFT
        }

        fun finish() {
            page?.let { footer(); doc.finishPage(it) }
            page = null
        }
    }
}
