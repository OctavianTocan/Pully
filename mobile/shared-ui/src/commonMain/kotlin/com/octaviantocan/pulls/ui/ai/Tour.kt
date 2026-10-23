package com.octaviantocan.pulls.ui.ai

import com.octaviantocan.pulls.data.isTourPreview
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.octaviantocan.pulls.core.OpenTarget
import com.octaviantocan.pulls.core.softBreaks
import com.octaviantocan.pulls.data.TourBlock
import com.octaviantocan.pulls.data.Tour
import com.octaviantocan.pulls.data.TourSource
import com.octaviantocan.pulls.sound.cueClickable
import com.octaviantocan.pulls.ui.design.CtaButton
import com.octaviantocan.pulls.ui.design.FloatIcon
import com.octaviantocan.pulls.ui.design.LocalPullsColors
import com.octaviantocan.pulls.ui.design.Lu
import com.octaviantocan.pulls.ui.design.LuIcon
import com.octaviantocan.pulls.ui.design.ModalReveal
import com.octaviantocan.pulls.ui.design.CodePlace
import com.octaviantocan.pulls.ui.design.LocalCodeBlock
import com.octaviantocan.pulls.ui.design.LocalMentions
import com.octaviantocan.pulls.ui.design.Markdown
import com.octaviantocan.pulls.ui.design.MarkdownPreview
import com.octaviantocan.pulls.ui.design.Mentions
import com.octaviantocan.pulls.ui.design.fileStems
import com.octaviantocan.pulls.data.ReviewFile
import com.octaviantocan.pulls.ui.design.PullsRadius
import com.octaviantocan.pulls.ui.design.PullsType
import com.octaviantocan.pulls.ui.design.Skeleton
import com.octaviantocan.pulls.ui.design.headerTopPadding
import com.octaviantocan.pulls.ui.review.ReviewSession
import com.octaviantocan.pulls.ui.review.LocalReviewActionReadiness
import com.octaviantocan.pulls.ui.review.guardedStop
import com.octaviantocan.pulls.ui.review.openSource
import kotlinx.serialization.json.put

/*
 * The Tour tab (phone-only): an AI walkthrough of the PR with no diffs. The server plans the
 * sections, writes each one in parallel and saves the tour per head commit; this draws it with the
 * app's tokens. Visuals are native: monospace sketches as text, Mermaid as an SVG the server
 * rendered, drawn as native vector data (no WebView).
 */

/**
 * Whether a tour is on its way: its own task runs, it waits for an analysis, or the running analysis writes one. A queued
 * tour counts until [followQueuedTour] starts it or drops it, so the frame between the analysis ending and the tour
 * starting still shows the work log, not the empty page.
 */
fun tourComing(session: ReviewSession): Boolean =
    session.taskFor("tour")?.running == true || session.tourQueued ||
        session.taskFor("analyze")?.let { it.running && "tour" in it.then } == true

/**
 * No tour yet, on a page opened straight on the tour (from the Tours tab or a link): the one action is the tour icon
 * sheet's model choice and Generate tour (rule 113). A failed tour says so, with the try-again arrow (rule 87). No word on how the
 * tour is made: what it does is enough (rule 6).
 */
@Composable
fun TourEmpty(session: ReviewSession, error: String?, modifier: Modifier, onGenerate: (() -> Unit)?) {
    val c = LocalPullsColors.current
    val capabilities by rememberUpdatedState(LocalAiCapabilities.current)
    val readiness = LocalReviewActionReadiness.current
    val owner = session.captureTourRead()
    val generate: (() -> Unit)? = onGenerate?.takeIf { capabilities.canTour }?.let { action ->
        { if (readiness.ai(capabilities).canTour && session.isCurrentTourRead(owner)) action() }
    }
    Column(
        modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Take a tour of this pull request", style = PullsType.xxl, color = c.text)
        Text(
            "AI reads the change and the code around it and walks you through what you need to know, section by section: the flow, the pieces, and why. No diffs.",
            style = PullsType.read, color = c.muted,
        )
        if (error != null) ErrorBox("The tour failed", error, onRetry = generate, retryLabel = "Write the tour again")
        else if (generate != null) CtaButton(
            "Generate tour",
            generate,
            Modifier.padding(top = 4.dp),
            enabled = session.key != null && session.taskFor("analyze")?.running != true,
        )
    }
}

/**
 * The first tour being written: its work log, whose orb is the Stop button (rule 87). Before the tour's own task starts,
 * the analysis that leads to it is what runs, so its log is shown as reading the change.
 */
