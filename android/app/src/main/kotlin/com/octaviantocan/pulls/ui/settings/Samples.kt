package com.octaviantocan.pulls.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
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
 * One diff layout drawn small, with its name under it: unified is one column of lines, split is two
 * side by side. Tap it to choose it; the chosen one has a teal outline.
 */
@Composable
internal fun LayoutChoice(split: Boolean, selected: Boolean, label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val c = LocalPullsColors.current
    val shape = RoundedCornerShape(PullsRadius.xl)
    Column(
        modifier.clip(shape).cueClickable(role = Role.RadioButton, onClickLabel = label, onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            Modifier.fillMaxWidth().height(84.dp).clip(shape).background(c.raised)
                .border(if (selected) 2.dp else 1.dp, if (selected) c.accentTeal else c.border, shape)
                .padding(10.dp),
        ) {
            if (split) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        MiniLine(' ', 0.7f); MiniLine('-', 0.5f); MiniLine(null, 0f); MiniLine(' ', 0.6f)
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        MiniLine(' ', 0.7f); MiniLine('+', 0.8f); MiniLine('+', 0.4f); MiniLine(' ', 0.6f)
                    }
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    MiniLine(' ', 0.7f); MiniLine('-', 0.5f); MiniLine('+', 0.8f); MiniLine('+', 0.4f); MiniLine(' ', 0.6f)
                }
            }
        }
        Text(label, style = PullsType.sm.copy(fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal), color = if (selected) c.text else c.muted, maxLines = 1)
    }
}

/** One line of a small diff: a wash for a removed or added line (null is a gap), and a short bar for its text. */
@Composable
private fun MiniLine(kind: Char?, fraction: Float) {
    val c = LocalPullsColors.current
    val wash = when (kind) { '+' -> c.diffAdd; '-' -> c.diffDel; else -> androidx.compose.ui.graphics.Color.Transparent }
    val ink = when (kind) { '+' -> c.diffAddInk; '-' -> c.diffDelInk; else -> c.faint }
    Row(Modifier.fillMaxWidth().height(9.dp).clip(RoundedCornerShape(PullsRadius.xs)).background(wash), verticalAlignment = Alignment.CenterVertically) {
        if (kind != null) {
            Box(Modifier.padding(start = 3.dp).width(3.dp).height(3.dp).clip(RoundedCornerShape(PullsRadius.xs)).background(ink))
            Box(Modifier.padding(start = 4.dp).fillMaxWidth(fraction).height(3.dp).clip(RoundedCornerShape(PullsRadius.xs)).background(ink.copy(alpha = 0.55f)))
        }
    }
}

/**
 * Two lines of code in the chosen font at the chosen size, set the way the diff sets them, so the Code size
 * and Code font choices are seen at once.
 */
@Composable
internal fun CodeSample(zoom: Float, modifier: Modifier = Modifier) {
    val c = LocalPullsColors.current
    val text = buildAnnotatedString {
        withStyle(SpanStyle(color = c.activeText)) { append("const") }
        append(" total = add(a, b)\n")
        withStyle(SpanStyle(color = c.activeText)) { append("return") }
        append(" total > 9 ? ")
        withStyle(SpanStyle(color = c.accentTeal)) { append("\"big\"") }
        append(" : ")
        withStyle(SpanStyle(color = c.accentTeal)) { append("\"ok\"") }
    }
    Text(
        text,
        modifier.fillMaxWidth().clip(RoundedCornerShape(PullsRadius.xl)).background(c.code).padding(horizontal = 12.dp, vertical = 10.dp),
        style = PullsType.sm.copy(fontFamily = PullsType.mono, fontSize = (12.5f * zoom).sp, lineHeight = (20f * zoom).sp),
        color = c.text,
    )
}
