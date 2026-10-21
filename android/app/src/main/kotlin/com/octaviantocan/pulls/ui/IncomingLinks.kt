package com.octaviantocan.pulls.ui

import android.content.Intent
import android.content.Context
import com.octaviantocan.pulls.notifications.AiResults
import com.octaviantocan.pulls.notifications.CheckNotifications
import com.octaviantocan.pulls.core.LinkPlace
import com.octaviantocan.pulls.core.OpenTarget
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Links handed to Pully from another app while it is already open: a tapped GitHub link, a Share, or selected text's
 * Open in Pully. [PullsApp] opens each one over the page on screen, so Back returns to where the reader was.
 */
object IncomingLinks {
    /** The text that arrived and has not been opened yet. A new object each time, so the same link shared twice opens twice. */
    var pending by mutableStateOf<Arrival?>(null)
        private set

    class Arrival(val text: String, val aiResult: String? = null)

    /**
     * The last link to the pull request already on screen. Nothing opens over it, so that page reads the pull request
     * again: an agent that just changed it sends the link to show the change (Tavi, 16 Oct 13:52).
     */
    var reopened by mutableStateOf<Reopen?>(null)
        private set

    /** [place]: the thing on the pull request the link pointed at, which the page goes to (`LinkPlaces`). */
    class Reopen(val target: OpenTarget, val place: LinkPlace? = null, val aiResult: String? = null)

    fun reopen(target: OpenTarget, place: LinkPlace? = null, aiResult: String? = null) {
        reopened = Reopen(target, place, aiResult)
    }

    fun receive(intent: Intent?, context: Context? = null) {
        pending = read(intent, context)?.let { Arrival(it, result(intent, context)) }
    }

    /** A link tapped inside Pully that Pully opens: it goes the way a link from another app does. */
    fun open(text: String) {
        pending = Arrival(text)
    }

    fun consume(arrival: Arrival) {
        if (pending === arrival) pending = null
    }

    /** The link or text another app handed over, or `null` for a launch that carries none (the launcher, a notification). */
    fun read(intent: Intent?, context: Context? = null): String? {
        if (intent == null) return null
        if (AiResults.isNotification(intent)) return context?.let { AiResults.destination(it, intent)?.url }
        if (CheckNotifications.isNotification(intent)) return context?.let { CheckNotifications.destination(it, intent) }
        val text = when (intent.action) {
            Intent.ACTION_SEND -> intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()
            Intent.ACTION_PROCESS_TEXT -> intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString()
            Intent.ACTION_VIEW -> intent.dataString
            else -> null
        }
        return text?.takeIf { it.isNotBlank() }
    }

    fun result(intent: Intent?, context: Context?): String? =
        if (intent != null && context != null) AiResults.destination(context, intent)?.kind else null
}
