package com.example.ui.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import com.example.model.AppLanguage
import com.example.model.Invoice
import com.example.model.StoreProfile
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * One-page A5 bill for a "Naya Bill" (handwritten chit) sale, made to send to the
 * customer on WhatsApp. Labels follow the app language; text is shaped by Android,
 * so Hindi and Gujarati render correctly. The first chit photo is printed below.
 */
object BillPdfGenerator {

    private const val PAGE_W = 420   // A5 in points
    private const val PAGE_H = 595
    private const val M = 28f        // margin

    private val INK = Color.rgb(30, 41, 59)
    private val MUTED = Color.rgb(100, 116, 139)
    private val RED = Color.rgb(220, 38, 38)
    private val GREEN = Color.rgb(22, 163, 74)

    data class BillData(
        val invoice: Invoice,
        val receivedNow: Double,
        /** Whole party balance after this bill; null for walk-in cash customers. */
        val partyTotalDue: Double?,
        val chitPhotoPath: String?
    )

    fun generate(context: Context, store: StoreProfile, data: BillData, lang: AppLanguage): File {
        val inv = data.invoice
        // Owner's PDF design (More > PDF ka design): phone-width page or A5 for printing,
        // and the PDF language (follows the app language unless one is picked there).
        val style = PdfStyle.load(context)
        val pdfLang = style.langFor(lang)
        fun t(key: String) = PdfText.get(key, pdfLang)
        val W = if (style.layout == PdfLayout.PHONE) 360 else PAGE_W
        val H = if (style.layout == PdfLayout.PHONE) 640 else PAGE_H

        val dir = File(context.cacheDir, "invoices").apply { mkdirs() }
        val safeNum = inv.billNumber.replace("[^a-zA-Z0-9_.-]".toRegex(), "_").ifBlank { "draft" }
        val file = File(dir, "Bill_$safeNum.pdf")

        val doc = PdfDocument()
        val page = doc.startPage(PdfDocument.PageInfo.Builder(W, H, 1).create())
        val c = page.canvas
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.color = style.paper.rgb
        c.drawRect(0f, 0f, W.toFloat(), H.toFloat(), p)
        val right = W - M

        // ---- Header band: logo + shop name / address / phone ----
        val headH = 86f
        p.color = style.accent.rgb
        c.drawRect(0f, 0f, W.toFloat(), headH, p)

        var textLeft = M
        loadLogo(context, store.logoUri)?.let { logo ->
            c.drawBitmap(logo, null, RectF(M, 16f, M + 54f, 70f), p)
            textLeft = M + 66f
        }
        val shopName = store.storeName.ifBlank { "Ghanshyam Kids Wear" }
        p.color = Color.WHITE; p.typeface = Typeface.DEFAULT_BOLD; p.textSize = 18f
        c.drawText(fit(shopName, p, right - textLeft), textLeft, 36f, p)
        p.typeface = Typeface.DEFAULT; p.textSize = 9f; p.color = Color.rgb(214, 211, 209)
        var hy = 52f
        val addr = listOf(store.address, store.state).filter { it.isNotBlank() }.joinToString(", ")
        if (addr.isNotBlank()) { c.drawText(fit(addr, p, right - textLeft), textLeft, hy, p); hy += 12f }
        val contact = listOfNotNull(
            store.phone.takeIf { it.isNotBlank() }?.let { "Ph: $it" },
            store.gstin.takeIf { it.isNotBlank() }?.let { "GSTIN: $it" }
        ).joinToString("   ")
        if (contact.isNotBlank()) c.drawText(fit(contact, p, right - textLeft), textLeft, hy, p)

        // ---- Title row: BILL + number + date ----
        var y = headH + 30f
        p.color = INK; p.typeface = Typeface.DEFAULT_BOLD; p.textSize = 16f
        c.drawText(t("pdfb_title"), M, y, p)
        val date = SimpleDateFormat("dd MMM yyyy", Locale.US).format(java.util.Date(inv.date))
        p.textSize = 10f; p.typeface = Typeface.DEFAULT; p.color = MUTED
        p.textAlign = Paint.Align.RIGHT
        c.drawText("${t("pdfb_bill_no")} ${inv.billNumber}", right, y - 12f, p)
        c.drawText("${t("pdfb_date")}: $date", right, y + 2f, p)
        p.textAlign = Paint.Align.LEFT

        // ---- Customer ----
        y += 22f
        p.color = MUTED; p.textSize = 9f
        c.drawText(t("pdfb_customer"), M, y, p)
        y += 15f
        p.color = INK; p.typeface = Typeface.DEFAULT_BOLD; p.textSize = 13f
        c.drawText(fit(inv.customerName, p, right - M), M, y, p)
        val phone = PhoneUtil.toDisplay(inv.customerPhone)
        if (phone.isNotBlank()) {
            y += 14f
            p.typeface = Typeface.DEFAULT; p.textSize = 10f; p.color = MUTED
            c.drawText(phone, M, y, p)
        }

        // ---- Details (the note he typed, e.g. "4 frock + 2 shorts") ----
        val details = inv.items.firstOrNull()?.name
            ?.takeIf { it.isNotBlank() && it != "Handwritten Bill" }
        if (details != null) {
            y += 22f
            p.color = MUTED; p.typeface = Typeface.DEFAULT; p.textSize = 9f
            c.drawText(t("pdfb_details"), M, y, p)
            y += 6f
            val tp = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = INK; textSize = 11f }
            val layout = StaticLayout.Builder
                .obtain(details, 0, details.length, tp, (right - M).toInt())
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setMaxLines(4)
                .build()
            c.save(); c.translate(M, y); layout.draw(c); c.restore()
            y += layout.height
        }

