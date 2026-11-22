package com.example.ui.theme

import androidx.compose.ui.graphics.Color

/** Every colour that changes with the theme. ThemeContrastTest checks the readable pairs. */
data class Palette(
    val isDark: Boolean,
    val page: Color,
    val card: Color, // also the text colour on primary / red / green buttons
    val dialog: Color,
    val selected: Color,
    val chipActive: Color,
    val chipInactive: Color,
    val border: Color,
    val fieldBorder: Color,
    val text: Color,
    val textSecondary: Color,
    val textTertiary: Color,
    val primary: Color,
    val accent: Color,
    val red: Color,
    val green: Color,
    val amber: Color
)

val WhitePalette = Palette(
    isDark = false,
    page = Color(0xFFF9FAFB),
    card = Color(0xFFFFFFFF),
    dialog = Color(0xFFECE6F0),
    selected = Color(0xFFE8DEF8),
    chipActive = Color(0xFFF3F4F6),
    chipInactive = Color(0xFFF9FAFB),
    border = Color(0xFFE5E7EB),
    fieldBorder = Color(0xFF6B7280),
    text = Color(0xFF111827),
    textSecondary = Color(0xFF4B5563),
    textTertiary = Color(0xFF5F6673),
    primary = Color(0xFF323433),
    accent = Color(0xFF83180D),
    red = Color(0xFFAE1919),
    green = Color(0xFF12642F),
    amber = Color(0xFF85390B)
)

/** Warm and about a quarter less bright than White. */
val CreamPalette = Palette(
    isDark = false,
    page = Color(0xFFE2D7C4),
    card = Color(0xFFEFE6D6),
    dialog = Color(0xFFEBE1D0),
    selected = Color(0xFFD3C5AC),
    chipActive = Color(0xFFD9CDBA),
    chipInactive = Color(0xFFE2D7C4),
    border = Color(0xFFC4B59D),
    fieldBorder = Color(0xFF6A6155),
    text = Color(0xFF262119),
    textSecondary = Color(0xFF443D36),
    textTertiary = Color(0xFF544C43),
    primary = Color(0xFF323433),
    accent = Color(0xFF741409),
    red = Color(0xFF861010),
    green = Color(0xFF0C4A25),
    amber = Color(0xFF633006)
)

val DarkPalette = Palette(
    isDark = true,
    page = Color(0xFF141414),
    card = Color(0xFF222222),
    dialog = Color(0xFF292929),
    selected = Color(0xFF45413B),
    chipActive = Color(0xFF343434),
    chipInactive = Color(0xFF191919),
    border = Color(0xFF3D3D3D),
    fieldBorder = Color(0xFF948E85),
    text = Color(0xFFE8E2D8),
    textSecondary = Color(0xFFC2BBB0),
    textTertiary = Color(0xFFAAA49B),
    primary = Color(0xFFDDD6CB),
    accent = Color(0xFFF0A59B),
    red = Color(0xFFFF9B92),
    green = Color(0xFF7CDCA0),
    amber = Color(0xFFF5B757)
)
