package com.example.ui.util

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import com.example.model.StoreProfile
import com.example.model.Transaction
import com.example.ui.components.ChallanNo
import com.example.ui.components.EntryDetailMode
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Draws a party statement as a PDF in the owner's chosen [PdfStyle] and [PdfLang].
 *
 * PHONE pages are 360 points wide, about the width of a phone screen, so text shows at
 * full size on WhatsApp without zooming; each entry is a small block instead of a wide
 * table row. A4 keeps a table for printing. Both put the closing balance at the top.
 *
 * An entry with photos gets a small photo icon, like Khatabook's. Tapping it jumps to the
 * full photo near the end of the PDF, and "Back to entry" there jumps back ([PdfLinks]).
 */
object StatementPdf {

    private const val MAX_PHOTOS = 20
    private val RED = Color.rgb(200, 30, 30)
    private val GREEN = Color.rgb(21, 128, 61)
    private val INK = Color.rgb(30, 41, 59)
    private val MUTED = Color.rgb(100, 116, 139)

    private class Dim(
        val w: Int, val h: Int, val m: Float,
        val title: Float, val big: Float, val body: Float, val small: Float
    )

    private val PHONE = Dim(360, 640, 16f, 16f, 20f, 11.5f, 10f)
    private val A4 = Dim(595, 842, 32f, 18f, 20f, 10f, 8.5f)

    fun render(
        statement: StatementResult,
        store: StoreProfile,
        style: PdfStyle,
        lang: PdfLang,
        detailMode: EntryDetailMode,
        photosByTx: Map<String, List<String>>,
        out: File
    ) {
        val d = if (style.layout == PdfLayout.PHONE) PHONE else A4
        val doc = PdfDocument()
        val pen = Pen(doc, d, style, store, statement) { PdfText.get(it, lang) }
        val dateFmt = SimpleDateFormat("dd MMM yyyy", Locale.US)

        // Numbered photos, in the order their entries appear. Only the first MAX_PHOTOS get
        // a page (and so an icon); the rest are counted in a note.
        val photos = mutableListOf<Triple<Int, String, StatementEntry>>()
        val numbersByTx = mutableMapOf<String, List<Int>>()
        var skipped = 0
        if (style.photos) {
            for (e in statement.entries) {
                val paths = photosByTx[e.transaction.id].orEmpty().filter { File(it).exists() }
                val room = (MAX_PHOTOS - photos.size).coerceAtLeast(0)
                skipped += (paths.size - room).coerceAtLeast(0)
                val kept = paths.take(room)
                if (kept.isEmpty()) continue
                numbersByTx[e.transaction.id] = kept.map { path -> (photos.size + 1).also { photos += Triple(it, path, e) } }
            }
        }

        val anyChallan = statement.entries.any { it.transaction.challanNo.isNotBlank() }
        val showChallan = detailMode != EntryDetailMode.NOTE || anyChallan
        val showNote = detailMode != EntryDetailMode.CHALLAN

        pen.firstPageHeader()
        pen.summaryBox()

        if (style.layout == PdfLayout.PHONE) {
            phoneRows(pen, statement, d, style, showChallan, showNote, numbersByTx)
        } else {
            a4Rows(pen, statement, d, style, showChallan, showNote, numbersByTx)
        }
        pen.closingLine()
        if (skipped > 0) pen.note(pen.t("more_photos").replace("{n}", skipped.toString()))

        photos.forEach { (n, path, e) ->
            val count = numbersByTx[e.transaction.id]?.size ?: 1
            val first = numbersByTx[e.transaction.id]?.first() ?: n
            pen.photoPage(n, n - first + 1, count, path, e, dateFmt)
        }

        pen.finish()
        FileOutputStream(out).use { doc.writeTo(it) }
        doc.close()

        // Icon -> photo page, and photo page -> entry. A viewer that ignores links still
        // shows every photo at the end, so a failure here only loses the taps.
        try {
            PdfLinks.addLinks(out, d.h.toFloat(), pen.links())
        } catch (t: Throwable) {
            android.util.Log.w("StatementPdf", "Could not add photo links: ${t.message}")
        }
    }

    // ---------------------------------------------------------------- phone

