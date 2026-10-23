package com.octaviantocan.pulls.ui.settings

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
import com.octaviantocan.pulls.data.Repo
import com.octaviantocan.pulls.data.RepoIdentity
import com.octaviantocan.pulls.ui.review.copyText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** The same shared state across pages, retained only for the current Android source. */
private object AndroidAccountSettings {
    private var epoch = -1L
    private var services: AccountPagesServices? = null
    private var observing = false
    fun get(context: Context): AccountPagesServices {
        if (!observing) {
            observing = true
            RepoIdentity.observeInvalidation {
                services?.close()
                services = null
            }
        }
        val generation = RepoIdentity.generation
        services?.takeIf { epoch == generation && it.current() }?.let { return it }
        services?.close()
        AccountUi.clear()
        val app = context.applicationContext
        val repo = Repo(app)
        val source = runCatching { repo.source() }.getOrNull()
        val current = { generation == RepoIdentity.generation && source != null && repo.isCurrent(source) }
        return SourceAccountPagesServices(repo, { Account.email }, current, {
            try { repo.signOut() } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) {}
            if (!current()) throw CancellationException("The account source changed.")
            Account.signedOut(app)
        }, { copyText(app, it) }, AccountUi).also {
            epoch = generation
            services = it
        }
    }
}

@Composable
internal fun rememberAndroidAccountPagesServices(): AccountPagesServices {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var epoch by remember { mutableLongStateOf(RepoIdentity.generation) }
    DisposableEffect(context) {
        val stop = RepoIdentity.observeInvalidation { scope.launch(Dispatchers.Main.immediate) { epoch = RepoIdentity.generation } }
        epoch = RepoIdentity.generation
        onDispose { stop() }
    }
    return remember(context, epoch) { AndroidAccountSettings.get(context) }
}
