package com.octaviantocan.pulls.ios

import com.octaviantocan.pulls.data.PersonalAccountRuntime
import com.octaviantocan.pulls.data.DarwinRpcTransport
import com.octaviantocan.pulls.data.PersonalAuthError
import com.octaviantocan.pulls.data.PersonalAuthOrigin
import com.octaviantocan.pulls.data.PersonalAuthProblem
import com.octaviantocan.pulls.data.PersonalSessionBlobStorage
import com.octaviantocan.pulls.data.PersonalSessionVault
import com.octaviantocan.pulls.data.RpcTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import platform.Foundation.NSDate
import platform.Foundation.NSThread

/** Swift errors must cross as Kotlin exceptions, never an empty successful secure read/write. */
interface NativePersonalSessionStorage {
    @Throws(Exception::class)
    fun read(): ByteArray?

    @Throws(Exception::class)
    fun write(contents: ByteArray)
}

/** One application-wide owner, including across scenes. No client model crosses the Cocoa API. */
class IosPersonalSessionStore internal constructor(
    private val storage: NativePersonalSessionStorage,
    configuredOrigin: String,
    private val transport: RpcTransport,
) {
    constructor(storage: NativePersonalSessionStorage, configuredOrigin: String) :
        this(storage, configuredOrigin, DarwinRpcTransport())

    private val vault = PersonalSessionVault(object : PersonalSessionBlobStorage {
        override fun read(): ByteArray? { requireMainThread(); return storage.read() }
        override fun write(contents: ByteArray) { requireMainThread(); storage.write(contents) }
    })
    private val cleanupScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val origin = configuredOrigin.takeIf { it.isNotBlank() }?.let {
        try { PersonalAuthOrigin.parse(it) } catch (_: IllegalArgumentException) { null }
    }
    private var epoch = 0L
    private val observers = mutableMapOf<Any, () -> Unit>()
    val configured: Boolean get() = origin != null

    init { requireMainThread() }

    /** Probe the native store on every call, including after a previously cached successful read. */
    @Throws(Exception::class)
    fun verifyStorage() {
        requireMainThread()
        val bytes = try { storage.read() } catch (_: Exception) { throw PersonalAuthError(PersonalAuthProblem.Unavailable) }
        try {
            if (bytes != null && (bytes.isEmpty() || bytes.size > PersonalSessionVault.MAX_BYTES))
                throw PersonalAuthError(PersonalAuthProblem.Unavailable)
        } finally { bytes?.fill(0) }
        vault.pending()
    }

    internal fun observeIdentityChanged(observer: () -> Unit): () -> Unit {
        requireMainThread()
        val key = Any()
        observers[key] = observer
        return { requireMainThread(); observers.remove(key); Unit }
    }

    internal fun open(parentScope: CoroutineScope, current: () -> Boolean): PersonalAccountRuntime? {
        requireMainThread()
        val origin = origin ?: return null
        val opening = epoch
        return PersonalAccountRuntime(
            origin, vault, parentScope, cleanupScope,
            nowSeconds = { NSDate().timeIntervalSince1970.toLong() },
            isCurrent = { epoch == opening && current() },
            onIdentityChanged = {
                requireMainThread()
                epoch++
                // Every old runtime is already revoked, including another scene whose cleanup fails.
                observers.values.toList().forEach { observer -> try { observer() } catch (_: Exception) { } }
            },
            transport = transport,
        )
    }

    private fun requireMainThread() { check(NSThread.isMainThread) { "Personal storage requires the UI thread." } }
}