    private fun phoneRows(
        pen: Pen, s: StatementResult, d: Dim, style: PdfStyle,
        showChallan: Boolean, showNote: Boolean, numbers: Map<String, List<Int>>
    ) {
        val p = pen.p
        val fmt = SimpleDateFormat("dd MMM yyyy", Locale.US)
        if (s.dateRange != StatementDateRange.ALL || Math.abs(s.openingBalance) > 0.009) {
            pen.ensure(30f)
            p.color = style.tint
            pen.c.drawRoundRect(RectF(d.m, pen.y, d.w - d.m, pen.y + 26f), 6f, 6f, p)
            text(pen, pen.t("opening"), d.m + 8f, pen.y + 17f, d.body, INK, bold = true)
            textRight(pen, rupees(s.openingBalance), d.w - d.m - 8f, pen.y + 17f, d.body, INK, bold = true)
            pen.y += 32f
        }
        for (e in s.entries) {
            val tx = e.transaction
            val nums = numbers[tx.id].orEmpty()
            val left = d.m
            val right = d.w - d.m
            val mode = modeLabel(tx)

            val detail = detailText(pen, tx.challanNo, tx.note, showChallan, showNote, tx.type, style.accent.rgb)
            val detailLayout = layout(detail, d.body, right - left, 2, INK)
            val hasFoot = nums.isNotEmpty() || mode.isNotEmpty()
            val h = 18f + detailLayout.height + 4f + 14f + (if (hasFoot) 4f else 0f) + 10f
            pen.ensure(h)
            val top = pen.y
            pen.markEntry(tx.id, top)

            text(pen, fmt.format(Date(tx.date)), left, top + 12f, d.body, INK, bold = true)
            val (amt, color) = if (e.debit > 0) rupees(e.debit) + " " + pen.short("debit") to RED
                               else rupees(e.credit) + " " + pen.short("credit") to GREEN
            textRight(pen, amt, right, top + 12f, d.body + 1.5f, color, bold = true)

            var y = top + 18f
            pen.c.save(); pen.c.translate(left, y); detailLayout.draw(pen.c); pen.c.restore()
            y += detailLayout.height + 4f

            // Bottom line: photo icon and payment mode on the left, running balance on the right.
            val base = y + 11f
            var x = left
            if (nums.isNotEmpty()) x += pen.photoIcon(x, base, nums.first(), nums.size) + 10f
            if (mode.isNotEmpty()) text(pen, mode, x, base, d.small, MUTED)
            textRight(pen, pen.t("balance") + " " + rupees(e.runningBalance), right, base, d.small, MUTED, bold = true)

            pen.y = top + h
            p.color = style.line; p.strokeWidth = 0.8f
            pen.c.drawLine(d.m, pen.y - 5f, right, pen.y - 5f, p)
        }
    }

    // ---------------------------------------------------------------- A4

    private fun a4Rows(
        pen: Pen, s: StatementResult, d: Dim, style: PdfStyle,
        showChallan: Boolean, showNote: Boolean, numbers: Map<String, List<Int>>
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
            text(pen, pen.t("date"), xDate, by, d.body, Color.WHITE, bold = true)
            when {
                showChallan && showNote -> {
                    text(pen, pen.t("challan"), xChallan, by, d.body, Color.WHITE, bold = true)
                    text(pen, pen.t("note"), xNote, by, d.body, Color.WHITE, bold = true)
                }
                showChallan -> text(pen, pen.t("challan"), xChallan, by, d.body, Color.WHITE, bold = true)
                else -> text(pen, pen.t("note"), xChallan, by, d.body, Color.WHITE, bold = true)
            }
            textRight(pen, pen.t("debit"), xDrR, by, d.body, Color.WHITE, bold = true)
            textRight(pen, pen.t("credit"), xCrR, by, d.body, Color.WHITE, bold = true)
            textRight(pen, pen.t("balance"), xBalR, by, d.body, Color.WHITE, bold = true)
            pen.y += 26f
        }
        pen.onNewPage = { header() }
        header()

        if (s.dateRange != StatementDateRange.ALL || Math.abs(s.openingBalance) > 0.009) {
            text(pen, pen.t("opening"), xDate, pen.y + 12f, d.body, INK, bold = true)
            textRight(pen, rupees(s.openingBalance), xBalR, pen.y + 12f, d.body, INK, bold = true)
            pen.y += 20f
        }

