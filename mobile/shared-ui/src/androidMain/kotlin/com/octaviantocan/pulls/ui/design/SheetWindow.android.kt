package com.octaviantocan.pulls.ui.design

import android.os.Build
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog as ComposeDialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat

internal actual object SheetPlatform {
    @Composable
    actual fun Dialog(onDismissRequest: () -> Unit, content: @Composable () -> Unit) {
        ComposeDialog(
            onDismissRequest = onDismissRequest,
            properties = DialogProperties(dismissOnClickOutside = false, usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
        ) {
            ConfigureSheetWindow()
            content()
        }
    }

    @Composable
    actual fun screenHeight(): Dp = LocalConfiguration.current.screenHeightDp.dp
}

/** The window the sheet draws in: edge to edge, no dim of its own (the scrim fades with the panel) and no window animation. */
@Composable
private fun ConfigureSheetWindow() {
    val view = LocalView.current
    val light = LocalPullsColors.current.paper.luminance() > 0.5f
    val lightPage = !LocalPullsColors.current.dark
    SideEffect {
        val window = (view.parent as? DialogWindowProvider)?.window ?: return@SideEffect
        // No system dim at all, not even for the first frame: it flashed black under the panel (recording 26 Oct 2026, 01:07 to 01:28).
        window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        window.setDimAmount(0f)
        window.setWindowAnimations(0)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) window.isNavigationBarContrastEnforced = false
        // The panel's paper runs under the navigation bar, so its icons follow the paper.
        WindowCompat.getInsetsController(window, view).isAppearanceLightNavigationBars = light
        // The status bar sits over the page behind the sheet, so its icons follow the page, as the app's own window does;
        // the window's default left them white on a light page while the sheet opened.
        WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = lightPage
    }
}
