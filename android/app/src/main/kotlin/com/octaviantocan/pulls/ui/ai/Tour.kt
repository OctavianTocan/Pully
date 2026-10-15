package com.octaviantocan.pulls.ui.ai

import android.content.Context
import android.graphics.Picture
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
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.caverock.androidsvg.SVG
import com.octaviantocan.pulls.core.OpenTarget
import com.octaviantocan.pulls.core.softBreaks
import com.octaviantocan.pulls.data.TourBlock
import com.octaviantocan.pulls.data.TourSource
import com.octaviantocan.pulls.sound.cueClickable
import com.octaviantocan.pulls.ui.design.CtaButton
import com.octaviantocan.pulls.ui.design.FloatIcon
import com.octaviantocan.pulls.ui.design.LocalPullsColors
import com.octaviantocan.pulls.ui.design.Lu
import com.octaviantocan.pulls.ui.design.ModalReveal
import com.octaviantocan.pulls.ui.design.CodePlace
import com.octaviantocan.pulls.ui.design.LocalCodeBlock
import com.octaviantocan.pulls.ui.design.LocalMentions
import com.octaviantocan.pulls.ui.design.Markdown
import com.octaviantocan.pulls.ui.design.MarkdownPreview
import com.octaviantocan.pulls.ui.design.Mentions
import com.octaviantocan.pulls.ui.design.PullsRadius
import com.octaviantocan.pulls.ui.design.PullsType
import com.octaviantocan.pulls.ui.design.Skeleton
import com.octaviantocan.pulls.ui.design.headerTopPadding
import com.octaviantocan.pulls.ui.review.ReviewSession
import com.octaviantocan.pulls.ui.review.guardedStop
import com.octaviantocan.pulls.ui.review.openSource
import androidx.compose.ui.platform.LocalContext
import kotlinx.serialization.json.put

/*
 * The Tour tab (phone-only): an AI walkthrough of the PR with no diffs. The server plans the
 * sections, writes each one in parallel and saves the tour per head commit; this draws it with the
 * app's tokens. Visuals are native: monospace sketches as text, Mermaid as an SVG the server
 * rendered, drawn with AndroidSVG (no WebView).
 */

private val SHAPE_LABELS = mapOf("calls" to "Call tree", "tree" to "Files", "pseudo" to "Pseudocode", "code" to "Code", "flow" to "Flow")

/** Starts a tour in the reader's language, unless one is already running. */
fun startTour(session: ReviewSession) {
    if (session.taskFor("tour")?.running == true) return
    session.startTask("tour", { put("locale", session.settings.locale) })
}

/**
 * Writes a tour: with no analysis yet it runs the last choice with the tour in it, and after one it writes the tour
 * alone. While an analysis runs, the tour waits for it: one that writes a tour needs nothing more, and one that does
 * not queues the tour, which [followQueuedTour] starts when the analysis is done.
 */
internal fun generateTour(session: ReviewSession, context: Context) {
    val analysis = session.taskFor("analyze")
    when {
        analysis?.running == true -> if ("tour" !in analysis.then) session.tourQueued = true
        session.hasAiResult -> startTour(session)
        else -> session.runAnalyze(context, session.settings.analyzeChoice.copy(tour = true))
    }
}

/** Whether a tour is on its way: its own task runs, it waits for the running analysis, or that analysis writes one. */
internal fun tourComing(session: ReviewSession): Boolean =
    session.taskFor("tour")?.running == true ||
        session.taskFor("analyze")?.let { it.running && (session.tourQueued || "tour" in it.then) } == true

/** Starts a queued tour once the analysis it waited for is done, and drops it if that analysis did not finish. */
@Composable
internal fun followQueuedTour(session: ReviewSession) {
    val status = session.taskFor("analyze")?.status
    LaunchedEffect(status, session.tourQueued) {
        if (!session.tourQueued || status == "running") return@LaunchedEffect
        session.tourQueued = false
        if (status == "done") startTour(session)
    }
}

/**
 * No tour yet, on a page opened straight on the tour (from the Tours tab or a link): the one action is the tour icon
 * sheet's Generate tour (rule 113), with no analysis the last choice with the tour in it, after one the tour alone. A failed tour says so, with the try-again arrow (rule 87). No word on how the
 * tour is made: what it does is enough (rule 6).
 */
