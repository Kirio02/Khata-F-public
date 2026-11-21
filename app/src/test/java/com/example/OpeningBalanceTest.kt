package com.example

import com.example.data.OpeningBalance
import com.example.model.AppLanguage
import com.example.ui.util.LanguageStrings
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OpeningBalanceTest {

    @Test
    fun recognisesOpeningNoteInEveryLanguage() {
        // If someone edits the opening_balance_note text, this fails instead of the
        // home "today" totals silently counting opening balances again.
        AppLanguage.entries.forEach { lang ->
            val note = LanguageStrings.get("opening_balance_note", lang)
            assertTrue("Not recognised for $lang: $note", OpeningBalance.isOpeningNote(note))
        }
        assertTrue(OpeningBalance.isOpeningNote("Opening Balance (CSV Import)"))
    }

    @Test
    fun ordinaryNotesAreNotOpening() {
        assertFalse(OpeningBalance.isOpeningNote(""))
        assertFalse(OpeningBalance.isOpeningNote("2 frock + 1 set"))
        assertFalse(OpeningBalance.isOpeningNote("Opening Balance baaki"))
    }
}
