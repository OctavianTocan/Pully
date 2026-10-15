package com.octaviantocan.pulls.ui.design

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** One SVG path of a 24x24 icon; [fill] paints it solid (the dots of "more"), otherwise it is a stroke. */
internal class LuPath(val d: String, val fill: Boolean = false) {
    val path: Path by lazy { PathParser().parsePathString(d).toPath() }
}

/** A line icon: round-capped strokes of [strokeWidth] on a 24x24 grid. */
internal class LuGlyph(val strokeWidth: Float, vararg val paths: LuPath)

/**
 * Draws a prototype icon. The paths are the prototype's own (`I` in the Android prototype), so
 * strokes keep their weight at any [size]: the stroke width scales with the grid.
 */
@Composable
internal fun LuIcon(
    glyph: LuGlyph,
    modifier: Modifier = Modifier,
    tint: Color = LocalPullsColors.current.text,
    size: Dp = 20.dp,
    strokeWidth: Float = glyph.strokeWidth,
    contentDescription: String? = null,
    /** How much of each stroke is drawn, 0 to 1: a tick is drawn in by raising it (rule 73). */
    progress: () -> Float = { 1f },
) {
    val semantic = if (contentDescription != null) Modifier.semantics { this.contentDescription = contentDescription } else Modifier
    Canvas(modifier.size(size).then(semantic)) {
        val unit = this.size.minDimension / 24f
        val drawn = progress().coerceIn(0f, 1f)
        scale(unit, unit, pivot = Offset.Zero) {
            glyph.paths.forEach { p ->
                val stroke = Stroke(width = strokeWidth, cap = StrokeCap.Round, join = StrokeJoin.Round)
                when {
                    p.fill -> drawPath(p.path, tint, style = Fill)
                    drawn >= 1f -> drawPath(p.path, tint, style = stroke)
                    drawn > 0f -> {
                        val measure = PathMeasure().apply { setPath(p.path, false) }
                        val part = Path()
                        measure.getSegment(0f, measure.length * drawn, part, true)
                        drawPath(part, tint, style = stroke)
                    }
                }
            }
        }
    }
}

