package com.example.ui.util

/**
 * Pure helper for the home-screen quick entry buttons ("Maine diye" / "Mujhe mile").
 * Kept separate from the Compose screen so the type decision is unit-testable.
 */
object QuickEntry {
    const val GAVE = "GAVE"
    const val GOT = "GOT"

    /** The entry dialog type comes only from the button that was tapped. */
    fun resolveType(tappedType: String?): String = if (tappedType == GAVE) GAVE else GOT
}
