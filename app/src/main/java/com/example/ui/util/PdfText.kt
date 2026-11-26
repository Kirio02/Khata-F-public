package com.example.ui.util

import com.example.model.AppLanguage

/**
 * Language of the statement and bill PDFs, chosen in More > PDF ka Design. It is separate
 * from the app language so the owner can send Gujlish (Gujarati words in English letters)
 * PDFs, which the app itself does not have. Unset = follow the app language.
 */
enum class PdfLang(val label: String) {
    ENGLISH("English"),
    HINGLISH("Hinglish"),
    HINDI("हिंदी"),
    GUJLISH("Gujlish"),
    GUJARATI("ગુજરાતી");

    companion object {
        fun from(app: AppLanguage): PdfLang = when (app) {
            AppLanguage.HINGLISH -> HINGLISH
            AppLanguage.HINDI -> HINDI
            AppLanguage.GUJARATI -> GUJARATI
            AppLanguage.ENGLISH -> ENGLISH
        }
    }
}

/** Words printed in the PDFs. Order in each row: English, Hinglish, Hindi, Gujlish, Gujarati. */
object PdfText {

    private class W(val en: String, val hing: String, val hi: String, val gujl: String, val gu: String)

    private val words = mapOf(
        // Statement
        "statement" to W("ACCOUNT STATEMENT", "KHATA STATEMENT", "खाता विवरण", "KHATA STATEMENT", "ખાતાનું સ્ટેટમેન્ટ"),
        "balance_due" to W("Balance Due", "Lene Baaki", "लेने बाकी", "Levana Baaki", "લેવાના બાકી"),
        "balance_payable" to W("Balance Payable", "Dene Baaki", "देने बाकी", "Aapvana Baaki", "આપવાના બાકી"),
        "all_settled" to W("All Settled", "Hisaab Barabar", "हिसाब बराबर", "Hisaab Sarbhar", "હિસાબ સરભર"),
        "debit" to W("Debit", "Udhar", "उधार", "Udhar", "ઉધાર"),
        "credit" to W("Credit", "Jama", "जमा", "Jama", "જમા"),
        "balance" to W("Balance", "Baaki", "बाकी", "Baaki", "બાકી"),
        "date" to W("Date", "Tarikh", "तारीख", "Tarikh", "તારીખ"),
        "challan" to W("Challan No", "Challan No", "चालान नं.", "Challan No", "ચલણ નં."),
        "note" to W("Note", "Note", "नोट", "Nondh", "નોંધ"),
        "opening" to W("Opening Balance", "Shuruaati Baaki", "शुरुआती बाकी", "Sharuaat ni Baaki", "શરૂઆતની બાકી"),
        "closing" to W("Closing Balance", "Aakhri Baaki", "आखिरी बाकी", "Chhelli Baaki", "છેલ્લી બાકી"),
        "goods_on_credit" to W("Goods on credit", "Maal udhar diya", "माल उधार दिया", "Maal udhar aapyo", "માલ ઉધાર આપ્યો"),
        "payment_received" to W("Payment received", "Paise mile", "पैसे मिले", "Paisa malya", "પૈસા મળ્યા"),
        "photo" to W("Photo", "Photo", "फोटो", "Photo", "ફોટો"),
        "back_to_entry" to W("Back to entry", "Entry par wapas", "एंट्री पर वापस", "Entry par paachha", "એન્ટ્રી પર પાછા"),
        "more_photos" to W("+{n} more photos are in the app.", "+{n} aur photo app mein hain.", "+{n} और फोटो ऐप में हैं।", "+{n} vadhu photo app ma chhe.", "+{n} વધુ ફોટા એપમાં છે."),
        "page" to W("Page", "Page", "पेज", "Panu", "પાનું"),
        "range_all" to W("All Time", "Poora Hisaab", "पूरा हिसाब", "Badho Hisaab", "બધો હિસાબ"),
        "range_month" to W("This Month", "Is Mahine", "इस महीने", "Aa Mahine", "આ મહિને"),
        "range_3m" to W("Last 3 Months", "Pichhle 3 Mahine", "पिछले 3 महीने", "Chhella 3 Mahina", "છેલ્લા 3 મહિના"),
        "filter_all" to W("Full Statement", "Saari Entry", "सारी एंट्री", "Badhi Entry", "બધી એન્ટ્રી"),
        "filter_pending" to W("Pending Bills Only", "Sirf Baaki Bill", "सिर्फ बाकी बिल", "Fakt Baaki Bill", "ફક્ત બાકી બિલ"),

        // Bill
        "pdfb_title" to W("BILL", "BILL", "बिल", "BILL", "બિલ"),
        "pdfb_bill_no" to W("Bill No.", "Bill No.", "बिल नं.", "Bill No.", "બિલ નં."),
        "pdfb_date" to W("Date", "Tareekh", "तारीख", "Tarikh", "તારીખ"),
        "pdfb_customer" to W("Customer", "Grahak", "ग्राहक", "Grahak", "ગ્રાહક"),
        "pdfb_details" to W("Items / Details", "Saman / Vivran", "सामान / विवरण", "Maal / Vigat", "માલ / વિગત"),
        "pdfb_total" to W("Bill Total", "Bill Total", "बिल कुल", "Bill Kul", "બિલ કુલ"),
        "pdfb_received" to W("Paid Now", "Abhi Mile", "अभी मिले", "Hamna Malya", "હમણાં મળ્યા"),
        "pdfb_this_bill_due" to W("Balance on this Bill", "Is Bill ka Baaki", "इस बिल का बाकी", "Aa Bill ni Baaki", "આ બિલની બાકી"),
        "pdfb_total_due" to W("Total Due (all bills)", "Kul Baaki (sab milakar)", "कुल बाकी (सब मिलाकर)", "Kul Baaki (badhu maline)", "કુલ બાકી (બધું મળીને)"),
        "pdfb_due_date" to W("Pay By", "Bhugtan ki Tareekh", "भुगतान की तारीख", "Chukavani ni Tarikh", "ચૂકવણીની તારીખ"),
        "pdfb_pay_upi" to W("Pay by UPI", "UPI se bhugtan karein", "UPI से भुगतान करें", "UPI thi chukavani karo", "UPI થી ચૂકવણી કરો"),
        "pdfb_chit_photo" to W("Bill Chit", "Bill ki Chit", "बिल की पर्ची", "Bill ni Chitthi", "બિલની ચિઠ્ઠી"),
        "pdfb_thanks" to W("Thank you! Please visit again.", "Dhanyavaad! Phir padhariye.", "धन्यवाद! फिर पधारिए।", "Aabhar! Fari padharjo.", "આભાર! ફરી પધારજો."),
        "pdfb_paid_full" to W("PAID IN FULL", "PURA BHUGTAN", "पूरा भुगतान", "PURI CHUKAVANI", "પૂરી ચૂકવણી"),

        // Plain-text statement
        "text_party" to W("Party", "Party", "पार्टी", "Party", "પાર્ટી"),
        "text_period" to W("Period", "Samay", "समय", "Samay", "સમય"),
        "text_no_entries" to W("No entries in this period.", "Is samay mein koi entry nahi.", "इस समय में कोई एंट्री नहीं।", "Aa samay ma koi entry nathi.", "આ સમયમાં કોઈ એન્ટ્રી નથી."),
        "text_thanks" to W("Thank you for your business!", "Dhanyavaad!", "धन्यवाद!", "Aabhar!", "આભાર!")
    )

    val keys: Set<String> get() = words.keys

    fun get(key: String, lang: PdfLang): String {
        val w = words[key] ?: return key
        return when (lang) {
            PdfLang.ENGLISH -> w.en
            PdfLang.HINGLISH -> w.hing
            PdfLang.HINDI -> w.hi
            PdfLang.GUJLISH -> w.gujl
            PdfLang.GUJARATI -> w.gu
        }
    }
}
