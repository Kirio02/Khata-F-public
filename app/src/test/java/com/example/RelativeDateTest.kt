package com.example

import com.example.model.AppLanguage
import com.example.ui.util.RelativeDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

class RelativeDateTest {

    private fun at(daysAgo: Int, hour: Int, now: Long): Long = Calendar.getInstance().apply {
        timeInMillis = now
        add(Calendar.DAY_OF_YEAR, -daysAgo)
        set(Calendar.HOUR_OF_DAY, hour); set(Calendar.MINUTE, 5)
    }.timeInMillis

    private val now = Calendar.getInstance().apply {
        set(2026, Calendar.NOVEMBER, 21, 10, 0, 0)
    }.timeInMillis

    @Test
    fun todayAndYesterdayKeepTheTime() {
        assertTrue(RelativeDate.format(at(0, 9, now), AppLanguage.HINGLISH, now).startsWith("Aaj, "))
        // 11 PM yesterday is still "Kal" even though it is under 24 hours ago
        assertTrue(RelativeDate.format(at(1, 23, now), AppLanguage.HINGLISH, now).startsWith("Kal, "))
    }

    @Test
    fun recentDaysSayHowLongAgo() {
        assertEquals("3 din pehle", RelativeDate.format(at(3, 12, now), AppLanguage.HINGLISH, now))
        assertEquals("6 दिन पहले", RelativeDate.format(at(6, 12, now), AppLanguage.HINDI, now))
    }

    @Test
    fun olderThanAWeekShowsTheDate() {
        assertEquals("14 Nov 2026", RelativeDate.format(at(7, 12, now), AppLanguage.HINGLISH, now))
    }
}
