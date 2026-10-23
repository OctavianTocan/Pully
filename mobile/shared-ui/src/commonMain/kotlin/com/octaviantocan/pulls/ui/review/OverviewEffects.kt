package com.octaviantocan.pulls.ui.review

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import com.octaviantocan.pulls.core.OpenTarget
import com.octaviantocan.pulls.ui.home.HomeLifecycleObserver
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first

/** One incoming-link event identity, adapted by the host without storing a second navigation stack. */
class OverviewReopen(val target: OpenTarget)

@Composable
fun OverviewEffects(session: ReviewSession, top: ReviewTopUi, data: PrOverviewData?, reopened: OverviewReopen?) {
    val overview = data?.overview
    LaunchedEffect(data, top.polls) {
        val wait = data?.let(::overviewRevalidateDelayMs) ?: return@LaunchedEffect
        if (top.polls >= 20) return@LaunchedEffect
        delay(wait)
        session.reloadOverview()
        top.polls++
    }
    val observedState = session.prOpened?.takeUnless { it.stale }?.state
    LaunchedEffect(observedState) {
        if (observedState in listOf("open", "draft", "closed", "merged") && overview != null && observedState != overview.state) {
            session.reloadOverview()
        }
    }
    var away by remember { mutableStateOf(false) }
    var returns by remember { mutableIntStateOf(0) }
    val native = LocalOverviewServices.current.native
    DisposableEffect(session, native) {
        val subscription = native.observeLifecycle(object : HomeLifecycleObserver {
            override fun stopped() { away = true }
            override fun resumed() = Unit
            override fun started() {
                if (away) {
                    away = false
                    top.polls = 0
                    returns++
                    session.reloadOverview()
                }
            }
        })
        onDispose { subscription.cancel() }
    }
    // A link to this pull request while it is on screen opens nothing new, so the page reads it again; one that came
    // before this page did is not read twice.
    val seen = remember(session) { reopened }
    LaunchedEffect(reopened) {
        if (reopened == null || reopened === seen || reopened.target.serverTarget != session.target.serverTarget) return@LaunchedEffect
        top.polls = 0
        session.reloadOverview()
    }
    val stale = isStale(session, overview)
    // A reload that fails leaves the page stale, so it is tried a few more times, and again when the app comes back.
    LaunchedEffect(stale, session.settings.autoRefresh, session.selection, returns) {
        if (!stale || !session.settings.autoRefresh || session.selection != null) return@LaunchedEffect
        for (wait in STALE_RETRY_MS) {
            snapshotFlow { session.loading }.first { !it }
            if (!isStale(session, overview)) return@LaunchedEffect
            session.refresh()
            delay(wait)
        }
    }
}

/** How long the page waits after each automatic reload of new commits before it tries again, while they have not loaded. */
private val STALE_RETRY_MS = listOf(2_000L, 8_000L, 30_000L)
