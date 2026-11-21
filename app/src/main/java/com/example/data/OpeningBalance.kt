package com.example.data

/**
 * A party's opening balance (old udhar carried over when the party is added) is
 * stored as a normal GAVE/GOT entry dated the day it was added. It is old money,
 * not today's business, so "today" totals must skip it.
 *
 * Entries are recognised by their note, which is the opening_balance_note text
 * in whichever language was active when the party was added, or the CSV-import note.
 */
object OpeningBalance {
    private val NOTES = setOf(
        "Opening Balance",          // Hinglish / English
        "प्रारंभिक शेष",              // Hindi
        "શરૂઆતની બાકી",             // Gujarati
        "Opening Balance (CSV Import)"
    )

    fun isOpeningNote(note: String): Boolean = note.trim() in NOTES
}
