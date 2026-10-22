@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.octaviantocan.pulls.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSOperationQueue
import platform.GameController.GCKeyboard
import platform.GameController.GCKeyboardDidConnectNotification
import platform.GameController.GCKeyboardDidDisconnectNotification

@Composable
internal actual fun rememberHardwareKeyboardPresent(): Boolean {
    var connected by remember { mutableStateOf(GCKeyboard.coalescedKeyboard != null) }
    DisposableEffect(Unit) {
        val center = NSNotificationCenter.defaultCenter
        val joined = center.addObserverForName(GCKeyboardDidConnectNotification, null, NSOperationQueue.mainQueue) {
            connected = GCKeyboard.coalescedKeyboard != null
        }
        val left = center.addObserverForName(GCKeyboardDidDisconnectNotification, null, NSOperationQueue.mainQueue) {
            connected = GCKeyboard.coalescedKeyboard != null
        }
        connected = GCKeyboard.coalescedKeyboard != null
        onDispose { center.removeObserver(joined); center.removeObserver(left) }
    }
    return connected
}
