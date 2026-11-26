package com.example.ui.util

import android.content.Context
import android.graphics.Color

/** PHONE: narrow pages with big text, read on WhatsApp without zooming. A4: for printing. */
enum class PdfLayout { PHONE, A4 }

/** Header and table colour of the statement and bill PDFs. */
enum class PdfAccent(val rgb: Int, val labelKey: String) {
    BLUE(Color.rgb(30, 58, 138), "pdf_color_blue"),
    MAROON(Color.rgb(127, 29, 29), "pdf_color_maroon"),
    GREEN(Color.rgb(20, 83, 45), "pdf_color_green"),
    BLACK(Color.rgb(41, 37, 36), "pdf_color_black")
}

/** Page colour. Both stay light so the PDF prints well and red/green amounts stay readable. */
enum class PdfPaper(val rgb: Int, val labelKey: String) {
    WHITE(Color.WHITE, "pdf_paper_white"),
    CREAM(Color.rgb(253, 248, 236), "pdf_paper_cream")
}

data class PdfStyle(
    val layout: PdfLayout = PdfLayout.PHONE,
    val accent: PdfAccent = PdfAccent.BLUE,
    val paper: PdfPaper = PdfPaper.WHITE,
    val photos: Boolean = true,
    /** PDF words' language; null = same as the app language. */
    val lang: PdfLang? = null
) {
    fun langFor(app: com.example.model.AppLanguage): PdfLang = lang ?: PdfLang.from(app)

    /** A pale tint of the accent for boxes and alternate rows, mixed over the paper colour. */
    val tint: Int get() = mix(accent.rgb, paper.rgb, 0.08f)
    val line: Int get() = mix(accent.rgb, paper.rgb, 0.18f)

    companion object {
        private const val PREFS = "ghanshyam_billing_prefs"

        fun load(context: Context): PdfStyle {
            val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            return PdfStyle(
                layout = PdfLayout.entries.firstOrNull { it.name == p.getString("pdf_layout", null) } ?: PdfLayout.PHONE,
                accent = PdfAccent.entries.firstOrNull { it.name == p.getString("pdf_accent", null) } ?: PdfAccent.BLUE,
                paper = PdfPaper.entries.firstOrNull { it.name == p.getString("pdf_paper", null) } ?: PdfPaper.WHITE,
                photos = p.getBoolean("pdf_photos", true),
                lang = PdfLang.entries.firstOrNull { it.name == p.getString("pdf_lang", null) }
            )
        }

        fun save(context: Context, style: PdfStyle) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString("pdf_layout", style.layout.name)
                .putString("pdf_accent", style.accent.name)
                .putString("pdf_paper", style.paper.name)
                .putBoolean("pdf_photos", style.photos)
                .apply { if (style.lang != null) putString("pdf_lang", style.lang.name) else remove("pdf_lang") }
                .apply()
        }

        /** [amount] of [fg] over [bg]. */
        fun mix(fg: Int, bg: Int, amount: Float): Int = Color.rgb(
            (Color.red(fg) * amount + Color.red(bg) * (1 - amount)).toInt(),
            (Color.green(fg) * amount + Color.green(bg) * (1 - amount)).toInt(),
            (Color.blue(fg) * amount + Color.blue(bg) * (1 - amount)).toInt()
        )
    }
}