/** The prototype's icon set, by its own names. */
internal object Lu {
    val cog = LuGlyph(1.75f, LuPath("M9,12a3,3 0 1,0 6,0a3,3 0 1,0 -6,0"), LuPath("M19.4 15a1.7 1.7 0 0 0 .3 1.8l.1.1a2 2 0 1 1-2.8 2.8l-.1-.1a1.7 1.7 0 0 0-1.8-.3 1.7 1.7 0 0 0-1 1.5V21a2 2 0 1 1-4 0v-.1a1.7 1.7 0 0 0-1.1-1.5 1.7 1.7 0 0 0-1.8.3l-.1.1a2 2 0 1 1-2.8-2.8l.1-.1a1.7 1.7 0 0 0 .3-1.8 1.7 1.7 0 0 0-1.5-1H3a2 2 0 1 1 0-4h.1a1.7 1.7 0 0 0 1.5-1.1 1.7 1.7 0 0 0-.3-1.8l-.1-.1a2 2 0 1 1 2.8-2.8l.1.1a1.7 1.7 0 0 0 1.8.3H9a1.7 1.7 0 0 0 1-1.5V3a2 2 0 1 1 4 0v.1a1.7 1.7 0 0 0 1 1.5 1.7 1.7 0 0 0 1.8-.3l.1-.1a2 2 0 1 1 2.8 2.8l-.1.1a1.7 1.7 0 0 0-.3 1.8V9a1.7 1.7 0 0 0 1.5 1H21a2 2 0 1 1 0 4h-.1a1.7 1.7 0 0 0-1.5 1z"))
    val back = LuGlyph(2f, LuPath("M15 18l-6-6 6-6"))
    val more = LuGlyph(1.75f, LuPath("M3.3,12a1.7,1.7 0 1,0 3.4,0a1.7,1.7 0 1,0 -3.4,0", fill = true), LuPath("M10.3,12a1.7,1.7 0 1,0 3.4,0a1.7,1.7 0 1,0 -3.4,0", fill = true), LuPath("M17.3,12a1.7,1.7 0 1,0 3.4,0a1.7,1.7 0 1,0 -3.4,0", fill = true))
    val list = LuGlyph(1.8f, LuPath("M4 6h16M4 12h16M4 18h10"))
    val diff = LuGlyph(1.8f, LuPath("M5 7.5h5M7.5 5v5M14 17.5h5M15.5 4l-7 16"))
    val tour = LuGlyph(1.8f, LuPath("M3,6a2,2 0 1,0 4,0a2,2 0 1,0 -4,0"), LuPath("M17,18a2,2 0 1,0 4,0a2,2 0 1,0 -4,0"), LuPath("M7 6h8a4 4 0 0 1 0 8H9a4 4 0 0 0 0 8h8"))
    val refresh = LuGlyph(2f, LuPath("M3 12a9 9 0 0 1 9-9 9.75 9.75 0 0 1 6.74 2.74L21 8"), LuPath("M21 3v5h-5"), LuPath("M21 12a9 9 0 0 1-9 9 9.75 9.75 0 0 1-6.74-2.74L3 16"), LuPath("M8 16H3v5"))
    val chev = LuGlyph(2f, LuPath("m9 6 6 6-6 6"))
    val chevd = LuGlyph(2f, LuPath("m6 9 6 6 6-6"))
    val arrow = LuGlyph(2f, LuPath("M5 12h14M13 6l6 6-6 6"))
    val arch = LuGlyph(2f, LuPath("M4 6h16v3H4zM5 9v10h14V9M10 13h4"))
    val xc = LuGlyph(2.1f, LuPath("M3,12a9,9 0 1,0 18,0a9,9 0 1,0 -18,0"), LuPath("m15 9-6 6M9 9l6 6"))
    val okc = LuGlyph(2.1f, LuPath("M3,12a9,9 0 1,0 18,0a9,9 0 1,0 -18,0"), LuPath("m8.2 12.4 2.6 2.6 5-5.6"))
    val to = LuGlyph(2f, LuPath("M5 12h14M13 6l6 6-6 6"))
    val exp = LuGlyph(2f, LuPath("m7 10 5-5 5 5M7 14l5 5 5-5"))
    val prev = LuGlyph(2f, LuPath("M15 18l-6-6 6-6"))
    val next = LuGlyph(2f, LuPath("m9 6 6 6-6 6"))
    val reply = LuGlyph(2f, LuPath("M9 17l-5-5 5-5M4 12h11a5 5 0 0 1 5 5v2"))
    val close = LuGlyph(2f, LuPath("M6 6l12 12M18 6 6 18"))
    val check = LuGlyph(2f, LuPath("m5 12 5 5 9-10"))
    val tick = LuGlyph(3f, LuPath("m5 12 5 5 9-10"))
    val fail = LuGlyph(1.8f, LuPath("M3,12a9,9 0 1,0 18,0a9,9 0 1,0 -18,0"), LuPath("m15 9-6 6M9 9l6 6"))
    val redo = LuGlyph(1.8f, LuPath("M21 12a9 9 0 1 1-3-6.7M21 4v5h-5"))
    val merge = LuGlyph(1.8f, LuPath("M4,5a2,2 0 1,0 4,0a2,2 0 1,0 -4,0"), LuPath("M4,19a2,2 0 1,0 4,0a2,2 0 1,0 -4,0"), LuPath("M16,12a2,2 0 1,0 4,0a2,2 0 1,0 -4,0"), LuPath("M6 7v10M6 7a6 6 0 0 0 6 6h4"))
    val draft = LuGlyph(1.8f, LuPath("M14 3H6a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V9zM14 3v6h6"))
    val shut = LuGlyph(1.8f, LuPath("M3,12a9,9 0 1,0 18,0a9,9 0 1,0 -18,0"), LuPath("m15 9-6 6M9 9l6 6"))
    val branch = LuGlyph(1.8f, LuPath("M6 3v12"), LuPath("M15,6a3,3 0 1,0 6,0a3,3 0 1,0 -6,0"), LuPath("M3,18a3,3 0 1,0 6,0a3,3 0 1,0 -6,0"), LuPath("M18 9a9 9 0 0 1-9 9"))
    /** Lucide `layers`: a pull request stack. */
    val layers = LuGlyph(1.8f, LuPath("M12.83 2.18a2 2 0 0 0-1.66 0L2.6 6.08a1 1 0 0 0 0 1.83l8.58 3.91a2 2 0 0 0 1.66 0l8.58-3.9a1 1 0 0 0 0-1.83z"), LuPath("M2 12a1 1 0 0 0 .58.91l8.6 3.91a2 2 0 0 0 1.65 0l8.58-3.9A1 1 0 0 0 22 12"), LuPath("M2 17a1 1 0 0 0 .58.91l8.6 3.91a2 2 0 0 0 1.65 0l8.58-3.9A1 1 0 0 0 22 17"))
    val search = LuGlyph(2f, LuPath("M4,11a7,7 0 1,0 14,0a7,7 0 1,0 -14,0"), LuPath("m20 20-3.5-3.5"))
    /** Lucide `arrow-up-down`: the Inbox sort. */
    val sort = LuGlyph(2f, LuPath("m21 16-4 4-4-4"), LuPath("M17 20V4"), LuPath("m3 8 4-4 4 4"), LuPath("M7 4v16"))
    val copy = LuGlyph(2f, LuPath("M11,9h7a2,2 0 0 1 2,2v7a2,2 0 0 1 -2,2h-7a2,2 0 0 1 -2,-2v-7a2,2 0 0 1 2,-2z"), LuPath("M5 15H4a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h9a2 2 0 0 1 2 2v1"))
    val edit = LuGlyph(1.8f, LuPath("M17 3a2.85 2.83 0 1 1 4 4L7.5 20.5 2 22l1.5-5.5Z"), LuPath("m15 5 4 4"))
    val trash = LuGlyph(1.8f, LuPath("M3 6h18"), LuPath("M19 6v14c0 1-1 2-2 2H7c-1 0-2-1-2-2V6"), LuPath("M8 6V4c0-1 1-2 2-2h4c1 0 2 1 2 2v2"), LuPath("M10 11v6M14 11v6"))
    val stop = LuGlyph(1.8f, LuPath("M7,5h10a2,2 0 0 1 2,2v10a2,2 0 0 1 -2,2H7a2,2 0 0 1 -2,-2V7a2,2 0 0 1 2,-2z", fill = true))
    val commit = LuGlyph(1.8f, LuPath("M9,12a3,3 0 1,0 6,0a3,3 0 1,0 -6,0"), LuPath("M3 12h6M15 12h6"))
    // The steps of AI work, one per kind of action (rule 72): reading a file, running a command, thinking, writing, another tool.
    val file = LuGlyph(1.8f, LuPath("M15 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V7Z"), LuPath("M14 2v4a2 2 0 0 0 2 2h4"), LuPath("M16 13H8M16 17H8M10 9H8"))
    val terminal = LuGlyph(1.8f, LuPath("m4 17 6-6-6-6"), LuPath("M12 19h8"))
    val think = LuGlyph(1.8f, LuPath("M15 14c.2-1 .7-1.7 1.5-2.5 1-.9 1.5-2.2 1.5-3.5A6 6 0 0 0 6 8c0 1 .2 2.2 1.5 3.5.7.7 1.3 1.5 1.5 2.5"), LuPath("M9 18h6M10 22h4"))
    val write = LuGlyph(1.8f, LuPath("M12 20h9"), LuPath("M16.4 3.6a2.1 2.1 0 0 1 3 3L7.4 18.6 3 20l1.4-4.4Z"))
    val tool = LuGlyph(1.8f, LuPath("M14.7 6.3a1 1 0 0 0 0 1.4l1.6 1.6a1 1 0 0 0 1.4 0l3.77-3.77a6 6 0 0 1-7.94 7.94l-6.91 6.91a2.12 2.12 0 0 1-3-3l6.91-6.91a6 6 0 0 1 7.94-7.94l-3.76 3.76z"))
    /** A new conversation: Lucide's square with a pen. */
    val newChat = LuGlyph(1.8f, LuPath("M12 3H5a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h14a2 2 0 0 0 2-2v-7"), LuPath("M18.4 2.6a2.1 2.1 0 0 1 3 3L12 15l-4 1 1-4Z"))
    /** Lucide corner-down-right: a question to ask next, under an answer. */
    val followUp = LuGlyph(2f, LuPath("m15 10 5 5-5 5"), LuPath("M4 4v7a4 4 0 0 0 4 4h12"))
    /** Lucide history: the earlier chats of a pull request, on Ask's button while nothing is typed (L36). */
    val history = LuGlyph(1.8f, LuPath("M3 12a9 9 0 1 0 9-9 9.75 9.75 0 0 0-6.74 2.74L3 8"), LuPath("M3 3v5h5"), LuPath("M12 7v5l4 2"))
}
