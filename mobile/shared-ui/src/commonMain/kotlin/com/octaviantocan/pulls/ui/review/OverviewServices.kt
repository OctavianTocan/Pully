package com.octaviantocan.pulls.ui.review

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import com.octaviantocan.pulls.data.Brief
import com.octaviantocan.pulls.data.PullSizes
import com.octaviantocan.pulls.ui.home.NativeHomeActions

/** Required host services; one native actions port owns clipboard, links and lifecycle. */
interface OverviewServices {
    val native: NativeHomeActions
    val sizes: PullSizes.SourceLease
    val ai: OverviewAiContent
    val canEditText: Boolean

    /** Cancels the inline field before Back can leave its review route. */
    @Composable fun EditBack(onBack: () -> Unit)

    /** Uses the host's existing preference-gated, debounced search analytics. */
    @Composable fun TrackSearch(scope: String, query: String, count: Int)
}

val LocalOverviewServices = staticCompositionLocalOf<OverviewServices> {
    error("The review host must install its native Overview services")
}

/**
 * Ordered extraction boundary for the existing AI renderers. A supported method must render the real content;
 * hosts without those renderers must declare reading/actions unavailable before exposing this screen.
 */
interface OverviewAiContent {
    val canReadResults: Boolean
    val canStartPaidWork: Boolean

    @Composable fun Summary(session: ReviewSession, text: String, onCite: (Cite) -> Unit)
    @Composable fun Brief(session: ReviewSession, brief: Brief, onCite: (Cite) -> Unit, modifier: Modifier)
    @Composable fun NeedsHuman(session: ReviewSession, onCite: (Cite) -> Unit, onSheet: (String) -> Unit)
    @Composable fun SharedBanner(session: ReviewSession, modifier: Modifier)
    @Composable fun FileNote(session: ReviewSession, text: String)
}
