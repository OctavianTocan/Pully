package com.octaviantocan.pulls.ui.review

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.octaviantocan.pulls.data.Analytics
import com.octaviantocan.pulls.data.AppSettings
import com.octaviantocan.pulls.data.Repo
import com.octaviantocan.pulls.data.TourStore
import com.octaviantocan.pulls.ui.IncomingLinks
import com.octaviantocan.pulls.ui.PlaceRequest
import com.octaviantocan.pulls.ui.design.PageBack
import com.octaviantocan.pulls.ui.design.PageMotion
import com.octaviantocan.pulls.ui.home.TourIndex
import com.octaviantocan.pulls.ui.home.deviceStore
import com.octaviantocan.pulls.ui.settings.rememberAndroidModelSettingsServices

/** Android services only; the complete page/session/sheet state remains in the shared review frame. */
@Composable
internal fun rememberAndroidReviewFrameServices(): ReviewFrameServices {
    val context = LocalContext.current
    val overview = rememberAndroidOverviewServices()
    val models = rememberAndroidModelSettingsServices()
    return remember(context, overview, models) {
        val repo = Repo(context)
        val source = runCatching { repo.source() }.getOrNull()
        val current = { source != null && repo.isCurrent(source) }
        ReviewFrameServices(
            reviewSessionCache(context), overview, TourIndex(deviceStore(context, "pulls-inbox")),
            current = current,
            createSession = { target, scope, from -> androidReviewSession(context, target, repo, scope, AppSettings.get(context), TourStore.get(context), from) },
            preview = { target -> pullPreview(context, target) }, nowMillis = System::currentTimeMillis,
            track = { name, props -> Analytics.track(name, *props) }, back = AndroidReviewFrameBack,
            reopened = {
                val event = IncomingLinks.reopened
                remember(event) { event?.let { ReviewFrameReopen(it.target, it.place?.let(::PlaceRequest), it.aiResult) } }
            },
            actions = SharedReviewFrameActions(models, overview.native, current, checks = { pull, canFollow ->
                com.octaviantocan.pulls.notifications.ChecksWatchRow(pull, canFollow)
            }),
            commands = SharedReviewFrameCommands(overview.native, current), canReadTour = true,
        )
    }
}

internal object AndroidReviewFrameBack : ReviewFrameBack {
    @Composable override fun Page(motion: PageMotion, enabled: Boolean, onBack: () -> Unit) = PageBack(motion, enabled, onBack)
    @Composable override fun Priority(enabled: Boolean, onBack: () -> Unit) {
        // motion-ok: Back restores an Ask sheet; it does not move a page (rule 108).
        androidx.activity.compose.BackHandler(enabled, onBack)
    }
}
