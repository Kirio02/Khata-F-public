package com.example.data

/**
 * Reads a "name, phone, balance" file for moving balances over from another app.
 *
 * This is money, so nothing is guessed: a row is either read exactly or gets an [Row.error]
 * (a LanguageStrings key), is shown to the owner and is never imported. In particular
 * "4,750" must never turn into 4, and "₹4750" must never turn into 0.
 */
object BalanceCsv {

    data class Row(
        val line: Int,
        val name: String,
        val phone: String,
        val balance: Double,
        val error: String? = null
    )

    private val HEADER_NAMES = setOf("name", "customer name", "party name", "customer", "party", "naam")
    private val TOTAL_NAMES = setOf("total", "grand total", "kul", "कुल", "કુલ")

    // 4750 | 4,750 | 1,04,750 (Indian) | 104,750 (western), with up to 2 decimals
    private val AMOUNT = Regex("""^(\d+|\d{1,2}(,\d{2})*,\d{3}|\d{1,3}(,\d{3})+)(\.\d{1,2})?$""")
    private val CURRENCY = Regex("""^(₹|rs\.?|inr)""", RegexOption.IGNORE_CASE)
    private val PHONE = Regex("""^\+?[0-9 ()\-]+$""")

    fun parse(text: String): List<Row> {
        val clean = text.removePrefix("﻿") // Excel "CSV UTF-8" starts with this invisible mark
        val firstLine = clean.lineSequence().firstOrNull { it.isNotBlank() } ?: return emptyList()
        val separator = if ('\t' in firstLine) '\t' else ','

        var records = records(clean, separator)
        if (records.firstOrNull()?.cells?.firstOrNull()?.lowercase() in HEADER_NAMES) {
            records = records.drop(1)
        }
        return records.map(::toRow)
    }

    /** The amount, or null if it can't be read with certainty. A blank cell means no balance. */
    fun amount(raw: String): Double? {
        var s = raw.filterNot { it.isWhitespace() || it == ' ' }
        if (s.isEmpty()) return 0.0
        var negative = false
        if (s.startsWith("-")) {
            negative = true
            s = s.substring(1)
        }
        s = s.replaceFirst(CURRENCY, "")
        if (!negative && s.startsWith("-")) {
            negative = true
            s = s.substring(1)
        }
        if (!AMOUNT.matches(s)) return null
        val value = s.replace(",", "").toDouble()
        return if (negative) -value else value
    }

    private class Record(val line: Int, val cells: List<String>, val brokenQuote: Boolean)

    private fun records(text: String, separator: Char): List<Record> {
        val out = mutableListOf<Record>()
        var cells = mutableListOf<String>()
        val cell = StringBuilder()
        var quoted = false
        var line = 1
        var startLine = 1

        fun endRecord(broken: Boolean = false) {
            cells.add(cell.toString().trim())
            cell.setLength(0)
            if (cells.any { it.isNotEmpty() }) out.add(Record(startLine, cells, broken))
            cells = mutableListOf()
        }

        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c == '\n') line++
            when {
                quoted && c == '"' && text.getOrNull(i + 1) == '"' -> {
                    cell.append('"')
                    i++
                }
                quoted && c == '"' -> quoted = false
                quoted -> cell.append(c)
                c == '"' && cell.isBlank() -> {
                    cell.setLength(0)
                    quoted = true
                }
                c == separator -> {
                    cells.add(cell.toString().trim())
                    cell.setLength(0)
                }
                c == '\n' -> {
                    endRecord()
                    startLine = line
                }
                c != '\r' -> cell.append(c)
            }
            i++
        }
        endRecord(broken = quoted)
        return out
    }

    private fun toRow(record: Record): Row {
        val cells = record.cells
        val name = cells.getOrElse(0) { "" }
        val phone = cells.getOrElse(1) { "" }
        val balance = amount(cells.getOrElse(2) { "" })
        val digits = phone.count { it.isDigit() }

        val error = when {
            // an unquoted comma inside a value pushes the rest into extra columns
            record.brokenQuote || cells.drop(3).any { it.isNotEmpty() } -> "csv_err_columns"
            cells.any { '�' in it } -> "csv_err_encoding"
            name.isBlank() -> "csv_err_name"
            name.lowercase() in TOTAL_NAMES -> "csv_err_total"
            phone.isNotEmpty() && (!PHONE.matches(phone) || digits !in 10..13) -> "csv_err_phone"
            balance == null -> "csv_err_amount"
            else -> null
        }
        return Row(record.line, name, phone, balance ?: 0.0, error)
    }
}