        // ---- Amount box ----
        y += 16f
        val remainder = (inv.totalAmount - data.receivedNow).coerceAtLeast(0.0)
        val rows = mutableListOf<Triple<String, String, Int>>()
        rows += Triple(t("pdfb_total"), rupees(inv.totalAmount), INK)
        if (data.receivedNow > 0.001) {
            val mode = if (inv.paymentMode in listOf("CREDIT", "PARTIAL")) "" else " (${inv.paymentMode})"
            rows += Triple(t("pdfb_received") + mode, rupees(data.receivedNow), GREEN)
        }
        rows += Triple(t("pdfb_this_bill_due"), rupees(remainder), if (remainder > 0.001) RED else GREEN)
        val rowH = 22f
        val boxTop = y
        val boxBottom = boxTop + rows.size * rowH + 10f
        p.style = Paint.Style.STROKE; p.color = style.line; p.strokeWidth = 1f
        c.drawRoundRect(RectF(M, boxTop, right, boxBottom), 6f, 6f, p)
        p.style = Paint.Style.FILL
        var ry = boxTop + 20f
        rows.forEachIndexed { i, (label, value, color) ->
            val last = i == rows.lastIndex
            p.typeface = if (last || i == 0) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            p.textSize = if (last) 12f else 11f
            p.color = INK
            c.drawText(label, M + 10f, ry, p)
            p.color = color; p.textAlign = Paint.Align.RIGHT
            c.drawText(value, right - 10f, ry, p)
            p.textAlign = Paint.Align.LEFT
            ry += rowH
        }
        y = boxBottom

        if (remainder <= 0.001) {
            p.color = GREEN; p.typeface = Typeface.DEFAULT_BOLD; p.textSize = 10f
            c.drawText("✓ ${t("pdfb_paid_full")}", M + 10f, y + 16f, p)
            y += 20f
        }

