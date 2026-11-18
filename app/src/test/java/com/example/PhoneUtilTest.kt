package com.example

import com.example.ui.util.PhoneUtil
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneUtilTest {

    @Test
    fun validIndianMobilesAreAccepted() {
        assertTrue(PhoneUtil.isValid("9876543210"))
        assertTrue(PhoneUtil.isValid("+91 98765 43210"))
        assertTrue(PhoneUtil.isValid("09876543210"))
        assertEquals("919876543210", PhoneUtil.toWhatsAppNumber("98765-43210"))
        assertEquals("+91 98765 43210", PhoneUtil.toDisplay("9876543210"))
    }

    @Test
    fun badNumbersAreRejectedAndNeverGetACountryCode() {
        // 10 digits starting with 0, and an 8 digit number (both seen in real testing)
        assertFalse(PhoneUtil.isValid("0917826521"))
        assertFalse(PhoneUtil.isValid("98090890"))
        assertFalse(PhoneUtil.isValid("1234567890"))
        assertEquals("", PhoneUtil.toWhatsAppNumber("0917826521"))
        assertEquals("", PhoneUtil.toWhatsAppNumber("98090890"))
        assertEquals("0917826521", PhoneUtil.normalize("0917826521"))
    }

    @Test
    fun internationalNumbersAreKept() {
        assertTrue(PhoneUtil.isValid("+1 415 555 2671"))
        assertEquals("14155552671", PhoneUtil.normalize("+1 415 555 2671"))
    }

    @Test
    fun blankIsNotValid() {
        assertFalse(PhoneUtil.isValid(""))
        assertFalse(PhoneUtil.isValid(null))
        assertEquals("", PhoneUtil.normalize(null))
    }
}
