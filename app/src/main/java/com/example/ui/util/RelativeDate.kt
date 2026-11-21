package com.example.ui.util

import com.example.model.AppLanguage
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Dates the way people say them: "Aaj, 03:23 PM", "Kal, 11:05 AM", "3 din pehle",
 * then the full date for anything older than a week.
 */
object RelativeDate {

    fun format(millis: Long, lang: AppLanguage, now: Long = System.currentTimeMillis()): String {
        val days = daysBetween(millis, now)
        val time = SimpleDateFormat("hh:mm a", Locale.US).format(Date(millis))
        return when {
            days == 0 -> "${LanguageStrings.get("date_today_label", lang)}, $time"
            days == 1 -> "${LanguageStrings.get("date_yesterday_label", lang)}, $time"
            days in 2..6 -> LanguageStrings.get("days_ago", lang).replace("%d", days.toString())
            else -> SimpleDateFormat("dd MMM yyyy", Locale.US).format(Date(millis))
        }
    }

    /** Whole calendar days from [from] to [to] (0 = same day). */
    fun daysBetween(from: Long, to: Long): Int {
        fun dayStart(t: Long) = Calendar.getInstance().apply {
            timeInMillis = t
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        val diff = dayStart(to) - dayStart(from)
        // round() absorbs the 1-hour shift on daylight-saving days
        return Math.round(diff / 86_400_000.0).toInt()
    }
}
