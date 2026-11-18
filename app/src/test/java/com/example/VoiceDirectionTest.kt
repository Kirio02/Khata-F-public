package com.example

import com.example.model.Customer
import com.example.ui.util.VoiceEntryParser
import org.junit.Assert.*
import org.junit.Test

class VoiceDirectionTest {
    private val customers = listOf(
        Customer(id = "1", name = "Ramesh Patel", phone = "9800000001"),
        Customer(id = "2", name = "Ramesh Shah", phone = "9800000002"),
        Customer(id = "3", name = "Suresh Modi", phone = "9800000003")
    )

    @Test
    fun koDiyaIsMoneyGivenOut() {
        assertEquals("GAVE", VoiceEntryParser.parse("maine Suresh ko 500 diya", customers).type)
        assertEquals("GAVE", VoiceEntryParser.parse("Suresh ko 500 diye", customers).type)
        assertEquals("GAVE", VoiceEntryParser.parse("500 rupaye diye Suresh ko", customers).type)
    }

    @Test
    fun neDiyeIsMoneyReceived() {
        assertEquals("GOT", VoiceEntryParser.parse("Suresh ne 500 diye", customers).type)
        assertEquals("GOT", VoiceEntryParser.parse("Suresh se 500 mile", customers).type)
    }

    @Test
    fun severalRameshAreNotGuessed() {
        val e = VoiceEntryParser.parse("Ramesh ne 500 diye", customers)
        assertNull(e.matchedCustomer)
        assertEquals(2, e.candidates.size)
    }

    @Test
    fun fullNamePicksTheRightOne() {
        val e = VoiceEntryParser.parse("Ramesh Shah ne 500 diye", customers)
        assertEquals("2", e.matchedCustomer?.id)
        assertTrue(e.candidates.isEmpty())
    }

    @Test
    fun noCueMarksDirectionUncertain() {
        assertTrue(VoiceEntryParser.parse("500 Suresh", customers).typeUncertain)
        assertFalse(VoiceEntryParser.parse("Suresh ko 500 diye", customers).typeUncertain)
    }
}
