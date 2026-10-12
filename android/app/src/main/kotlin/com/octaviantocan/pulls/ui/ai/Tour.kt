package com.octaviantocan.pulls.ui.ai

import android.graphics.Picture
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.caverock.androidsvg.SVG
import com.octaviantocan.pulls.core.FileLinks
import com.octaviantocan.pulls.data.Tour
import com.octaviantocan.pulls.data.TourBlock
import com.octaviantocan.pulls.data.TourSource
import com.octaviantocan.pulls.data.TourStop
import com.octaviantocan.pulls.ui.design.ActionButton
import com.octaviantocan.pulls.ui.design.ButtonVariant
import com.octaviantocan.pulls.ui.design.IconButton
import com.octaviantocan.pulls.ui.design.LocalPullsColors
import com.octaviantocan.pulls.ui.design.Markdown
import com.octaviantocan.pulls.ui.design.PhIcon
import com.octaviantocan.pulls.ui.design.PullsType
import com.octaviantocan.pulls.ui.review.Focus
import com.octaviantocan.pulls.ui.review.ReviewSession
import kotlinx.coroutines.launch
import kotlinx.serialization.json.put

/*
 * The Tour tab (phone-only): an AI walkthrough of the PR with no diffs. The server plans the
 * stops, writes each one in parallel and saves the tour per head commit; this draws it with the
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
 * The tour as LazyColumn items, starting at list index [start] (so the itinerary can jump).
 * Emits the empty state, the running state, or the tour.
 */
fun LazyListScope.tourItems(session: ReviewSession, listState: LazyListState, start: Int) {
    val task = session.taskFor("tour")
    val tour = session.saved?.tour
    val pad = Modifier.padding(horizontal = 12.dp)
    if (task?.running == true) {
        item(key = "tour:running") { TourRunning(session, pad.padding(top = 16.dp)) }
        if (tour == null) return
    }
    if (tour == null) {
        item(key = "tour:empty") { TourEmpty(session, task?.takeIf { it.status == "failed" }?.error, pad.padding(top = 24.dp)) }
        return
    }
    var index = start + (if (task?.running == true) 1 else 0)
    val stopIndex = HashMap<Int, Int>()
    item(key = "tour:head") { TourHead(session, tour, pad.padding(top = 16.dp)) }
    index++
    item(key = "tour:itinerary") { Itinerary(tour) { at -> stopIndex[at]?.let { session.scope.launch { listState.animateScrollToItem(it) } } } }
    index++
    tour.stops.forEachIndexed { at, stop ->
        stopIndex[at] = index
        item(key = "tour:stop:$at") { StopCard(session, at, tour.stops.size, stop, pad.padding(top = 20.dp)) }
        index++
    }
    if (tour.glossary.isNotEmpty()) item(key = "tour:glossary") { Glossary(tour.glossary, pad.padding(top = 24.dp)) }
    item(key = "tour:end") { Box(Modifier.padding(48.dp)) }
}

@Composable
private fun TourEmpty(session: ReviewSession, error: String?, modifier: Modifier) {
    val c = LocalPullsColors.current
    Column(
        modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).border(1.dp, c.border, RoundedCornerShape(8.dp)).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PhIcon("compass-duotone", tint = c.accentTeal, size = 22.dp)
            Text("Take a tour of this pull request", style = PullsType.lg.copy(fontWeight = FontWeight.SemiBold), color = c.text)
        }
        Text(
            "AI reads the change and the code around it, plans a few stops, and walks you through what you need to know: the flow, the pieces, and why. No diffs.",
            style = PullsType.sm, color = c.fade,
        )
        Text("It plans first, then writes every stop in parallel. Saved per commit, so it opens instantly next time.", style = PullsType.xs, color = c.mute)
        if (error != null) ErrorBox("The tour failed", error)
        ActionButton({ startTour(session) }, variant = ButtonVariant.Primary, icon = "compass-duotone", enabled = session.key != null) { Text(if (error != null) "Try again" else "Give me a tour") }
    }
}

@Composable
private fun TourRunning(session: ReviewSession, modifier: Modifier) {
    val c = LocalPullsColors.current
    val task = session.taskFor("tour") ?: return
    val now = rememberNow(true)
    Column(
        modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).border(1.dp, c.border, RoundedCornerShape(8.dp)).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            com.octaviantocan.pulls.ui.design.SpinIcon(tint = c.accentTeal, size = 18.dp)
            Shimmer("Building the tour… ${formatClockDuration(now - task.startedAt)}", PullsType.sm.copy(color = c.text), Modifier.weight(1f))
            ActionButton({ session.cancelTask(task.id) }, variant = ButtonVariant.Text, small = true, icon = "stop-duotone") { Text("Stop") }
        }
        AiActivityLogFull(task.activities, running = true)
    }
}

