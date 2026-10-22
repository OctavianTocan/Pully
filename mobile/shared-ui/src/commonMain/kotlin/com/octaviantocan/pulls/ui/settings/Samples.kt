package com.octaviantocan.pulls.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.octaviantocan.pulls.sound.cueClickable
import com.octaviantocan.pulls.ui.design.LocalPullsColors
import com.octaviantocan.pulls.ui.design.PullsRadius
import com.octaviantocan.pulls.ui.design.PullsType

/*
 * Samples for the settings that change how something looks (rule 81): the control is the thing
 * itself, so nobody has to leave the sheet to find out what a choice does.
 */

/**
 * One diff layout drawn small, with its name under it: a real little diff in the code font, a line removed and a
 * line added between two kept ones, in the diff's own washes and marks. Unified is one column, the removed line over
 * the added one; split is the old side and the new side next to each other. Tap it to choose it; the chosen one has
 * a teal outline. A layout the screen cannot show ([enabled] off) is dimmed and its [label] says what it needs.
 */
@Composable
fun LayoutChoice(split: Boolean, selected: Boolean, label: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val c = LocalPullsColors.current
    val shape = RoundedCornerShape(PullsRadius.xl)
    Column(
        modifier.alpha(if (enabled) 1f else 0.5f).clip(shape).cueClickable(enabled = enabled, role = Role.RadioButton, onClickLabel = label, onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            Modifier.fillMaxWidth().heightIn(min = 84.dp).clip(shape).background(c.raised)
                .border(if (selected) 2.dp else 1.dp, if (selected) c.accentTeal else c.border, shape)
                .padding(horizontal = 8.dp, vertical = 10.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            if (split) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        MiniLine(' ', SAMPLE_KEPT); MiniLine('-', SAMPLE_OLD); MiniLine(' ', SAMPLE_END)
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        MiniLine(' ', SAMPLE_KEPT); MiniLine('+', SAMPLE_NEW); MiniLine(' ', SAMPLE_END)
                    }
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    MiniLine(' ', SAMPLE_KEPT); MiniLine('-', SAMPLE_OLD); MiniLine('+', SAMPLE_NEW); MiniLine(' ', SAMPLE_END)
                }
            }
        }
        Text(label, style = PullsType.sm.copy(fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal), color = if (selected) c.text else c.muted, maxLines = 1)
    }
}

/** The little diff's code: short enough for one side of the split sample on a narrow phone. */
private const val SAMPLE_KEPT = "let n = 1"
private const val SAMPLE_OLD = "n = n + 1"
private const val SAMPLE_NEW = "n += 1"
private const val SAMPLE_END = "return n"

/** How small the sample draws the diff: its code is the diff's 12.5sp at this scale, a picture of the diff rather than text to read. */
private const val SAMPLE_SCALE = 0.62f

/** One line of the little diff: the removed or added wash, the − or + in the diff's ink, then the code. */
@Composable
private fun MiniLine(kind: Char, code: String) {
    val c = LocalPullsColors.current
    val wash = when (kind) { '+' -> c.diffAdd; '-' -> c.diffDel; else -> Color.Transparent }
    val ink = when (kind) { '+' -> c.diffAddInk; '-' -> c.diffDelInk; else -> c.faint }
    val style = PullsType.sm.copy(fontFamily = PullsType.mono, fontSize = (12.5f * SAMPLE_SCALE).sp, lineHeight = (20f * SAMPLE_SCALE).sp)
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(PullsRadius.xs)).background(wash).padding(horizontal = 3.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(when (kind) { '+' -> "+"; '-' -> "−"; else -> "" }, Modifier.width(7.dp), style = style, color = ink, maxLines = 1)
        Text(code, style = style, color = if (kind == ' ') c.muted else c.text, maxLines = 1, softWrap = false, overflow = TextOverflow.Clip)
    }
}

/**
 * Three lines of code in the chosen font at the chosen size, set the way the diff sets them, so the Code size,
 * Code font and Wrap long lines choices are all seen at once. The last line is longer than the card: [wrap] on
 * breaks it onto a second line, off lets it run past the edge and fades it there.
 */
@Composable
fun CodeSample(zoom: Float, modifier: Modifier = Modifier, wrap: Boolean = false) {
    val c = LocalPullsColors.current
    val text = buildAnnotatedString {
        withStyle(SpanStyle(color = c.activeText)) { append("const") }
        append(" total = add(a, b)\n")
        withStyle(SpanStyle(color = c.activeText)) { append("return") }
        append(" total > 9 ? ")
        withStyle(SpanStyle(color = c.accentTeal)) { append("\"big\"") }
        append(" : ")
        withStyle(SpanStyle(color = c.accentTeal)) { append("\"ok\"") }
        append("\n")
        withStyle(SpanStyle(color = c.activeText)) { append("const") }
        append(" note = ")
        withStyle(SpanStyle(color = c.accentTeal)) { append("\"a long line shows what wrapping does\"") }
    }
    val edge = 28.dp
    Text(
        text,
        modifier.fillMaxWidth().clip(RoundedCornerShape(PullsRadius.xl)).background(c.code)
            .drawWithContent {
                drawContent()
                // Unwrapped, a line that runs on is cut by the card: fade it into the card instead of slicing it.
                if (!wrap) drawRect(Brush.horizontalGradient(listOf(Color.Transparent, c.code), startX = size.width - edge.toPx(), endX = size.width), Offset(size.width - edge.toPx(), 0f), Size(edge.toPx(), size.height))
            }
            .padding(horizontal = 12.dp, vertical = 10.dp),
        style = PullsType.sm.copy(fontFamily = PullsType.mono, fontSize = (12.5f * zoom).sp, lineHeight = (20f * zoom).sp),
        color = c.text,
        softWrap = wrap,
        overflow = TextOverflow.Clip,
    )
}
