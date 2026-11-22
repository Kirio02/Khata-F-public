package com.example.ui.util

import java.util.Calendar

/**
 * The date the owner picks for a bill or entry.
 *
 * [NOW] means "not picked": the moment of saving is used, so a form left open
 * overnight is not saved with yesterday's date. A picked day keeps a time of day,
 * so entries of the same day still sort in the order they were typed.
 */
object EntryDate {
    const val NOW = 0L

    fun resolve(picked: Long, now: Long = System.currentTimeMillis()): Long = if (picked == NOW) now else picked

    /** [timeFrom] moved to the given calendar day, in the phone's time zone. [month] is 0-based. */
    fun onDay(year: Int, month: Int, day: Int, timeFrom: Long): Long = Calendar.getInstance().apply {
        timeInMillis = timeFrom
        set(year, month, day)
    }.timeInMillis

    fun daysAgo(days: Int, now: Long = System.currentTimeMillis()): Long = addDays(now, -days)

    fun addDays(from: Long, days: Int): Long = Calendar.getInstance().apply {
        timeInMillis = from
        add(Calendar.DAY_OF_YEAR, days)
    }.timeInMillis

    /** Due date from either a calendar pick or a "7 / 15 / 30 din" chip; 0 = none. */
    fun due(entryDate: Long, chipDays: Int, picked: Long): Long = when {
        picked != NOW -> picked
        chipDays > 0 -> addDays(entryDate, chipDays)
        else -> 0L
    }
}