        // Both on: a narrow Challan column and a wide Note column.
        // Only one on: a single wide column holding challan and/or note.
        val twoCols = showChallan && showNote
        s.entries.forEachIndexed { i, e ->
            val tx = e.transaction
            val nums = numbers[tx.id].orEmpty()
            val mode = modeLabel(tx)
            val cellLeft = if (twoCols) xNote else xChallan
            val cellText: CharSequence = if (twoCols) {
                tx.note.takeIf { it.isNotBlank() } ?: if (tx.challanNo.isBlank()) defaultText(pen, tx.type) else ""
            } else {
                detailText(pen, tx.challanNo, tx.note, showChallan, showNote, tx.type, style.accent.rgb)
            }
            val noteLayout = cellText.takeIf { it.isNotEmpty() }?.let { layout(it, d.body, noteRight - cellLeft, 3, INK) }
            // Under the date: the photo icon, then the payment mode (each on its own line).
            val dateLines = 1 + (if (nums.isNotEmpty()) 1 else 0) + (if (mode.isNotEmpty()) 1 else 0)
            val rowH = maxOf(24f, (noteLayout?.height?.toFloat() ?: 0f) + 10f, 8f + dateLines * 13f)
            pen.ensure(rowH)
            val top = pen.y
            pen.markEntry(tx.id, top)
            if (i % 2 == 1) {
                pen.p.color = style.tint
                pen.c.drawRect(left, top, right, top + rowH, pen.p)
            }
            val by = top + 14f
            text(pen, SimpleDateFormat("dd/MM/yy", Locale.US).format(Date(tx.date)), xDate, by, d.body, INK)
            var ly = by + 13f
            if (nums.isNotEmpty()) { pen.photoIcon(xDate, ly, nums.first(), nums.size); ly += 13f }
            if (mode.isNotEmpty()) text(pen, mode, xDate, ly, d.small, MUTED)
            if (twoCols && tx.challanNo.isNotBlank()) {
                text(pen, fitText(ChallanNo.label(tx.challanNo), d.body, wChallan - 6f, true), xChallan, by, d.body, style.accent.rgb, bold = true)
            }
            noteLayout?.let { pen.c.save(); pen.c.translate(cellLeft, top + 4f); it.draw(pen.c); pen.c.restore() }
            if (e.debit > 0) textRight(pen, rupees(e.debit), xDrR, by, d.body, RED, bold = true)
            if (e.credit > 0) textRight(pen, rupees(e.credit), xCrR, by, d.body, GREEN, bold = true)
            textRight(pen, rupees(e.runningBalance), xBalR, by, d.body, if (e.runningBalance > 0.009) RED else INK, bold = true)
            pen.y += rowH
        }
        pen.onNewPage = null
    }

    // ---------------------------------------------------------------- shared

    /** Payment mode is only useful on money received, and "CASH" is the default, so it is left out. */
    private fun modeLabel(tx: Transaction): String =
        tx.paymentMode.takeIf { tx.type == "GOT" && it.isNotBlank() && !it.equals("CASH", ignoreCase = true) } ?: ""

    private fun defaultText(pen: Pen, type: String) = pen.t(if (type == "GAVE") "goods_on_credit" else "payment_received")

    /** "CH. NO. 985" in bold accent colour, then the note; a stock phrase when there is neither. */
    private fun detailText(pen: Pen, challanNo: String, note: String, showChallan: Boolean, showNote: Boolean, type: String, accent: Int): CharSequence {
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
        if (sb.isEmpty()) sb.append(defaultText(pen, type))
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
        p.style = Paint.Style.FILL
        pen.c.drawText(t, x, y, p)
    }

    private fun textRight(pen: Pen, t: String, x: Float, y: Float, size: Float, color: Int, bold: Boolean = false) {
        val p = pen.p
        p.textAlign = Paint.Align.RIGHT; p.textSize = size; p.color = color
        p.typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        p.style = Paint.Style.FILL
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

    /** Keeps the page, the drawing position, page breaks and the links in one place. */
    private class Pen(
        val doc: PdfDocument, val d: Dim, val style: PdfStyle,
        val store: StoreProfile, val s: StatementResult,
        val t: (String) -> String
    ) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        var pageNo = 0
        var page: PdfDocument.Page? = null
        lateinit var c: Canvas
        var y = 0f
        var onNewPage: (() -> Unit)? = null
        private val bottom get() = d.h - d.m - 14f

        // Links are recorded while drawing and resolved once every page exists.
        private class Icon(val page: Int, val rect: RectF, val photo: Int)
        private val icons = mutableListOf<Icon>()
        private val entryAt = mutableMapOf<String, Pair<Int, Float>>()
        private val photoPageOf = mutableMapOf<Int, Int>()
        private class Back(val page: Int, val rect: RectF, val txId: String)
        private val backs = mutableListOf<Back>()

        /** "Udhar"/"Jama" are short already; English uses Dr/Cr in tight spots. */
        fun short(key: String): String {
            val word = t(key)
            return if (word == "Debit") "Dr" else if (word == "Credit") "Cr" else word
        }

        fun markEntry(txId: String, top: Float) {
            entryAt[txId] = (pageNo - 1) to top
        }

        fun links(): List<PdfLinks.Link> {
            val out = mutableListOf<PdfLinks.Link>()
            for (i in icons) {
                val target = photoPageOf[i.photo] ?: continue
                out += PdfLinks.Link(i.page, i.rect.left, i.rect.top, i.rect.right, i.rect.bottom, target, 0f)
            }
            for (b in backs) {
                val (pg, top) = entryAt[b.txId] ?: continue
                out += PdfLinks.Link(b.page, b.rect.left, b.rect.top, b.rect.right, b.rect.bottom, pg, (top - 20f).coerceAtLeast(0f))
            }
            return out
        }

        private fun open(continuation: Boolean) {
            page?.let { footer(); doc.finishPage(it) }
            pageNo++
            page = doc.startPage(PdfDocument.PageInfo.Builder(d.w, d.h, pageNo).create())
            c = page!!.canvas
            p.style = Paint.Style.FILL
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
            text(this, t("statement"), d.m, y, d.small, style.accent.rgb, bold = true)
            y += d.title + 2f
            text(this, fitText(s.customer.name, d.title, d.w - 2 * d.m, true), d.m, y, d.title, INK, bold = true)
            y += d.small + 6f
            val phone = PhoneUtil.toDisplay(s.customer.phone)
            val range = t(when (s.dateRange) {
                StatementDateRange.ALL -> "range_all"
                StatementDateRange.THIS_MONTH -> "range_month"
                StatementDateRange.THREE_MONTHS -> "range_3m"
            })
            val filter = t(if (s.filter == StatementFilter.PENDING_BILLS_ONLY) "filter_pending" else "filter_all")
            val sub = listOf(phone, "$range • $filter").filter { it.isNotBlank() }.joinToString("   ")
            text(this, fitText(sub, d.small, d.w - 2 * d.m, false), d.m, y, d.small, MUTED)
            y += 14f
        }

        fun summaryBox() {
            val bal = s.closingBalance
            val h = d.big + 46f
            p.color = style.tint
            c.drawRoundRect(RectF(d.m, y, d.w - d.m, y + h), 8f, 8f, p)
            val (label, color) = when {
                bal > 0.009 -> t("balance_due") to RED
                bal < -0.009 -> t("balance_payable") to GREEN
                else -> t("all_settled") to INK
            }
            text(this, label, d.m + 12f, y + 18f, d.small, MUTED, bold = true)
            text(this, rupees(Math.abs(bal)), d.m + 12f, y + 20f + d.big, d.big, color, bold = true)
            text(this, "${t("debit")} ${rupees(s.totalDebit)}   •   ${t("credit")} ${rupees(s.totalCredit)}", d.m + 12f, y + h - 9f, d.small, MUTED)
            y += h + 14f
        }

        fun closingLine() {
            ensure(30f)
            y += 6f
            p.color = style.accent.rgb; p.strokeWidth = 1.2f
            c.drawLine(d.m, y, d.w - d.m, y, p)
            y += 18f
            text(this, t("closing"), d.m, y, d.body, INK, bold = true)
            textRight(this, rupees(s.closingBalance), d.w - d.m, y, d.body + 1f, if (s.closingBalance > 0.009) RED else INK, bold = true)
            y += 12f
        }

        fun note(msg: String) {
            ensure(20f)
            text(this, msg, d.m, y + 12f, d.small, MUTED)
            y += 18f
        }

        /** Makes room for [h] points, starting a new page (with its table header) if needed. */
        fun ensure(h: Float) {
            if (y + h <= bottom) return
            open(continuation = true)
            onNewPage?.invoke()
        }

        /**
         * A small picture icon and "Photo" (or "3 Photo") in the accent colour, with its
         * baseline at [base]. Records a link to photo [number]. Returns the drawn width.
         */
        fun photoIcon(x: Float, base: Float, number: Int, count: Int): Float {
            val s = d.small
            val iw = s * 1.5f; val ih = s * 1.15f
            val top = base - ih + 1f
            val box = RectF(x, top, x + iw, top + ih)
            p.color = style.accent.rgb
            p.style = Paint.Style.STROKE; p.strokeWidth = 1f
            c.drawRoundRect(box, 2f, 2f, p)
            p.style = Paint.Style.FILL
            c.drawCircle(box.right - iw * 0.28f, box.top + ih * 0.3f, ih * 0.11f, p)
            val hill = Path().apply {
                moveTo(box.left + 1f, box.bottom - 1f)
                lineTo(box.left + iw * 0.38f, box.top + ih * 0.42f)
                lineTo(box.left + iw * 0.6f, box.bottom - ih * 0.3f)
                lineTo(box.left + iw * 0.72f, box.top + ih * 0.58f)
                lineTo(box.right - 1f, box.bottom - 1f)
                close()
            }
            c.drawPath(hill, p)
            val label = (if (count > 1) "$count " else "") + t("photo")
            p.textSize = s; p.typeface = Typeface.DEFAULT_BOLD; p.textAlign = Paint.Align.LEFT
            p.isUnderlineText = true
            c.drawText(label, box.right + 4f, base, p)
            p.isUnderlineText = false
            val w = iw + 4f + p.measureText(label)
            // A finger-sized tap area around the icon and its word.
            icons += Icon(pageNo - 1, RectF(x - 4f, top - 6f, x + w + 4f, base + 6f), number)
            return w
        }

        fun photoPage(n: Int, nth: Int, count: Int, path: String, e: StatementEntry, fmt: SimpleDateFormat) {
            val bmp = decode(path, if (d === PHONE) 900 else 1200) ?: return
            open(continuation = true)
            photoPageOf[n] = pageNo - 1
            val tx = e.transaction
            val amount = if (e.debit > 0) rupees(e.debit) + " " + short("debit") else rupees(e.credit) + " " + short("credit")
            val head = t("photo") + if (count > 1) " $nth/$count" else ""
            val cap = listOf(head, fmt.format(Date(tx.date)), ChallanNo.label(tx.challanNo), amount)
                .filter { it.isNotBlank() }.joinToString("  •  ")
            text(this, fitText(cap, d.body, d.w - 2 * d.m, true), d.m, y + 8f, d.body, INK, bold = true)
            y += 16f

            // "← Back to entry" button.
            val label = "←  " + t("back_to_entry")
            p.textSize = d.body; p.typeface = Typeface.DEFAULT_BOLD
            val bw = p.measureText(label) + 20f
            val btn = RectF(d.m, y, d.m + bw, y + d.body + 12f)
            p.color = style.accent.rgb; p.style = Paint.Style.FILL
            c.drawRoundRect(btn, 6f, 6f, p)
            text(this, label, btn.left + 10f, btn.bottom - 6f - d.body * 0.15f, d.body, Color.WHITE, bold = true)
            backs += Back(pageNo - 1, RectF(btn.left - 4f, btn.top - 4f, btn.right + 4f, btn.bottom + 4f), tx.id)
            y = btn.bottom + 10f

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
            p.style = Paint.Style.FILL
            p.textAlign = Paint.Align.CENTER; p.textSize = d.small; p.color = MUTED; p.typeface = Typeface.DEFAULT
            c.drawText("${t("page")} $pageNo", d.w / 2f, d.h - d.m / 2f, p)
            p.textAlign = Paint.Align.LEFT
        }

        fun finish() {
            page?.let { footer(); doc.finishPage(it) }
            page = null
        }
    }
}
