package com.octaviantocan.pulls.ui.review

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.octaviantocan.pulls.data.PrStack
import com.octaviantocan.pulls.data.StackEntry
import com.octaviantocan.pulls.sound.cueClickable
import com.octaviantocan.pulls.ui.design.LocalPullsColors
import com.octaviantocan.pulls.ui.design.Lu
import com.octaviantocan.pulls.ui.design.LuIcon
import com.octaviantocan.pulls.ui.design.PullsRadius
import com.octaviantocan.pulls.ui.design.PullsSheet
import com.octaviantocan.pulls.ui.design.PullsType
import com.octaviantocan.pulls.ui.design.t
import com.octaviantocan.pulls.ui.home.RowNumber
import com.octaviantocan.pulls.ui.home.StatusColors
import com.octaviantocan.pulls.ui.home.compactCount

/*
 * A pull request stack, the way GitHub's stack map shows it: the header says where this layer
 * sits, and the Stack sheet lists every layer top first down to the base branch, joined by one
 * line, with a tap on any other layer opening it.
 */

/**
 * The header's mark of a stacked pull request: the layers glyph and where this one sits, "Stacked on #241" or
 * "Bottom of a stack", in the branch chip's size and tint. No "2 of 4" (rule 14). Nothing when it is in no stack.
 */
@Composable
fun StackMark(stack: PrStack?, onOpen: () -> Unit) {
    stack ?: return
    val c = LocalPullsColors.current
    val below = stack.below
    val words = buildAnnotatedString {
        when {
            below != null -> {
                append("Stacked on ")
                // Numbers are in Mona Sans, as on the meta line (Typography).
                withStyle(SpanStyle(fontFamily = PullsType.num.fontFamily, fontWeight = PullsType.num.fontWeight)) { append("#${below.number}") }
            }
            stack.current?.position == 1 -> append("Bottom of a stack")
            else -> append("Part of a stack")
        }
    }
    Row(
        Modifier.padding(top = 8.dp).clip(RoundedCornerShape(PullsRadius.md)).background(c.accentWash)
            .cueClickable(role = Role.Button, onClickLabel = "Show the stack", onClick = onOpen)
            .padding(horizontal = 8.dp, vertical = 3.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LuIcon(Lu.layers, tint = c.accentWashInk, size = 14.dp)
        Text(words, style = PullsType.sm.copy(fontWeight = FontWeight.Medium), color = c.accentWashInk)
    }
}

/** The leading column the stack's line runs down. */
private val RAIL = 24.dp
private val ROW_PAD = 13.dp
private val BASE_GLYPH = 20.dp

/**
 * `Stack`: every layer top first, then the base branch, like GitHub's stack map. A layer row has its place in the
 * stack in the leading column ("2/3"), then the whole title wrapping over the Home rows' meta line: number, author,
 * state, checks and size ("I cannot see the user's name ... It's like its missing the PR number (not the in-stack one;
 * that one is there), and the username": Tavi, 17 Oct 20:13; rule 203). One thin line joins the rows' marks so the
 * list reads as one stack. This pull request is marked in the accent and opens nothing;
 * any other row opens that pull request with [onOpen].
 */
@Composable
fun StackSheet(stack: PrStack, onOpen: (number: Int) -> Unit, onClose: () -> Unit) {
    PullsSheet(onClose, "Stack", expandable = true) {
        StackLayers(stack, onOpen = { number -> onOpen(number); onClose() })
    }
}

/** The Stack sheet's list on its own: every layer top first, then the base branch. */
@Composable
fun StackLayers(stack: PrStack, onOpen: (number: Int) -> Unit) {
    Column {
        val trunk = stack.base.isNotBlank()
        val layers = stack.entries.asReversed()
        val top = maxOf(stack.size, stack.entries.maxOf { it.position })
        layers.forEachIndexed { index, entry ->
            LayerRow(entry, top, first = index == 0, last = !trunk && index == layers.lastIndex, onOpen = if (entry.current) null else { { onOpen(entry.number) } })
        }
        if (trunk) BaseRow(stack.base)
    }
}

/** Draws the stack's line down the rail, from the top (or this row's mark, on the first row) to the bottom (or the mark, on the last). */
private fun DrawScope.rail(first: Boolean, last: Boolean, color: Color): Float {
    val x = RAIL.toPx() / 2
    // The mark sits on the middle of the first text line, so it lines up with the number and the branch name.
    val markY = ROW_PAD.toPx() + PullsType.row.lineHeight.toPx() / 2
    val width = 1.5.dp.toPx()
    drawRect(color, Offset(x - width / 2, if (first) markY else 0f), Size(width, (if (last) markY else size.height) - (if (first) markY else 0f)))
    return markY
}

/** The hairline over every row but the first, starting after the rail as on the other sheet lists. */
private fun DrawScope.hairline(color: Color) {
    val inset = (RAIL + 8.dp).toPx()
    drawRect(color, Offset(inset, 0f), Size(size.width - inset, 1.dp.toPx()))
}

@Composable
private fun LayerRow(entry: StackEntry, top: Int, first: Boolean, last: Boolean, onOpen: (() -> Unit)?) {
    val c = LocalPullsColors.current
    val title = buildAnnotatedString {
        // A draft is marked as on the Home rows: muted, at the title's own size (rule 14).
        if (entry.state == "draft") withStyle(SpanStyle(color = c.muted)) { append("[${t("local.home.draft")}] ") }
        append(entry.title)
    }
    Row(
        Modifier.fillMaxWidth().defaultMinSize(minHeight = 64.dp).clip(RoundedCornerShape(PullsRadius.lg))
            .then(if (onOpen != null) Modifier.cueClickable(role = Role.Button, onClickLabel = "Open #${entry.number}", onClick = onOpen) else Modifier.semantics(mergeDescendants = true) { stateDescription = "This pull request" })
            .drawBehind {
                if (!first) hairline(c.borderMute)
                val markY = rail(first = first, last = last, color = c.border)
                val center = Offset(RAIL.toPx() / 2, markY)
                if (entry.current) {
                    drawCircle(c.accentTeal, 6.dp.toPx(), center)
                } else {
                    drawCircle(c.paper, 5.dp.toPx(), center)
                    drawCircle(c.muted, 5.dp.toPx(), center, style = Stroke(1.5.dp.toPx()))
                }
            }
            .padding(start = RAIL + 8.dp, end = 4.dp, top = ROW_PAD, bottom = ROW_PAD),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        // Its place in the stack, counted from the bottom as the rows' "2/10" mark is (H16).
        Text(
            if (entry.position in 1..top) "${entry.position}/$top" else "", Modifier.width(44.dp),
            style = PullsType.num.copy(fontFeatureSettings = "tnum"), color = if (entry.current) c.activeText else c.text, maxLines = 1,
        )
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(title, style = PullsType.row, color = c.text)
            LayerFacts(entry)
        }
    }
}

