package com.example.ui.components

import androidx.core.view.WindowCompat
import com.example.ui.theme.AppTheme
import android.os.Build
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogWindowProvider

/**
 * Call first inside a full-screen Dialog that uses decorFitsSystemWindows = false.
 *
 * Android still places a dialog window below the status bar, while Compose sizes it to the
 * whole screen, so its bottom (the action button) lands behind the Back / Home bar and the
 * insets read as zero. This makes the window really cover the screen; the content then pads
 * itself with the normal inset modifiers.
 */
@Composable
internal fun FullScreenDialogWindow(blackBackground: Boolean = false) {
    val view = LocalView.current
    val window = (view.parent as? DialogWindowProvider)?.window ?: return
    val lightIcons = blackBackground || AppTheme.palette.isDark
    SideEffect {
        WindowCompat.getInsetsController(window, view).apply {
            isAppearanceLightStatusBars = !lightIcons
            isAppearanceLightNavigationBars = !lightIcons
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.attributes = window.attributes.apply {
                fitInsetsTypes = 0
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            }
        }
    }
}
