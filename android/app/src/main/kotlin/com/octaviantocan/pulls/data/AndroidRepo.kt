package com.octaviantocan.pulls.data

import android.content.Context
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong

/** Android's existing Repo(Context) entry; the repository and request builders now live in shared-client. */
fun Repo(context: Context): Repo {
    NativeWorkspaceBinding.active?.let { return it.repo }
    val app = context.applicationContext
    return Repo(
        configuration = {
            val config = ServerStore.load(app)
            RepoConfiguration(
                server = config.url,
                headers = config.headers() + Account.headers(app, config.url.trim()),
                engine = config.engine,
                model = config.model,
                effort = AppSettings.get(app).effort,
                generation = RepoIdentity.generation,
            )
        },
        transport = JvmRpcTransport(),
        observer = RpcObserver { name, duration, ok, status -> Analytics.rpc(name, duration, ok, status) },
    )
}

/** Process-local native identity epoch. Account/server changes also cancel presentation caches through listeners. */
object RepoIdentity {
    private val epoch = AtomicLong()
    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    val generation: Long get() = epoch.get()

    fun observeInvalidation(listener: () -> Unit): () -> Unit {
        listeners.add(listener)
        return { listeners.remove(listener) }
    }

    internal fun invalidate() {
        epoch.incrementAndGet()
        listeners.forEach { it() }
    }
}