        // ---- Whole-party balance, due date, UPI ----
        p.typeface = Typeface.DEFAULT; p.textSize = 10f
        data.partyTotalDue?.takeIf { it > 0.001 }?.let { due ->
            y += 18f
            p.color = INK; c.drawText(t("pdfb_total_due"), M, y, p)
            p.color = RED; p.typeface = Typeface.DEFAULT_BOLD; p.textAlign = Paint.Align.RIGHT
            c.drawText(rupees(due), right, y, p)
            p.textAlign = Paint.Align.LEFT; p.typeface = Typeface.DEFAULT
        }
        if (inv.dueDate > 0 && remainder > 0.001) {
            y += 15f
            p.color = INK
            val dueStr = SimpleDateFormat("dd MMM yyyy", Locale.US).format(java.util.Date(inv.dueDate))
            c.drawText("${t("pdfb_due_date")}: $dueStr", M, y, p)
        }
        if (store.upiId.isNotBlank() && remainder > 0.001) {
            y += 15f
            p.color = INK
            c.drawText(fit("${t("pdfb_pay_upi")}: ${store.upiId}", p, right - M), M, y, p)
        }

        // ---- Chit photo fills the space left above the footer ----
        val footerY = H - 22f
        val photo = data.chitPhotoPath?.takeIf { style.photos }?.let { decodeScaled(it, 900) }
        if (photo != null) {
            y += 18f
            p.color = MUTED; p.textSize = 9f; p.typeface = Typeface.DEFAULT
            c.drawText(t("pdfb_chit_photo"), M, y, p)
            y += 6f
            val availH = footerY - 18f - y
            val availW = right - M
            if (availH > 60f) {
                val scale = minOf(availW / photo.width, availH / photo.height)
                val w = photo.width * scale
                val h = photo.height * scale
                val left = M + (availW - w) / 2f
                c.drawBitmap(photo, null, RectF(left, y, left + w, y + h), Paint(Paint.FILTER_BITMAP_FLAG))
                p.style = Paint.Style.STROKE; p.color = style.line
                c.drawRect(left, y, left + w, y + h, p)
                p.style = Paint.Style.FILL
            }
            photo.recycle()
        }

        // ---- Footer ----
        p.color = style.line; c.drawLine(M, footerY - 12f, right, footerY - 12f, p)
        p.color = MUTED; p.textSize = 9f; p.textAlign = Paint.Align.CENTER
        c.drawText(t("pdfb_thanks"), W / 2f, footerY, p)
        p.textAlign = Paint.Align.LEFT

        doc.finishPage(page)
        FileOutputStream(file).use { doc.writeTo(it) }
        doc.close()
        return file
    }

    private fun rupees(v: Double): String = "₹" + String.format(Locale("en", "IN"), "%,.2f", v)

    /** Shortens [text] with "…" so it fits in [maxW]. */
    private fun fit(text: String, p: Paint, maxW: Float): String {
        if (p.measureText(text) <= maxW) return text
        var end = text.length
        while (end > 1 && p.measureText(text.substring(0, end) + "…") > maxW) end--
        return text.substring(0, end) + "…"
    }

    private fun loadLogo(context: Context, logoUri: String): Bitmap? = try {
        if (logoUri.isBlank()) null else {
            val uri = Uri.parse(logoUri)
            val path = if (uri.scheme == null || uri.scheme == "file") uri.path else null
            if (path != null) decodeScaled(path, 160)
            else context.contentResolver.openInputStream(uri)?.use { s ->
                BitmapFactory.decodeStream(s, null, BitmapFactory.Options().apply { inSampleSize = 4 })
            }
        }
    } catch (e: Exception) {
        null
    }

    private fun decodeScaled(path: String, reqPx: Int): Bitmap? = try {
        val f = File(path)
        if (!f.exists()) null else {
            val b = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(path, b)
            var s = 1
            while (maxOf(b.outWidth, b.outHeight) / (s * 2) >= reqPx) s *= 2
            BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = s })
        }
    } catch (e: Exception) {
        null
    }
}
