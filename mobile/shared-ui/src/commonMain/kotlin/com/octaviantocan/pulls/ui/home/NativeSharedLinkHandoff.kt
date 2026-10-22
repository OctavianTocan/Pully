package com.octaviantocan.pulls.ui.home

/** Native storage only: the existing shared namespace is private, and the returned string is canonical link data. */
interface NativeSharedLinkHandoff {
    fun take(namespace: String, server: String): String?
    fun revoke()
}

internal data class ShareImportSource(
    val namespace: String,
    val server: String,
    val epoch: Long,
    val generation: Long,
    val foregroundGrant: Long = 0,
)

/** Retains no route or credential. The app's one existing inbox receives the data after the native read. */
internal class SharedLinkImport(
    private val native: NativeSharedLinkHandoff,
    private val current: () -> ShareImportSource?,
    private val receive: (String) -> Unit,
) {
    private var closed = false

    fun sync() {
        if (closed) return
        val opening = current() ?: return
        val payload = native.take(opening.namespace, opening.server) ?: return
        if (!closed && current() == opening) receive(payload)
    }

    /** Closing a scene preserves its durable same-source record and token. */
    fun close() { closed = true }
}