@Composable
fun TourRunning(session: ReviewSession, modifier: Modifier) {
    val tour = session.taskFor("tour")?.takeIf { it.running }
    val task = tour ?: session.taskFor("analyze") ?: return
    val kind = if (tour != null) "tour" else "analyze"
    val capabilities = LocalAiCapabilities.current
    val canStop = if (tour != null) capabilities.canTour else capabilities.canAnalyze
    val owner = session.captureTourRead()
    val guarded = guardedStop(session, listOf(kind), task, task.running && canStop,
        permitted = { session.isCurrentTourRead(owner) })
    val stop = guarded.takeIf { canStop }
    // Every step, the log growing downward in the sheet, with no "+4 more" squeezing the older ones away ("this could just
    // go down": recording 16 Oct 13:06, 06:02 to 06:30; O14). No "Wrote 3 of 6 stops" line: the stops appear on the
    // page as they are written (K13).
    AiActivityLogFull(
        task.activities.filterNot { it.id == "tour-write" || isTourPreview(it) }, running = true, modifier.fillMaxWidth(), startedAt = task.startedAt.takeIf { it > 0 },
        compact = true, workingVerb = if (tour != null) "Building the tour" else "Reading the change", onStop = stop, everyStep = true, reconnecting = task.id in session.taskObservationErrors,
    )
}



/**
 * Where to check the stop: each a tappable `file:line` on its own line, the reason under it at the full width of the card.
 * Side by side, a long file name left the reason a column three or four letters wide (F62). The name wraps between its
 * words, never cut (rule 2). A long list shows its first [SOURCES_SHOWN], then a hairline and "Show N more", as the
 * Overview's long lists do.
 */
@Composable
fun Sources(sources: List<TourSource>, onOpen: (TourSource) -> Unit) {
    val c = LocalPullsColors.current
    var all by remember(sources) { mutableStateOf(false) }
    val shown = if (all) sources else sources.take(SOURCES_SHOWN)
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text("In the code", style = PullsType.sm.copy(fontWeight = FontWeight.Medium), color = c.muted)
        shown.forEach { source ->
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(PullsRadius.lg))
                    .cueClickable(role = Role.Button, onClickLabel = "Open ${source.path}") { onOpen(source) }.padding(vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    softBreaks(source.path.substringAfterLast('/')) + if (source.line > 0) ":${source.line}" else "",
                    style = PullsType.sm.copy(fontFamily = PullsType.display, fontWeight = FontWeight.Medium),
                    color = c.activeText,
                )
                if (source.why.isNotBlank()) Text(source.why, Modifier.fillMaxWidth(), style = PullsType.sm, color = c.muted)
            }
        }
        if (shown.size < sources.size) {
            Box(Modifier.fillMaxWidth().height(1.dp).background(c.borderMute))
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(PullsRadius.lg))
                    .cueClickable(role = Role.Button, onClickLabel = "Show every place") { all = true }.padding(vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text("Show ${sources.size - shown.size} more", style = PullsType.sm.copy(fontWeight = FontWeight.Medium), color = c.muted)
                LuIcon(Lu.chevd, tint = c.muted, size = 16.dp)
            }
        }
    }
}

/** How many places "In the code" lists before "Show N more". */
private const val SOURCES_SHOWN = 5

@Composable
fun Glossary(entries: List<Pair<String, String>>, modifier: Modifier) {
    val c = LocalPullsColors.current
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Glossary", style = PullsType.base.copy(fontWeight = FontWeight.SemiBold), color = c.text)
        entries.forEach { (term, meaning) ->
            Column(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                // The model writes terms as inline code; the glossary already sets them in mono, so the backticks go (rule 95).
                Text(term.replace("`", ""), style = PullsType.sm.copy(fontFamily = PullsType.mono, fontWeight = FontWeight.Medium), color = c.text)
                Markdown(meaning, style = PullsType.sm.copy(color = c.fade))
            }
        }
    }
}

/** Starts a queued tour once the analysis it waited for is done, and drops it if that analysis did not finish. */
@Composable
fun followQueuedTour(session: ReviewSession, start: (() -> Unit)?) {
    val capabilities = LocalAiCapabilities.current
    val owner = session.captureView()
    val status = session.taskFor("analyze")?.status
    LaunchedEffect(status, session.tourQueued, owner, capabilities, start) {
        if (!capabilities.canTour || start == null || owner == null || !session.isCurrentView(owner)) return@LaunchedEffect
        if (!session.tourQueued || status == "running") return@LaunchedEffect
        session.tourQueued = false
        if (status == "done") start()
    }
}

