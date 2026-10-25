package com.octaviantocan.pulls.data

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.octaviantocan.pulls.update.UpdateChecks

/** Process-local, fixed-session presentation adapter. Credentials never enter legacy preferences. */
class NativeWorkspaceBinding private constructor(
    val owner: PersonalWorkspaceOwner,
    val signOut: () -> Unit,
    private val context: Context,
) {
    val namespace = requireNotNull(sourceStorageNamespace(owner.server, owner.accountId, true))
    private var transport = requireNotNull(owner.foregroundWorkspace())
    // Compatibility access for native adapters: a pause returns a retired capability, never legacy authority.
    val workspace: PersonalWorkspace get() = owner.foregroundWorkspace() ?: transport
    val repo = owner.repository(
        engine = { ServerStore.load(context).engine }, model = { ServerStore.load(context).model },
        effort = { AppSettings.get(context).effort },
        admitted = { active === this && owner.current },
        observer = RpcObserver { name, duration, ok, status -> if (active === this && owner.current) Analytics.rpc(name, duration, ok, status) },
    )
    private var stop: (() -> Unit)? = null
    private var stopTransport: (() -> Unit)? = null
    private var usage: NativeWorkspaceAnalytics? = null
    val analytics: NativeWorkspaceAnalytics? get() = usage

    private val foregroundObservers = mutableSetOf<(Boolean) -> Unit>()

    /** Native read owners pause synchronously and replay admission after the replacement transport is installed. */
    fun observeForeground(changed: (Boolean) -> Unit): () -> Unit {
        if (active !== this || !owner.current) { changed(false); return {} }
        foregroundObservers += changed
        changed(transport.current)
        return { foregroundObservers -= changed }
    }

    private fun publishForeground(ready: Boolean) {
        for (changed in foregroundObservers.toList()) {
            if (changed in foregroundObservers)
                changed(ready && active === this && owner.current && transport.current)
        }
    }

    /** Replace transport/upload admission and resume observers without replacing presentation ownership. */
    fun syncForeground() {
        if (active !== this || !owner.current) return
        val next = owner.foregroundWorkspace() ?: return
        if (transport === next && usage != null) return
        stopTransport?.invoke()
        usage?.close()
        transport = next
        usage = NativeWorkspaceAnalytics(context, next, namespace) { active === this && next.current }
        stopTransport = next.onRetired {
            publishForeground(false)
            usage?.close(); usage = null
            AiWork.clear(context)
        }
        publishForeground(true)
        // ON_START can precede personal foreground admission, and a browser pause has no ON_START.
        if (active === this && owner.current && next.current) AiWork.foreground(context)
    }

    fun close() {
        stop?.invoke(); stop = null
        stopTransport?.invoke(); stopTransport = null
        if (active !== this) return
        publishForeground(false)
        foregroundObservers.clear()
        usage?.close(); usage = null
        AiWork.clear(context)
        RepoIdentity.invalidate()
        active = null
        com.octaviantocan.pulls.sound.Sounds.refresh()
        UpdateChecks.sync(context)
    }

    companion object {
        var active by mutableStateOf<NativeWorkspaceBinding?>(null)
            private set

        fun mount(context: Context, owner: PersonalWorkspaceOwner, signOut: () -> Unit): NativeWorkspaceBinding {
            active?.close()
            return NativeWorkspaceBinding(owner, signOut, context.applicationContext).also { binding ->
                active = binding
                RepoIdentity.invalidate()
                binding.stop = owner.onRetired(binding::close)
                binding.syncForeground()
                com.octaviantocan.pulls.sound.Sounds.refresh()
                UpdateChecks.sync(context)
            }
        }
    }
}

/** All native caches use the immutable workspace account, never its mutable GitHub login. */
fun nativeStorageNamespace(context: Context): String? {
    NativeWorkspaceBinding.active?.let { return it.namespace.takeIf { _ -> it.owner.current } }
    Account.load(context)
    val config = ServerStore.load(context)
    return sourceStorageNamespace(config.url, Account.email, Account.eligible(context), config.headers())
}
