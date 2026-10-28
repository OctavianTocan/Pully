package com.octaviantocan.pulls.ui.ai

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.octaviantocan.pulls.data.TourBlock
import com.octaviantocan.pulls.sound.cueClickable
import com.octaviantocan.pulls.ui.design.FloatIcon
import com.octaviantocan.pulls.ui.design.LocalPullsColors
import com.octaviantocan.pulls.ui.design.Lu
import com.octaviantocan.pulls.ui.design.ModalReveal
import com.octaviantocan.pulls.ui.design.PullsRadius
import com.octaviantocan.pulls.ui.design.PullsType
import com.octaviantocan.pulls.ui.design.Skeleton
import com.octaviantocan.pulls.ui.design.headerTopPadding
import com.octaviantocan.pulls.ui.review.ReviewSession

/**
 * What a sketch or a diagram is and its caption, as one paragraph above it: the kind in the accent, the caption after it
 * in the muted colour, wrapping under the kind at the left edge. Side by side, a two-line caption stood in a column of its
 * own, out of line with the kind ("the Diagram label does not line up with its caption", recording of 16 Oct 12:10,
 * 17:07 to 17:22, N33). The caption is its first sentence: the model is asked for one, and an older brief may hold more.
 */
@Composable
fun BlockLabel(kind: String, caption: String) {
    val c = LocalPullsColors.current
    val text = remember(kind, caption, c) {
        buildAnnotatedString {
            withStyle(SpanStyle(fontWeight = FontWeight.Medium, color = c.accentTeal)) { append(kind) }
            val first = firstSentence(caption)
            if (first.isNotEmpty()) {
                append("  ")
                withStyle(SpanStyle(color = c.muted)) { append(first) }
            }
        }
    }
    Text(text, Modifier.fillMaxWidth(), style = PullsType.sm)
}

/** A Mermaid diagram the server rendered to SVG, drawn natively; tap opens it full screen with pinch zoom. */
@Composable
fun Diagram(session: ReviewSession, block: TourBlock) {
    val c = LocalPullsColors.current
    val state = diagramState(session)
    val owner = state.viewOwner()
    val key = DiagramKey(c.dark, block.body)
    var result by remember(state, owner, key) { mutableStateOf(state.cached(key)) }
    LaunchedEffect(state, owner, key) { result = state.load(key) }
    val svg = remember(result) {
        result?.getOrNull()?.let { text -> runCatching { parseDiagramSvg(text) }.getOrNull() }
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        BlockLabel("Diagram", block.text)
        when {
            svg != null -> DiagramPicture(svg)
            result == null -> Skeleton(Modifier.fillMaxWidth().height(160.dp).clip(RoundedCornerShape(PullsRadius.xl)))
            // Could not render: the source still says what the diagram meant.
            else -> AiCodeView(session, block.body, "")
        }
    }
}

/**
 * A drawn diagram in its bordered frame at its natural size, [scale] dp to an SVG pixel, shrunk to fit the width and at
 * most 480dp tall; a tap opens it full screen with pinch zoom.
 */
@Composable
fun DiagramPicture(svg: DiagramSvg, scale: Float = 1.2f) {
    val c = LocalPullsColors.current
    var full by remember(svg) { mutableStateOf(false) }
    androidx.compose.foundation.layout.BoxWithConstraints(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(PullsRadius.md)).border(1.dp, c.border, RoundedCornerShape(PullsRadius.md))
            .cueClickable(onClickLabel = "Open the diagram") { full = true }.padding(8.dp),
        contentAlignment = Alignment.Center,
    ) {
        val w0 = svg.viewWidth * scale
        val h0 = svg.viewHeight * scale
        val fit = minOf(1f, maxWidth.value / w0, 480f / h0)
        SvgView(svg, Modifier.size((w0 * fit).dp, (h0 * fit).dp))
    }
    ModalReveal(full, { full = false }) {
        var zoom by remember { mutableStateOf(1f) }
        var offset by remember { mutableStateOf(androidx.compose.ui.geometry.Offset.Zero) }
        val state = rememberTransformableState { change, pan, _ -> zoom = (zoom * change).coerceIn(1f, 6f); offset += pan }
        Box(Modifier.fillMaxSize().background(c.base)) {
            SvgView(svg, Modifier.fillMaxSize().aspectRatio(diagramRatio(svg)).transformable(state).graphicsLayer { scaleX = zoom; scaleY = zoom; translationX = offset.x; translationY = offset.y }.padding(16.dp))
            FloatIcon(Lu.close, "Close", { full = false }, Modifier.align(Alignment.TopEnd).padding(top = headerTopPadding(), end = 16.dp))
        }
    }
}
