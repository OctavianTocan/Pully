package com.octaviantocan.pulls.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.octaviantocan.pulls.core.LinkPlace
import com.octaviantocan.pulls.core.OpenTarget
import com.octaviantocan.pulls.data.RepoConfiguration
import com.octaviantocan.pulls.data.sourceStorageNamespace
import com.octaviantocan.pulls.ui.home.HomeLink
import com.octaviantocan.pulls.ui.home.HomeLinks

/** A pending intent may survive sign-in, but never an unrelated endpoint, Access policy or signed-in account. */
class IncomingLinkAudience internal constructor(internal val endpoint: String, internal val account: String?) {
    internal fun accepts(opening: IncomingLinkAudience?): Boolean = opening == null ||
        endpoint == opening.endpoint && (opening.account == null || account == opening.account)
    override fun toString() = "IncomingLinkAudience"
}

/** The existing namespace policy excludes session renewal and returns no credentials. */
fun incomingLinkAudience(configuration: RepoConfiguration?, email: String?, eligibleSession: Boolean): IncomingLinkAudience? {
    val config = configuration ?: return null
    val endpoint = sourceStorageNamespace(config.server, null, false, config.headers) ?: return null
    return IncomingLinkAudience(endpoint, email?.trim()?.lowercase()?.takeIf { eligibleSession && it.isNotEmpty() })
}

/** One UI-thread inbox per native application lifetime; it contains no second navigation stack. */
class IncomingLinkInbox {
    class Arrival internal constructor(val text: String?, val aiResult: String?, internal val audience: IncomingLinkAudience?, internal val link: HomeLink? = null)
    class Reopen(val target: OpenTarget, val place: LinkPlace? = null, val aiResult: String? = null)

    var pending by mutableStateOf<Arrival?>(null)
        private set
    var reopened by mutableStateOf<Reopen?>(null)
        private set

    fun receive(text: String, audience: IncomingLinkAudience?, aiResult: String? = null) {
        if (text.isNotBlank()) pending = Arrival(text, aiResult, audience)
    }

    /** Typed share data uses the same audience, awaited sheet exit, route mapping and deduplication as URL delivery. */
    fun receive(link: HomeLink, audience: IncomingLinkAudience?) { pending = Arrival(null, null, audience, link) }

    /** Invalid native envelopes are explicit one-shot events, with no rejected URL or credential retained. */
    fun reject(audience: IncomingLinkAudience?) { pending = Arrival(null, null, audience) }

    fun consume(arrival: Arrival) {
        if (pending === arrival) pending = null
    }

    fun clear() { pending = null; reopened = null }

    /** Pending sign-in intents retain their stamped audience; old private reopen events do not. */
    fun retireSource() { reopened = null }

    /** Called only once the supplied source can actually display the destination. */
    suspend fun openPending(
        audience: IncomingLinkAudience,
        navigation: AppNavigation,
        current: () -> Boolean,
        closeSheets: suspend () -> Unit,
        onInvalid: () -> Unit,
    ): Boolean {
        val arrival = pending ?: return false
        if (!current()) return false
        if (!audience.accepts(arrival.audience)) { consume(arrival); return true }
        val destination = arrival.link?.let { sharedLinkScreen(it) } ?: arrival.text?.let { sharedLinkScreen(it, arrival.aiResult) }
        if (destination != null) closeSheets()
        // Awaiting a sheet exit can replace the account or deliver a newer intent.
        if (!current() || pending !== arrival) return false
        consume(arrival)
        if (destination == null) { onInvalid(); return true }
        val stack = navigation.stack
        if (stack.last() == Screen.Settings) stack.removeAt(stack.lastIndex)
        val top = stack.last()
        if (destination == top || destination is Screen.Review && top is Screen.Review && destination.target.serverTarget == top.target.serverTarget) {
            (top as? Screen.Review)?.let {
                val request = destination as Screen.Review
                reopened = Reopen(it.target, request.place?.place, request.aiResult)
            }
        } else stack.add(destination)
        return true
    }
}

/** The original Android incoming-link mapping, including the exact file/side/line/thread destination. */
fun sharedLinkScreen(text: String, aiResult: String? = null): Screen? = HomeLinks.parseShared(text)?.let { sharedLinkScreen(it, aiResult) }

fun sharedLinkScreen(link: HomeLink, aiResult: String? = null): Screen = when (link) {
    is HomeLink.Target -> Screen.Review(link.target, from = "link", place = link.place?.let(::PlaceRequest), aiResult = aiResult)
    is HomeLink.Repo -> Screen.Repo(link.owner, link.repo)
}
