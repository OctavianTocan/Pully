package com.octaviantocan.pulls.ui.design

import androidx.compose.runtime.Composable
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.octaviantocan.pulls.ui.SharedBackHandler

/** Native focus/inset/dismissal handling surrounds the same shared modal animation and content. */
@Composable
internal actual fun ModalWindow(onDismissRequest: () -> Unit, properties: DialogProperties, content: @Composable () -> Unit) {
    Dialog(onDismissRequest, properties) {
        SharedBackHandler(onDismissRequest)
        content()
    }
}
