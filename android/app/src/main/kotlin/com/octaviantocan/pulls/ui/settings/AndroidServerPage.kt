package com.octaviantocan.pulls.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.octaviantocan.pulls.data.Repo
import com.octaviantocan.pulls.data.RepoIdentity
import com.octaviantocan.pulls.data.ServerStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** Existing Android preferences and identity observer, with the same shared input page as iOS. */
@Composable
internal fun rememberAndroidServerPageServices(): ServerPageServices {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var epoch by remember { mutableLongStateOf(RepoIdentity.generation) }
    DisposableEffect(context) {
        val stop = observeServerPageEpoch {
            scope.launch(Dispatchers.Main.immediate) { epoch = RepoIdentity.generation }
        }
        onDispose { stop() }
    }
    return remember(context, epoch) {
        val openingEpoch = epoch
        val repo = Repo(context)
        val source = runCatching { repo.source() }.getOrNull()
        val saved = ServerStore.load(context)
        object : ServerPageServices {
            override val configuration = ServerPageConfiguration(saved.url, saved.accessId, saved.accessSecret)
            override fun save(configuration: ServerPageConfiguration): ServerConnectionCheck {
                // Invalid saved URLs can be corrected, but an old page cannot overwrite a new native owner.
                if (openingEpoch != RepoIdentity.generation || source != runCatching { repo.source() }.getOrNull()) {
                    throw CancellationException("The server changed.")
                }
                val current = ServerStore.load(context)
                ServerStore.save(context, current.copy(url = configuration.url, accessId = configuration.accessId, accessSecret = configuration.accessSecret))
                val savedEpoch = RepoIdentity.generation
                val savedSource = repo.source()
                return ServerConnectionCheck {
                    if (RepoIdentity.generation != savedEpoch || !repo.isCurrent(savedSource))
                        throw CancellationException("The server changed.")
                    when (val outcome = checkAccount(context)) {
                        ConnectionCheckResult.Connected -> ServerConnectionOutcome.Connected
                        is ConnectionCheckResult.Failed -> ServerConnectionOutcome.Failed(outcome.problem)
                        ConnectionCheckResult.Superseded -> ServerConnectionOutcome.Superseded
                    }
                }
            }
        }
    }
}

/** Replays after registration, including a source change between the first Compose read and this effect. */
internal fun observeServerPageEpoch(changed: () -> Unit): () -> Unit {
    val stop = RepoIdentity.observeInvalidation(changed)
    changed()
    return stop
}