@Composable
private fun TourHead(session: ReviewSession, tour: Tour, modifier: Modifier) {
    val c = LocalPullsColors.current
    val outdated = tour.headSha != null && session.opened?.headSha?.let { it != tour.headSha } == true
    val running = session.taskFor("tour")?.running == true
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PhIcon("compass-duotone", tint = c.accentTeal, size = 20.dp)
            Text("Tour", style = PullsType.xs.copy(fontWeight = FontWeight.Medium), color = c.accentTeal)
            androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))
            if (!running) IconButton("arrows-clockwise-duotone", "Regenerate the tour", { startTour(session) }, compact = true)
        }
        Text(tour.title, style = PullsType.xl.copy(fontWeight = FontWeight.SemiBold), color = c.text)
        if (outdated) Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).background(c.warning.copy(alpha = 0.1f)).padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            PhIcon("warning-duotone", tint = c.warning, size = 16.dp)
            Text("Written for an older commit.", Modifier.weight(1f), style = PullsType.xs, color = c.text)
            if (!running) ActionButton({ startTour(session) }, small = true) { Text("Update") }
        }
        Markdown(tour.overview, style = PullsType.sm.copy(color = c.text))
        AiUsageLine(tour.usage?.copy(model = tour.usage.model ?: tour.model) ?: com.octaviantocan.pulls.data.Usage(model = tour.model))
    }
}