@Composable
internal fun TourEmpty(session: ReviewSession, error: String?, modifier: Modifier) {
    val c = LocalPullsColors.current
    val context = LocalContext.current
    Column(
        modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Take a tour of this pull request", style = PullsType.xxl, color = c.text)
        Text(
            "AI reads the change and the code around it and walks you through what you need to know, section by section: the flow, the pieces, and why. No diffs.",
            style = PullsType.read, color = c.muted,
        )
        if (error != null) ErrorBox("The tour failed", error, onRetry = { startTour(session) }, retryLabel = "Write the tour again")
        else CtaButton(
            "Generate tour",
            { generateTour(session, context) },
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
internal fun TourRunning(session: ReviewSession, modifier: Modifier) {
    val tour = session.taskFor("tour")?.takeIf { it.running }
    val task = tour ?: session.taskFor("analyze") ?: return
    val kind = if (tour != null) "tour" else "analyze"
    val stop = guardedStop(session, listOf(kind), task.running) { session.cancelTask(task.id) }
    AiActivityLogFull(
        task.activities, running = true, modifier.fillMaxWidth(), startedAt = task.startedAt.takeIf { it > 0 },
        workingVerb = if (tour != null) "Building the tour" else "Reading the change", onStop = stop,
    )
}

/**
 * Mentions in AI text go to their place: a changed file opens the diff on the line, any other
 * file opens through [open] (rule 96). The resolver takes a whole path or the end of one.
 */
internal fun mentionsFor(session: ReviewSession, open: (CodePlace) -> Unit): Mentions = Mentions(resolve = { name -> resolveMention(session, name) }, open = open, repo = reviewRepo(session))

/** The `owner/name` of the repository under review, or null for a local branch. */
internal fun reviewRepo(session: ReviewSession): String? = when (val target = session.target) {
    is OpenTarget.Pull -> "${target.owner}/${target.repo}"
    is OpenTarget.Compare -> "${target.owner}/${target.repo}"
    is OpenTarget.Commit -> "${target.owner}/${target.repo}"
    else -> null
}

/** The changed file a mention names, by its whole path or the end of it, or null. */
internal fun resolveMention(session: ReviewSession, name: String): String? =
    session.files.firstOrNull { it.path == name }?.path ?: session.files.firstOrNull { it.path.endsWith("/$name") }?.path

/** What the AI wrote, with its mentions as links, its code as the diff's code view and its sketches drawn (rules 95 and 96). */
@Composable
internal fun ProvideAiText(session: ReviewSession, open: (CodePlace) -> Unit, content: @Composable () -> Unit) {
    val latest by rememberUpdatedState(open)
    val mentions = remember(session) { mentionsFor(session) { place -> latest(place) } }
    val code = remember(session) { @Composable { body: String, lang: String -> AiFence(session, body, lang) } }
    CompositionLocalProvider(LocalMentions provides mentions, LocalCodeBlock provides code, content = content)
}

/**
 * Markdown the AI wrote, on a page that also shows people's words (the Diff's notes, findings and answers, the
 * Overview's file notes, the findings sheet): its mentions are links that open their place (rule 96) and its code is
 * the diff's code view (rule 95), while a reviewer's comment beside it stays as they wrote it. Inside [ProvideAiText]
 * it is plain [Markdown]. A [maxLines] makes it a preview of that many lines.
 */
@Composable
internal fun AiMarkdown(session: ReviewSession, source: String, modifier: Modifier = Modifier, style: TextStyle = PullsType.sm, maxLines: Int = Int.MAX_VALUE) {
    val uri = LocalUriHandler.current
    val text = @Composable { if (maxLines == Int.MAX_VALUE) Markdown(source, modifier, style) else MarkdownPreview(source, modifier, style, maxLines) }
    if (LocalMentions.current != null) text()
    else ProvideAiText(session, { place -> openSource(session, TourSource(place.path, place.line, ""), uri::openUri) }, text)
}

@Composable
internal fun TourBlockView(session: ReviewSession, block: TourBlock) {
    val c = LocalPullsColors.current
    when (block.kind) {
        "text" -> Markdown(block.text, style = PullsType.read.copy(color = c.text))
        "shape" -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                // The kind and its caption at body size: a 10sp label was below the floor of rule 14.
                Text(SHAPE_LABELS[block.lang] ?: "Sketch", style = PullsType.sm.copy(fontWeight = FontWeight.Medium), color = c.accentTeal)
                if (block.text.isNotBlank()) Text(block.text, style = PullsType.sm, color = c.muted)
            }
            // A flow is drawn from its hops, a call tree and a file tree as trees, pseudocode and code as the diff's code view (rule 95).
            AiFence(session, block.body, if (block.lang == "code") block.text else block.lang)
        }
        "diagram" -> Diagram(session, block)
    }
}

private val svgCache = mutableStateMapOf<String, Result<String>>()

/** A Mermaid diagram the server rendered to SVG, drawn natively; tap opens it full screen with pinch zoom. */
@Composable
private fun Diagram(session: ReviewSession, block: TourBlock) {
    val c = LocalPullsColors.current
    val key = "${c.dark}:${block.body}"
    LaunchedEffect(key) {
        if (svgCache[key] == null) svgCache[key] = runCatching { session.repo.mermaidSvg(block.body, c.dark) }
            .onFailure { android.util.Log.w("pulls", "mermaid-svg failed", it) }
    }
    val result = svgCache[key]
    val svg = remember(result) {
        result?.getOrNull()?.let { text -> runCatching { SVG.getFromString(androidSvgSafe(text)) }.onFailure { android.util.Log.w("pulls", "svg parse failed", it) }.getOrNull() }
    }
    var full by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Diagram", style = PullsType.sm.copy(fontWeight = FontWeight.Medium), color = c.accentTeal)
            if (block.text.isNotBlank()) Text(block.text, style = PullsType.sm, color = c.muted)
        }
        when {
            svg != null -> androidx.compose.foundation.layout.BoxWithConstraints(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(PullsRadius.md)).border(1.dp, c.border, RoundedCornerShape(PullsRadius.md))
                    .cueClickable(onClickLabel = "Open the diagram") { full = true }.padding(8.dp),
                contentAlignment = Alignment.Center,
            ) {
                // Natural size (1 SVG px = 1.2dp), shrunk to fit the width and at most 480dp tall; tap for full screen.
                val box = svg.documentViewBox
                val w0 = (box?.width() ?: 400f) * 1.2f
                val h0 = (box?.height() ?: 250f) * 1.2f
                val fit = minOf(1f, maxWidth.value / w0, 480f / h0)
                SvgView(svg, Modifier.size((w0 * fit).dp, (h0 * fit).dp))
            }
            result == null -> Skeleton(Modifier.fillMaxWidth().height(160.dp).clip(RoundedCornerShape(PullsRadius.xl)))
            // Could not render: the source still says what the diagram meant.
            else -> AiCodeView(session, block.body, "")
        }
    }
    ModalReveal(full && svg != null, { full = false }) {
        var scale by remember { mutableStateOf(1f) }
        var offset by remember { mutableStateOf(androidx.compose.ui.geometry.Offset.Zero) }
        val state = rememberTransformableState { zoom, pan, _ -> scale = (scale * zoom).coerceIn(1f, 6f); offset += pan }
        Box(Modifier.fillMaxSize().background(c.base)) {
            svg?.let { drawing ->
                SvgView(drawing, Modifier.fillMaxSize().aspectRatio(ratioOf(drawing)).transformable(state).graphicsLayer { scaleX = scale; scaleY = scale; translationX = offset.x; translationY = offset.y }.padding(16.dp))
            }
            FloatIcon(Lu.close, "Close", { full = false }, Modifier.align(Alignment.TopEnd).padding(top = headerTopPadding(), end = 16.dp))
        }
    }
}

