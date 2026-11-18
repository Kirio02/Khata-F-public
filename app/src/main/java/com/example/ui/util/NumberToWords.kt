package com.example.ui.util

object NumberToWords {

    private val units = arrayOf(
        "", "One", "Two", "Three", "Four", "Five", "Six", "Seven", "Eight", "Nine",
        "Ten", "Eleven", "Twelve", "Thirteen", "Fourteen", "Fifteen", "Sixteen",
        "Seventeen", "Eighteen", "Nineteen"
    )

    private val tens = arrayOf(
        "", "", "Twenty", "Thirty", "Forty", "Fifty", "Sixty", "Seventy", "Eighty", "Ninety"
    )

    fun convertRupees(amount: Double): String {
        val roundedAmount = Math.round(amount * 100.0) / 100.0
        val wholePart = roundedAmount.toLong()
        val paisePart = Math.round((roundedAmount - wholePart) * 100).toInt()

        if (wholePart == 0L && paisePart == 0) {
            return "Zero Rupees Only"
        }

        val sb = StringBuilder()
        if (wholePart > 0) {
            sb.append(convertWholeNumber(wholePart))
            sb.append(if (wholePart == 1L) " Rupee" else " Rupees")
        }

        if (paisePart > 0) {
            if (sb.isNotEmpty()) sb.append(" and ")
            sb.append(convertWholeNumber(paisePart.toLong()))
            sb.append(" Paise")
        }

        sb.append(" Only")
        return sb.toString().trim()
    }

    private fun convertWholeNumber(num: Long): String {
        if (num == 0L) return "Zero"
        if (num < 0) return "Minus " + convertWholeNumber(-num)

        var n = num
        val sb = StringBuilder()

        val crore = n / 10000000L
        n %= 10000000L

        val lakh = n / 100000L
        n %= 100000L

        val thousand = n / 1000L
        n %= 1000L

        val hundred = n / 100L
        val rest = (n % 100L).toInt()

        if (crore > 0) {
            sb.append(convertLessThanThousand(crore.toInt())).append(" Crore ")
        }
        if (lakh > 0) {
            sb.append(convertLessThanThousand(lakh.toInt())).append(" Lakh ")
        }
        if (thousand > 0) {
            sb.append(convertLessThanThousand(thousand.toInt())).append(" Thousand ")
        }
        if (hundred > 0) {
            sb.append(units[hundred.toInt()]).append(" Hundred ")
        }
        if (rest > 0) {
            sb.append(convertLessThanHundred(rest)).append(" ")
        }

        return sb.toString().trim()
    }

    private fun convertLessThanThousand(number: Int): String {
        val hundred = number / 100
        val rest = number % 100
        val sb = StringBuilder()
        if (hundred > 0) {
            sb.append(units[hundred]).append(" Hundred ")
        }
        if (rest > 0) {
            sb.append(convertLessThanHundred(rest))
        }
        return sb.toString().trim()
    }

    private fun convertLessThanHundred(number: Int): String {
        return if (number < 20) {
            units[number]
        } else {
            val t = number / 10
            val u = number % 10
            if (u > 0) "${tens[t]} ${units[u]}" else tens[t]
        }
    }
}
