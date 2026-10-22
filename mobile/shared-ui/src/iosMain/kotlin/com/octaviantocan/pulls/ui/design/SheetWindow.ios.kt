package com.octaviantocan.pulls.ui.design

import androidx.compose.runtime.Composable
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.window.Dialog as ComposeDialog
import androidx.compose.ui.window.DialogProperties
import com.octaviantocan.pulls.ui.SharedDialogBack

internal actual object SheetPlatform {
    /** Compose's native scene layer supplies focus, dismissal and real UIKit safe-area/keyboard insets. */
    @OptIn(ExperimentalComposeUiApi::class)
    @Composable
    actual fun Dialog(onDismissRequest: () -> Unit, content: @Composable () -> Unit) {
        ComposeDialog(
            onDismissRequest = onDismissRequest,
            properties = DialogProperties(
                dismissOnClickOutside = false,
                usePlatformDefaultWidth = false,
                usePlatformInsets = false,
                useSoftwareKeyboardInset = false,
                scrimColor = Color.Transparent,
                animateTransition = false,
            ),
        ) {
            SharedDialogBack(onDismissRequest)
            content()
        }
    }

    @Composable
    actual fun screenHeight(): Dp = with(LocalDensity.current) {
        LocalWindowInfo.current.containerSize.height.toDp()
    }
}
