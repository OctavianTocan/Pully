package com.octaviantocan.pulls.ui

import android.content.Intent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Links handed to Pulls from another app while it is already open: a tapped GitHub link, a Share, or selected text's
 * Open in Pulls. [PullsApp] opens each one over the page on screen, so Back returns to where the reader was.
 */
object IncomingLinks {
    /** The text that arrived and has not been opened yet. A new object each time, so the same link shared twice opens twice. */
    var pending by mutableStateOf<Arrival?>(null)
        private set

    class Arrival(val text: String)

    fun receive(intent: Intent?) {
        pending = read(intent)?.let(::Arrival)
    }

    fun consume(arrival: Arrival) {
        if (pending === arrival) pending = null
    }

    /** The link or text another app handed over, or `null` for a launch that carries none (the launcher, a notification). */
    fun read(intent: Intent?): String? {
        if (intent == null) return null
        val text = when (intent.action) {
            Intent.ACTION_SEND -> intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()
            Intent.ACTION_PROCESS_TEXT -> intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString()
            Intent.ACTION_VIEW -> intent.dataString
            else -> null
        }
        return text?.takeIf { it.isNotBlank() }
    }
}
