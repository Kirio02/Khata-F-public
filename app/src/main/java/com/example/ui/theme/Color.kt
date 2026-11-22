package com.example.ui.theme

import androidx.compose.ui.graphics.Color

// Brand colours: the same in every theme. Use OnBrand for anything drawn on them (or on black).
val BrandCharcoal = Color(0xFF323433) // Header band (#323433)
val BrandMaroon = Color(0xFF83180D)   // Logo area only (#83180D)
val GoldAccent = Color(0xFFD4AF37)
val OnBrand = Color(0xFFFFFFFF)
val PremiumBannerBg = BrandCharcoal

// Themed colours. These are getters on purpose: never copy one into a top-level val,
// or it will stop following the theme.
val PrimaryBlue: Color get() = AppTheme.palette.primary // the primary UI colour (charcoal; light in Dark)
val AccentCyan: Color get() = AppTheme.palette.accent

// Money colours: green for received and clearly brighter red for given (never maroon)
val UdharRed: Color get() = AppTheme.palette.red
val JamaGreen: Color get() = AppTheme.palette.green

val SoftBackground: Color get() = AppTheme.palette.page
val SurfaceWhite: Color get() = AppTheme.palette.card // card colour; white only in the White theme
val CardBorder: Color get() = AppTheme.palette.border
val FieldBorder: Color get() = AppTheme.palette.fieldBorder // Visible outline for text fields and outlined buttons
val TextPrimary: Color get() = AppTheme.palette.text
val TextSecondary: Color get() = AppTheme.palette.textSecondary
val TextTertiary: Color get() = AppTheme.palette.textTertiary

val ChipActiveBg: Color get() = AppTheme.palette.chipActive
val ChipActiveText: Color get() = AppTheme.palette.primary
val ChipInactiveBg: Color get() = AppTheme.palette.chipInactive
val ChipInactiveText: Color get() = AppTheme.palette.textSecondary

val WarningAmber: Color get() = AppTheme.palette.amber