/** The stops as numbered chips; a tap scrolls to the stop. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Itinerary(tour: Tour, onJump: (Int) -> Unit) {
    val c = LocalPullsColors.current
    FlowRow(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 14.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        tour.stops.forEachIndexed { at, stop ->
            Row(
                Modifier.clip(RoundedCornerShape(16.dp)).border(1.dp, c.border, RoundedCornerShape(16.dp)).clickable(role = Role.Button) { onJump(at) }
                    .padding(start = 4.dp, end = 10.dp, top = 4.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                StopNumber(at + 1, small = true, failed = stop.error != null)
                Text(stop.title, style = PullsType.xs, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 220.dp))
            }
        }
    }
}

@Composable
private fun StopNumber(n: Int, small: Boolean = false, failed: Boolean = false) {
    val c = LocalPullsColors.current
    val color = if (failed) c.error else c.accentTeal
    Box(Modifier.size(if (small) 20.dp else 28.dp).clip(CircleShape).background(color.copy(alpha = 0.15f)), contentAlignment = Alignment.Center) {
        Text("$n", style = (if (small) PullsType.mini else PullsType.sm).copy(fontWeight = FontWeight.SemiBold), color = color)
    }
}

@Composable
private fun StopCard(session: ReviewSession, at: Int, total: Int, stop: TourStop, modifier: Modifier) {
    val c = LocalPullsColors.current
    Column(
        modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).border(1.dp, c.border, RoundedCornerShape(10.dp)).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StopNumber(at + 1, failed = stop.error != null)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("Stop ${at + 1} of $total", style = PullsType.xs, color = c.mute)
                Text(stop.title, style = PullsType.lg.copy(fontWeight = FontWeight.SemiBold), color = c.text)
            }
        }
        if (stop.purpose.isNotBlank()) Text(stop.purpose, style = PullsType.sm, color = c.fade)
        if (stop.error != null) ErrorBox("This stop could not be written", stop.error)
        stop.blocks.forEach { block -> TourBlockView(session, block) }
        if (stop.takeaway.isNotBlank()) Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).background(c.accentTeal.copy(alpha = 0.08f)).padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            PhIcon("lightbulb-duotone", tint = c.accentTeal, size = 18.dp)
            Text(stop.takeaway, style = PullsType.sm.copy(fontWeight = FontWeight.Medium), color = c.text)
        }
        if (stop.sources.isNotEmpty()) Sources(session, stop.sources)
    }
}

@Composable
private fun TourBlockView(session: ReviewSession, block: TourBlock) {
    val c = LocalPullsColors.current
    when (block.kind) {
        "text" -> Markdown(block.text, style = PullsType.sm.copy(color = c.text))
        "shape" -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(SHAPE_LABELS[block.lang] ?: "Sketch", style = PullsType.micro.copy(fontWeight = FontWeight.SemiBold), color = c.accentTeal)
                if (block.text.isNotBlank()) Text(block.text, style = PullsType.xs, color = c.fade)
            }
            val zoom = session.settings.codeZoom
            Box(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).background(c.code).border(1.dp, c.border, RoundedCornerShape(6.dp))
                    .horizontalScroll(rememberScrollState()).padding(12.dp),
            ) {
                Text(block.body, style = PullsType.xs.copy(fontFamily = PullsType.mono, fontSize = (12f * zoom).sp, lineHeight = (18f * zoom).sp), color = c.text, softWrap = false)
            }
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
            Text("Diagram", style = PullsType.micro.copy(fontWeight = FontWeight.SemiBold), color = c.accentTeal)
            if (block.text.isNotBlank()) Text(block.text, style = PullsType.xs, color = c.fade)
        }
        when {
            svg != null -> androidx.compose.foundation.layout.BoxWithConstraints(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).border(1.dp, c.border, RoundedCornerShape(6.dp))
                    .clickable(onClickLabel = "Open the diagram") { full = true }.padding(8.dp),
                contentAlignment = Alignment.Center,
            ) {
                // Natural size (1 SVG px = 1.2dp), shrunk to fit the width and at most 480dp tall; tap for full screen.
                val box = svg.documentViewBox
                val w0 = (box?.width() ?: 400f) * 1.2f
                val h0 = (box?.height() ?: 250f) * 1.2f
                val fit = minOf(1f, maxWidth.value / w0, 480f / h0)
                SvgView(svg, Modifier.size((w0 * fit).dp, (h0 * fit).dp))
            }
            result == null -> com.octaviantocan.pulls.ui.design.Loading("Drawing the diagram…")
            // Could not render: the source still says what the diagram meant.
            else -> TourBlockView(session, TourBlock("shape", "Mermaid source", "flow", block.body))
        }
    }
    if (full && svg != null) Dialog({ full = false }, DialogProperties(usePlatformDefaultWidth = false)) {
        var scale by remember { mutableStateOf(1f) }
        var offset by remember { mutableStateOf(androidx.compose.ui.geometry.Offset.Zero) }
        val state = rememberTransformableState { zoom, pan, _ -> scale = (scale * zoom).coerceIn(1f, 6f); offset += pan }
        Box(Modifier.fillMaxSize().background(c.base)) {
            SvgView(svg, Modifier.fillMaxSize().aspectRatio(ratioOf(svg)).transformable(state).graphicsLayer { scaleX = scale; scaleY = scale; translationX = offset.x; translationY = offset.y }.padding(16.dp))
            IconButton("x", "Close", { full = false }, Modifier.align(Alignment.TopEnd).padding(top = 32.dp, end = 8.dp))
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

/** Where to check the stop: a tap jumps to the line in the diff, or opens the file on GitHub when the PR did not touch it. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Sources(session: ReviewSession, sources: List<TourSource>) {
    val c = LocalPullsColors.current
    val uri = LocalUriHandler.current
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("In the code", style = PullsType.micro.copy(fontWeight = FontWeight.SemiBold), color = c.mute)
        sources.forEach { source ->
            val inDiff = session.files.any { it.path == source.path }
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(4.dp)).clickable(role = Role.Button) {
                    if (inDiff) {
                        session.pageTab = "diff"
                        session.focus = Focus(source.path, source.line)
                    } else {
                        val repo = FileLinks.repoOf(session.target)
                        val sha = session.opened?.headSha
                        if (repo != null && !sha.isNullOrBlank()) runCatching { uri.openUri(FileLinks.blob(repo.first, repo.second, sha, source.path) + if (source.line > 0) "#L${source.line}" else "") }
                    }
                }.padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                PhIcon(if (inDiff) "file-code-duotone" else "arrow-square-out", tint = c.fade, size = 14.dp)
                Text(
                    source.path.substringAfterLast('/') + if (source.line > 0) ":${source.line}" else "",
                    style = PullsType.xs.copy(fontFamily = PullsType.mono), color = c.text, maxLines = 1,
                )
                Text(source.why, Modifier.weight(1f), style = PullsType.xs, color = c.fade, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun Glossary(entries: List<Pair<String, String>>, modifier: Modifier) {
    val c = LocalPullsColors.current
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PhIcon("book-open-text-duotone", tint = c.fade, size = 18.dp)
            Text("Glossary", style = PullsType.base.copy(fontWeight = FontWeight.SemiBold), color = c.text)
        }
        entries.forEach { (term, meaning) ->
            Column(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                Text(term, style = PullsType.sm.copy(fontFamily = PullsType.mono, fontWeight = FontWeight.Medium), color = c.text)
                Text(meaning, style = PullsType.sm, color = c.fade)
            }
        }
    }
}
