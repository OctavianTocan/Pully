package com.octaviantocan.pulls.ui.review

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context

/** Puts [text] on the clipboard. Android 13+ shows its own confirmation; the app's toast covers older ones. */
fun copyText(context: Context, text: String) {
    context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("pulls.review", text))
}