/**
 * What AndroidSVG 1.4 cannot read in beautiful-mermaid's output: the SVG2 marker orientation
 * `auto-start-reverse` (the start arrow is already drawn reversed) and the `<style>` block.
 */
internal fun androidSvgSafe(svg: String): String = svg
    .replace("orient=\"auto-start-reverse\"", "orient=\"auto\"")
    .replace(Regex("<style>[\\s\\S]*?</style>"), "")
    // The removed style set the font; without it AndroidSVG falls back to serif.
    .replace("<text ", "<text font-family=\"sans-serif\" ")

private fun ratioOf(svg: SVG): Float = svg.documentViewBox?.let { if (it.height() > 0) (it.width() / it.height()).coerceIn(0.2f, 5f) else null } ?: 1.6f

/** Draws an AndroidSVG document scaled to fit, keeping its aspect ratio. */
@Composable
private fun SvgView(svg: SVG, modifier: Modifier) {
    val box = svg.documentViewBox
    val picture: Picture = remember(svg) {
        val w = (box?.width() ?: 400f).toInt().coerceAtLeast(1)
        val h = (box?.height() ?: 250f).toInt().coerceAtLeast(1)
        svg.renderToPicture(w, h)
    }
    Canvas(modifier) {
        val s = minOf(size.width / picture.width, size.height / picture.height)
        val dx = (size.width - picture.width * s) / 2f
        val dy = (size.height - picture.height * s) / 2f
        drawIntoCanvas { canvas ->
            canvas.nativeCanvas.save()
            canvas.nativeCanvas.translate(dx, dy)
            canvas.nativeCanvas.scale(s, s)
            canvas.nativeCanvas.drawPicture(picture)
            canvas.nativeCanvas.restore()
        }
    }
}

/**
 * Where to check the stop: each a tappable `file:line` on its own line, the reason under it at the full width of the card.
 * Side by side, a long file name left the reason a column three or four letters wide (F62). The name wraps between its
 * words, never cut (rule 2).
 */
@Composable
internal fun Sources(sources: List<TourSource>, onOpen: (TourSource) -> Unit) {
    val c = LocalPullsColors.current
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text("In the code", style = PullsType.sm.copy(fontWeight = FontWeight.Medium), color = c.muted)
        sources.forEach { source ->
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
    }
}

@Composable
internal fun Glossary(entries: List<Pair<String, String>>, modifier: Modifier) {
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
