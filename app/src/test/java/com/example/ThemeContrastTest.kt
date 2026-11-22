package com.example

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import com.example.ui.theme.CreamPalette
import com.example.ui.theme.DarkPalette
import com.example.ui.theme.Palette
import com.example.ui.theme.ThemeChoice
import com.example.ui.theme.WhitePalette
import com.example.ui.theme.paletteFor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The owner has trouble with glare and small text, so every pair must reach 4.5:1 (WCAG AA). */
class ThemeContrastTest {

    private fun contrast(a: Color, b: Color): Float {
        val x = a.luminance()
        val y = b.luminance()
        return (maxOf(x, y) + 0.05f) / (minOf(x, y) + 0.05f)
    }

    private fun failures(name: String, p: Palette): List<String> {
        val backgrounds = mapOf("page" to p.page, "card" to p.card, "dialog" to p.dialog, "chip" to p.chipActive, "chip2" to p.chipInactive)
        val inks = mapOf("text" to p.text, "secondary" to p.textSecondary, "tertiary" to p.textTertiary, "primary" to p.primary, "red" to p.red, "green" to p.green, "amber" to p.amber)
        val pairs = mutableListOf<Triple<String, Color, Color>>()
        inks.forEach { (ink, fg) -> backgrounds.forEach { (bg, c) -> pairs += Triple("$ink on $bg", fg, c) } }
        // button text on filled buttons
        mapOf("primary" to p.primary, "red" to p.red, "green" to p.green).forEach { (fill, c) -> pairs += Triple("button text on $fill", p.card, c) }
        // status tags: the colour on its own 15% tint
        mapOf("red" to p.red, "green" to p.green, "amber" to p.amber).forEach { (ink, c) ->
            pairs += Triple("$ink on its tint", c, c.copy(alpha = 0.15f).compositeOver(p.card))
        }
        pairs += Triple("text on selected", p.text, p.selected)
        pairs += Triple("secondary on selected", p.textSecondary, p.selected)
        return pairs.filter { contrast(it.second, it.third) < 4.5f }
            .map { "$name: ${it.first} = ${contrast(it.second, it.third)}" }
    }

    @Test
    fun everyThemeIsReadable() {
        val failed = failures("White", WhitePalette) + failures("Cream", CreamPalette) + failures("Dark", DarkPalette)
        assertTrue(failed.joinToString("; "), failed.isEmpty())
    }

    @Test
    fun creamAndDarkGiveOffLessLightThanWhite() {
        assertTrue(CreamPalette.page.luminance() < WhitePalette.page.luminance() * 0.8f)
        assertTrue(DarkPalette.page.luminance() < 0.02f)
    }

    @Test
    fun systemFollowsPhoneAndOtherChoicesIgnoreIt() {
        assertEquals(DarkPalette, paletteFor(ThemeChoice.SYSTEM, true))
        assertEquals(WhitePalette, paletteFor(ThemeChoice.SYSTEM, false))
        listOf(true, false).forEach {
            assertEquals(WhitePalette, paletteFor(ThemeChoice.WHITE, it))
            assertEquals(CreamPalette, paletteFor(ThemeChoice.CREAM, it))
            assertEquals(DarkPalette, paletteFor(ThemeChoice.DARK, it))
        }
    }
}
