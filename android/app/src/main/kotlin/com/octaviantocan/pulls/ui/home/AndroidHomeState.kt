package com.octaviantocan.pulls.ui.home

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.octaviantocan.pulls.data.Account
import com.octaviantocan.pulls.data.Analytics
import com.octaviantocan.pulls.data.Repo
import com.octaviantocan.pulls.data.RepoIdentity
import com.octaviantocan.pulls.data.ServerStore
import com.octaviantocan.pulls.data.sourceStorageNamespace
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** Native private preferences keep the existing keys/JSON; unowned legacy lists are not adopted. */
internal fun deviceStore(context: Context, name: String = "pulls-home"): KeyValueStore {
    val app = context.applicationContext
    Account.load(app)
    val config = ServerStore.load(app)
    val namespace = sourceStorageNamespace(
        config.url, Account.email,
        Account.serverHasAccount != false && Account.headers(app).isNotEmpty(), config.headers(),
    )
    val epoch = RepoIdentity.generation
    val prefs = app.getSharedPreferences("$name-source-v2-${namespace ?: "invalid"}", Context.MODE_PRIVATE)
    val store = object : KeyValueStore {
        override fun get(key: String): String? = prefs.getString(key, null)
        override fun set(key: String, value: String) { prefs.edit().putString(key, value).apply() }
    }
    return GuardedKeyValueStore(store) { namespace != null && RepoIdentity.generation == epoch }
}

/** The route owns common state; identity changes erase it before a new source is composed. */
@Composable
internal fun rememberHomeState(repo: Repo): HomeState {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var epoch by remember { mutableLongStateOf(RepoIdentity.generation) }
    val home = remember(repo, epoch) {
        val source = runCatching { repo.source() }.getOrNull()
        val capturedEpoch = RepoIdentity.generation
        val current = {
            RepoIdentity.generation == capturedEpoch &&
                if (source == null) runCatching { repo.source() }.isFailure else repo.isCurrent(source)
        }
        val inbox = deviceStore(context, "pulls-inbox")
        HomeState(
            MyPullsSync(
                cached = { selected -> repo.myPullsCached(selected)?.asSnapshot() },
                fresh = { selected -> repo.myPulls(selected) },
                storage = deviceStore(context),
                peek = { selected, classify -> repo.myPullsCached(selected, revalidate = false, classify = classify)?.asSnapshot() },
                effects = AndroidHomeEffects,
                currentSource = current,
            ),
            ArchiveStore(inbox), TourIndex(inbox), InboxLayout(inbox, AndroidHomeEffects),
        )
    }
    DisposableEffect(home) {
        val unsubscribe = RepoIdentity.observeInvalidation {
            scope.launch(Dispatchers.Main.immediate) { home.invalidate(); epoch = RepoIdentity.generation }
        }
        // Cover an invalidation between composition and registration as well.
        if (epoch != RepoIdentity.generation) { home.invalidate(); epoch = RepoIdentity.generation }
        onDispose {
            unsubscribe()
            home.sync.invalidate()
            // Same-source Undo callbacks may still persist an archive/tour action after navigation.
            // Their guarded store denies access as soon as the native identity epoch changes.
        }
    }
    return home
}

private object AndroidHomeEffects : HomeEffects {
    override fun loadError(message: String?) = Analytics.error("inbox.load", message)
    override fun action(action: String, value: String) = Analytics.track("inbox.action", "action" to action, "value" to value)
}
