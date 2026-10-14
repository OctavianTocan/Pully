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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.caverock.androidsvg.SVG
import com.octaviantocan.pulls.data.TourBlock
import com.octaviantocan.pulls.data.TourSource
import com.octaviantocan.pulls.ui.design.CtaButton
import com.octaviantocan.pulls.ui.design.FloatIcon
import com.octaviantocan.pulls.ui.design.LocalPullsColors
import com.octaviantocan.pulls.ui.design.Lu
import com.octaviantocan.pulls.ui.design.Markdown
import com.octaviantocan.pulls.ui.design.Orb
import com.octaviantocan.pulls.ui.design.PillButton
import com.octaviantocan.pulls.ui.design.PullsType
import com.octaviantocan.pulls.ui.design.Skeleton
import com.octaviantocan.pulls.ui.design.headerTopPadding
import com.octaviantocan.pulls.ui.review.ReviewSession
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

@Composable
internal fun TourEmpty(session: ReviewSession, error: String?, modifier: Modifier) {
    val c = LocalPullsColors.current
    Column(
        modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Take a tour of this pull request", style = PullsType.xxl, color = c.text)
        Text(
            "AI reads the change and the code around it, plans a few stops, and walks you through what you need to know: the flow, the pieces, and why. No diffs.",
            style = PullsType.read, color = c.muted,
        )
        Text("It plans first, then writes every stop in parallel. Saved per commit, so it opens instantly next time.", style = PullsType.sm, color = c.muted)
        if (error != null) ErrorBox("The tour failed", error)
        CtaButton(if (error != null) "Try again" else "Give me a tour", { startTour(session) }, Modifier.padding(top = 4.dp), enabled = session.key != null)
    }
}

@Composable
internal fun TourRunning(session: ReviewSession, modifier: Modifier) {
    val task = session.taskFor("tour") ?: return
    val c = LocalPullsColors.current
    val now = rememberNow(true)
    Column(
        modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Orb(size = 24.dp)
            Shimmer("Building the tour… ${formatClockDuration(now - task.startedAt)}", PullsType.base.copy(color = c.text), Modifier.weight(1f))
            PillButton("Stop", { session.cancelTask(task.id) })
        }
        AiActivityLogFull(task.activities, running = true)
    }
}

@Composable
internal fun TourBlockView(session: ReviewSession, block: TourBlock) {
    val c = LocalPullsColors.current
    when (block.kind) {
        "text" -> Markdown(block.text, style = PullsType.read.copy(color = c.text))
        "shape" -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(SHAPE_LABELS[block.lang] ?: "Sketch", style = PullsType.micro.copy(fontWeight = FontWeight.SemiBold), color = c.accentTeal)
                if (block.text.isNotBlank()) Text(block.text, style = PullsType.xs, color = c.fade)
            }
            val zoom = session.settings.codeZoom
            Box(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(c.paper)
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
            result == null -> Skeleton(Modifier.fillMaxWidth().height(160.dp).clip(RoundedCornerShape(12.dp)))
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

/** Where to check the stop, as tappable `file:line` citations with the reason beside each. */
@Composable
internal fun Sources(sources: List<TourSource>, onOpen: (TourSource) -> Unit) {
    val c = LocalPullsColors.current
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text("In the code", style = PullsType.sm.copy(fontWeight = FontWeight.Medium), color = c.muted)
        sources.forEach { source ->
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable(role = Role.Button) { onOpen(source) }.padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    source.path.substringAfterLast('/') + if (source.line > 0) ":${source.line}" else "",
                    style = PullsType.sm.copy(fontFamily = PullsType.display, fontWeight = FontWeight.Medium, textDecoration = TextDecoration.Underline),
                    color = c.activeText, maxLines = 1,
                )
                Text(source.why, Modifier.weight(1f), style = PullsType.sm, color = c.muted)
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
                Text(term, style = PullsType.sm.copy(fontFamily = PullsType.mono, fontWeight = FontWeight.Medium), color = c.text)
                Text(meaning, style = PullsType.sm, color = c.fade)
            }
        }
    }
}
