package com.example.ui.util

object PhoneUtil {

    private fun digitsOf(raw: String?): String = raw?.filter { it.isDigit() } ?: ""

    /** The 10-digit national part when the digits have an Indian shape, else null. */
    private fun indianNational(digits: String): String? {
        var n = digits
        if (n.startsWith("0091")) n = n.substring(4)
        else if (n.length == 12 && n.startsWith("91")) n = n.substring(2)
        else if (n.length == 11 && n.startsWith("0")) n = n.substring(1)
        return if (n.length == 10) n else null
    }

    /** True for a 10-digit Indian mobile number starting with 6, 7, 8 or 9 (with or without +91 / 0). */
    fun isValidIndianMobile(raw: String?): Boolean {
        val n = indianNational(digitsOf(raw)) ?: return false
        return n[0] in '6'..'9'
    }

    /** Valid Indian mobile, or an international-looking number (11 to 15 digits, not starting with 0). */
    fun isValid(raw: String?): Boolean {
        val d = digitsOf(raw)
        if (d.isEmpty()) return false
        if (isValidIndianMobile(d)) return true
        if (indianNational(d) != null) return false
        return d.length in 11..15 && !d.startsWith("0")
    }

    /**
     * Digits only. A valid Indian mobile always becomes 91XXXXXXXXXX.
     * Other numbers are returned as plain digits (international numbers are kept as they are).
     */
    fun normalize(raw: String?): String {
        val d = digitsOf(raw)
        if (d.isEmpty()) return ""
        val n = indianNational(d)
        return if (n != null && n[0] in '6'..'9') "91$n" else d
    }

    /** Number ready for wa.me/<number>, or an empty string when the number is not usable. */
    fun toWhatsAppNumber(raw: String?): String {
        return if (isValid(raw)) normalize(raw) else ""
    }

    /** Indian display format: +91 98765 43210. Invalid numbers are shown as typed digits. */
    fun toDisplay(raw: String?): String {
        val norm = normalize(raw)
        if (norm.length == 12 && norm.startsWith("91") && isValidIndianMobile(norm)) {
            return "+91 " + norm.substring(2, 7) + " " + norm.substring(7)
        }
        return norm
    }
}
