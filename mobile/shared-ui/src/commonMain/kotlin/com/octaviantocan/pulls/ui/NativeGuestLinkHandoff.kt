package com.octaviantocan.pulls.ui

/** A public fallback can hand the same intent back to verification without widening its audience. */
class NativeGuestLinkHandoff(
    private val incoming: IncomingLinkInbox,
    private val navigation: AppNavigation,
    private val current: () -> Boolean,
) {
    private var delivered: Pair<Screen, IncomingLinkInbox.Arrival>? = null

    fun committed(screen: Screen, arrival: IncomingLinkInbox.Arrival) {
        if (!current() || incoming.pending !== arrival) return
        val displayed = navigation.stack.lastOrNull()?.takeIf { it == screen } ?: return
        incoming.consume(arrival)
        delivered = displayed to arrival
    }

    fun prepareExistingEntry() {
        if (!current() || incoming.pending != null) return
        val (screen, arrival) = delivered ?: return
        if (navigation.stack.lastOrNull() !== screen) return
        arrival.text?.let { incoming.receive(it, arrival.audience, arrival.aiResult) }
    }

    fun clear() { delivered = null }
}
