package com.octaviantocan.pulls.ui.personal

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.octaviantocan.pulls.ui.explore.NativeEntryMode
import com.octaviantocan.pulls.ui.explore.NativeEntryState

/** An account invitation has no browser or read authority when its native services are unavailable. */
class GitHubAccountEntry(
    private val current: () -> Boolean,
    private val foreground: () -> Boolean,
    private val configured: () -> Boolean,
    private val available: () -> Boolean,
    private val mount: () -> PersonalBrowseMount?,
) {
    enum class Unavailable { Configuration, Device }
    class Explanation internal constructor(val reason: Unavailable)

    private var closed = false
    var explanation by mutableStateOf<Explanation?>(null)
        private set

    fun open(): Boolean {
        if (closed || !current() || !foreground()) return false
        val hasConfiguration = configured()
        val admitted = hasConfiguration && available()
        val opening = if (admitted) mount() else null
        val reason = when {
            !hasConfiguration -> Unavailable.Configuration
            !admitted || opening == null -> Unavailable.Device
            else -> null
        }
        if (closed || !current() || !foreground()) return false
        if (reason != null) {
            if (explanation?.reason != reason) explanation = Explanation(reason)
            return true
        }
        explanation = null
        if (opening == null) return false
        opening.openAccount()
        return opening.presentation != null
    }

    fun dismiss(opening: Explanation) {
        if (!closed && current() && explanation === opening) explanation = null
    }

    fun close() { closed = true; explanation = null }
}

/** Only a fixed normal-account intent crosses entry replacement, stamped to its new public source. */
class GitHubAccountRequest(private val entry: NativeEntryState) {
    private class Pending(val generation: Long)
    private var pending by mutableStateOf<Pending?>(null)
    val hasPending: Boolean get() = pending != null

    fun request(current: () -> Boolean, foreground: () -> Boolean, selectExplore: () -> Unit) {
        if (!current() || !foreground()) return
        pending = null
        selectExplore()
        if (entry.mode == NativeEntryMode.Explore) pending = Pending(entry.generation)
    }

    fun deliver(to: GitHubAccountEntry) {
        val opening = pending ?: return
        if (entry.mode != NativeEntryMode.Explore || entry.generation != opening.generation) {
            if (pending === opening) pending = null
            return
        }
        if (to.open() && pending === opening) pending = null
    }

    fun clear() { pending = null }
}

/** Settings owns the displayed row even if the same source later opens another Settings sheet. */
class GitHubAccountSettingsAction(private val current: () -> Boolean, private val action: () -> Unit) {
    private var closed = false
    fun open() { if (!closed && current()) action() }
    fun close() { closed = true }
}