/**
 * The Home rows' meta line under the title: number and author, then state, checks and size in fixed columns so they
 * line up from row to row (rule 13).
 */
@Composable
private fun LayerFacts(entry: StackEntry) {
    val c = LocalPullsColors.current
    val state = when (entry.state) {
        "merged" -> "Merged" to c.purple
        "closed" -> "Closed" to c.error
        else -> null
    }
    val checks = entry.checks.takeIf { it == "success" || it == "failure" || it == "pending" }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            RowNumber(entry.number)
            if (entry.author.isNotBlank()) {
                Text(entry.author, Modifier.weight(1f, fill = false), style = PullsType.sm, color = c.muted, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
            }
            state?.let { (word, color) -> Text(word, style = PullsType.sm.copy(fontWeight = FontWeight.Medium), color = color, maxLines = 1, softWrap = false) }
        }
        Box(Modifier.width(34.dp), contentAlignment = Alignment.CenterStart) {
            when (checks) {
                "failure" -> LuIcon(Lu.xc, tint = c.error, size = 18.dp, contentDescription = t("pulls.checks.failure"))
                "success" -> LuIcon(Lu.okc, tint = c.success, size = 18.dp, contentDescription = t("pulls.checks.success"))
                "pending" -> Box(Modifier.size(8.dp).clip(CircleShape).background(StatusColors.amber500))
            }
        }
        Box(Modifier.width(100.dp), contentAlignment = Alignment.CenterEnd) {
            entry.additions?.let { adds ->
                val mono = PullsType.sm.copy(fontFamily = PullsType.mono, fontWeight = FontWeight.Medium, textAlign = TextAlign.End)
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("+${compactCount(adds)}", Modifier.width(56.dp), style = mono, color = c.success, maxLines = 1, softWrap = false)
                    Text("−${compactCount(entry.deletions ?: 0)}", Modifier.width(40.dp), style = mono, color = c.error, maxLines = 1, softWrap = false)
                }
            }
        }
    }
}

/** The trunk under the bottom layer: the branch glyph ends the line, and the name is in the mono face. */
@Composable
private fun BaseRow(base: String) {
    val c = LocalPullsColors.current
    // The glyph's middle on the middle of the name's line, where `rail` ends the line.
    val glyphTop = ROW_PAD + with(LocalDensity.current) { PullsType.row.lineHeight.toDp() } / 2 - BASE_GLYPH / 2
    Row(
        Modifier.fillMaxWidth().defaultMinSize(minHeight = 64.dp).drawBehind {
            hairline(c.borderMute)
            rail(first = false, last = true, color = c.border)
        },
    ) {
        Box(Modifier.width(RAIL).padding(top = glyphTop), contentAlignment = Alignment.TopCenter) {
            // A disc of the sheet's paper under the glyph, so the line stops at it rather than running through.
            LuIcon(Lu.branch, Modifier.clip(CircleShape).background(c.paper), tint = c.muted, size = BASE_GLYPH)
        }
        Text(base, Modifier.weight(1f).padding(start = 8.dp, end = 4.dp, top = ROW_PAD, bottom = ROW_PAD), style = PullsType.row.copy(fontFamily = PullsType.mono), color = c.muted)
    }
}
