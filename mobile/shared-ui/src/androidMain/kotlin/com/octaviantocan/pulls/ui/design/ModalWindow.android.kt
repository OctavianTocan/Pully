package com.octaviantocan.pulls.ui.design

import androidx.compose.runtime.Composable
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/** The original Android dialog and all caller-supplied window properties. */
@Composable
internal actual fun ModalWindow(onDismissRequest: () -> Unit, properties: DialogProperties, content: @Composable () -> Unit) {
    Dialog(onDismissRequest, properties, content = content)
}
