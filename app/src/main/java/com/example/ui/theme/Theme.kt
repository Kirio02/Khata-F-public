package com.example.ui.theme

import android.graphics.drawable.ColorDrawable
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private fun colorSchemeFor(p: Palette): ColorScheme =
    (if (p.isDark) darkColorScheme() else lightColorScheme()).copy(
        primary = p.primary,
        onPrimary = p.card,
        primaryContainer = p.chipActive,
        onPrimaryContainer = p.text,
        secondary = p.accent,
        onSecondary = p.card,
        secondaryContainer = p.selected,
        onSecondaryContainer = p.text,
        // red and green are registered as roles so anything filled with them gets readable content
        tertiary = p.green,
        onTertiary = p.card,
        error = p.red,
        onError = p.card,
        background = p.page,
        onBackground = p.text,
        surface = p.card,
        onSurface = p.text,
        surfaceVariant = p.card,
        onSurfaceVariant = p.textSecondary,
        surfaceTint = p.primary,
        surfaceDim = p.page,
        surfaceBright = p.card,
        surfaceContainerLowest = p.card,
        surfaceContainerLow = p.card,
        surfaceContainer = p.dialog,
        surfaceContainerHigh = p.dialog,
        surfaceContainerHighest = p.dialog,
        outline = p.fieldBorder,
        outlineVariant = p.border
    )

@Composable
fun MyApplicationTheme(content: @Composable () -> Unit) {
    val palette = AppTheme.palette
    val colorScheme = remember(palette) { colorSchemeFor(palette) }

    val view = LocalView.current
    val activity = view.context as? ComponentActivity
    if (!view.isInEditMode && activity != null) {
        DisposableEffect(palette) {
            val dark = palette.isDark
            val clear = android.graphics.Color.TRANSPARENT
            // Do not set window.statusBarColor: on Android 14 and older it painted a white strip
            // over the dark header, hiding the white clock and battery icons.
            activity.enableEdgeToEdge(
                statusBarStyle = SystemBarStyle.auto(clear, clear) { dark },
                navigationBarStyle = SystemBarStyle.auto(
                    palette.card.copy(alpha = 0.9f).toArgb(),
                    palette.card.copy(alpha = 0.9f).toArgb()
                ) { dark }
            )
            // Otherwise Android lays a bright white (or blue-grey) strip behind Back / Home
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                activity.window.isNavigationBarContrastEnforced = false
            }
            activity.window.setBackgroundDrawable(ColorDrawable(palette.page.toArgb()))
            AppTheme.applySplash(activity)
            onDispose { }
        }
        SideEffect {
            WindowCompat.getInsetsController(activity.window, view).isAppearanceLightNavigationBars = !palette.isDark
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
