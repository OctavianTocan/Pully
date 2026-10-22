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
    SideEffect {
        val window = (view.parent as? DialogWindowProvider)?.window ?: return@SideEffect
        window.setDimAmount(0f)
        window.setWindowAnimations(0)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) window.isNavigationBarContrastEnforced = false
        // The panel's paper runs under the navigation bar, so its icons follow the paper.
        WindowCompat.getInsetsController(window, view).isAppearanceLightNavigationBars = light
    }
}
