package com.example.ui.theme

import android.app.Activity
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.example.R

/** The background the owner chose. [SYSTEM] is White, or Dark while the phone is in dark mode. */
enum class ThemeChoice { WHITE, CREAM, DARK, SYSTEM }

fun paletteFor(choice: ThemeChoice, systemDark: Boolean): Palette = when (choice) {
    ThemeChoice.WHITE -> WhitePalette
    ThemeChoice.CREAM -> CreamPalette
    ThemeChoice.DARK -> DarkPalette
    ThemeChoice.SYSTEM -> if (systemDark) DarkPalette else WhitePalette
}

/**
 * Holds the choice in snapshot state, so every composable that reads a colour from Color.kt
 * redraws when it changes. Saved in the normal prefs file, which Android auto-backup includes.
 */
object AppTheme {
    private const val PREFS = "ghanshyam_billing_prefs"
    private const val KEY = "app_theme"

    var choice by mutableStateOf(ThemeChoice.WHITE)
        private set
    private var systemDark by mutableStateOf(false)

    val palette: Palette get() = paletteFor(choice, systemDark)

    fun load(context: Context) {
        val saved = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)
        choice = ThemeChoice.entries.firstOrNull { it.name == saved } ?: ThemeChoice.WHITE
        onSystemChanged(context.resources.configuration)
    }

    fun onSystemChanged(config: Configuration) {
        systemDark = (config.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
    }

    fun select(context: Context, newChoice: ThemeChoice) {
        choice = newChoice
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, newChoice.name).apply()
    }

    /** Without this a Dark user gets a white flash each time the app opens. */
    fun applySplash(activity: Activity) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            activity.splashScreen.setSplashScreenTheme(
                when (choice) {
                    ThemeChoice.WHITE -> R.style.Theme_MyApplication
                    ThemeChoice.CREAM -> R.style.Theme_MyApplication_Cream
                    ThemeChoice.DARK -> R.style.Theme_MyApplication_Dark
                    ThemeChoice.SYSTEM -> R.style.Theme_MyApplication_System
                }
            )
        }
    }

    /** Theme for Android's own calendar dialog. */
    val calendarStyle: Int
        get() = when (palette) {
            DarkPalette -> R.style.Calendar_Dark
            CreamPalette -> R.style.Calendar_Cream
            else -> R.style.Calendar_White
        }
}
