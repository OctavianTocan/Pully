package com.octaviantocan.pulls.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.octaviantocan.pulls.ui.design.LocalPullsColors
import com.octaviantocan.pulls.ui.design.Lu
import com.octaviantocan.pulls.ui.design.LuIcon
import com.octaviantocan.pulls.ui.design.PullsRadius
import com.octaviantocan.pulls.ui.design.PullsType

/**
 * The state tile's side: as tall as the row's two lines but for a little air above and below, as Claude's own list
 * draws it ("the icon is actually the entire size pretty much of the row ... leave a little bit of margin up top and
 * at the bottom": recording 17 Oct 14:45, 00:35 to 00:56; Q1). That is a one-line title over the meta line; a title
 * on two lines leaves the tile this size, centred.
 */
val TILE = 42.dp

/** The mark on the tile, grown with it. */
val TILE_MARK = 24.dp

/** The most lines a row's title takes before it ends in an ellipsis (rule 201). */
const val TITLE_LINES = 2

/**
 * The number's column: "#999" fits; a longer one pushes only its own author along rather than being cut, while the
 * columns at the right end stay put. Narrow, so the author keeps room beside the tile (0.40.0's rows cut "Octavi…").
 */
private val NUMBER_WIDTH = 40.dp

/** The checks mark's column: the 18dp mark and a little air. */
private val CHECK_WIDTH = 22.dp

/** The age's column: "22h", "3w", "11mo". */
private val AGE_WIDTH = 34.dp

/** The columns of [DiffCounts]: `+17k` and `−1.2k` both fit, right-aligned, in the mono face's fixed advance. */
private val ADDS_WIDTH = 48.dp
private val DELS_WIDTH = 48.dp

/** What state a pull request is in, for the tile that leads its row. */
enum class PullState { Open, Draft, Merged, Closed }

/** A repository's pull request by the state GitHub gives it (`open`, `draft`, `closed`, `merged`). */
fun pullStateOf(state: String): PullState = when (state) {
    "merged" -> PullState.Merged
    "closed" -> PullState.Closed
    "draft" -> PullState.Draft
    else -> PullState.Open
}

/**
 * The tile that leads a row: a rounded square washed in the state's colour with the state's mark on it in that colour, so
 * a draft reads apart from an open pull request by colour before a word is read; nothing in the title says "[draft]"
 * any more (N4).
 */
@Composable
fun StateTile(state: PullState, description: String) {
    val c = LocalPullsColors.current
    val ink = stateInk(state)
    val glyph = when (state) {
        PullState.Open -> Lu.prOpen
        PullState.Draft -> Lu.prDraft
        PullState.Merged -> Lu.prMerged
        PullState.Closed -> Lu.prClosed
    }
    RowTile(ink.copy(alpha = if (c.dark) 0.18f else 0.12f), Modifier.semantics { contentDescription = description }) {
        LuIcon(glyph, tint = ink, size = TILE_MARK)
    }
}

/** A state's own colour, for its tile's wash and mark. */
@Composable
fun stateInk(state: PullState): Color {
    val c = LocalPullsColors.current
    return when (state) {
        PullState.Open -> c.stateOpen
        PullState.Draft -> c.stateDraft
        PullState.Merged -> c.stateMerged
        PullState.Closed -> c.stateClosed
    }
}

/** The square at the head of a row, filled with [fill]: a pull request's state, or a tour's ring. */
@Composable
fun RowTile(fill: Color, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(modifier.size(TILE).clip(RoundedCornerShape(PullsRadius.lg)).background(fill), contentAlignment = Alignment.Center) { content() }
}

/**
 * The frame every pull request row shares: [tile], then the [title] on at most two lines, cut with an ellipsis where it
 * runs longer ("these things should never have three lines ... you can use ellipses", recording 17 Oct 14:45, 00:07 to
 * 00:25; Q1, rule 2; "I meant the title should also be in two rows", Tavi 17 Oct 17:14, rule 201), [under] for what a
 * page adds (a repository's labels), and the one [meta] line. The tile stays centred on the row whether the title takes
 * one line or two. A hairline over the row starts and ends on the page's 16dp margins, so the first row's is the
 * heading's underline (rule 75).
 */
@Composable
fun PullRowFrame(
    title: AnnotatedString,
    tile: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    dimmed: Boolean = false,
    under: (@Composable () -> Unit)? = null,
    meta: @Composable RowScope.() -> Unit,
) {
    val c = LocalPullsColors.current
    val inset = with(LocalDensity.current) { 16.dp.toPx() }
    Row(
        modifier.fillMaxWidth()
            .drawBehind { drawRect(c.borderMute, Offset(inset, 0f), Size(size.width - 2 * inset, 1.dp.toPx())) }
            .alpha(if (dimmed) 0.55f else 1f)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        tile()
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = PullsType.base, color = c.text, maxLines = TITLE_LINES, overflow = TextOverflow.Ellipsis)
            under?.invoke()
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp), content = meta)
        }
    }
}

/** "#279", first on the meta line where Claude's list puts the state (N4), in its own column. */
@Composable
fun RowNumber(number: Int) {
    val c = LocalPullsColors.current
    Text(
        "#$number", Modifier.widthIn(min = NUMBER_WIDTH),
        style = PullsType.sm.copy(fontFamily = PullsType.display, fontWeight = FontWeight.Medium, fontFeatureSettings = "tnum"),
        color = c.text, maxLines = 1, softWrap = false,
    )
}

/**
 * The right end of the meta line: the checks mark, the + and − and the age, each in a fixed column. [checks] and the
 * counts are left out together for a row that has no pull request to read them from (a tour of one not in the Inbox).
 */
@Composable
fun RowFacts(checks: (@Composable () -> Unit)?, additions: Int?, deletions: Int?, age: String) {
    val c = LocalPullsColors.current
    if (checks != null) {
        Box(Modifier.width(CHECK_WIDTH), contentAlignment = Alignment.CenterStart) { checks() }
        Box(Modifier.width(ADDS_WIDTH + 4.dp + DELS_WIDTH), contentAlignment = Alignment.CenterEnd) { if (additions != null) DiffCounts(additions, deletions ?: 0) }
    }
    Text(age, Modifier.width(AGE_WIDTH), style = PullsType.sm.copy(fontFeatureSettings = "tnum"), color = c.muted, textAlign = TextAlign.End, maxLines = 1, softWrap = false)
}

/**
 * `.dx`: `+180` in green and `−32` in red, each right-aligned in a fixed column in the mono face, side by side. Every
 * list that counts lines (the Inbox, the Files sheet, a file section's heading) draws them with this one, so the numbers
 * of a list sit in one column (K27; recording 16 Oct 12:10, 18:03 to 18:21, N35).
 */
@Composable
fun DiffCounts(additions: Int, deletions: Int) {
    val c = LocalPullsColors.current
    val mono = PullsType.sm.copy(fontFamily = PullsType.mono, fontWeight = FontWeight.Medium, textAlign = TextAlign.End, fontFeatureSettings = "tnum")
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("+${compactCount(additions)}", Modifier.width(ADDS_WIDTH), style = mono, color = c.success, maxLines = 1, softWrap = false)
        Text("−${compactCount(deletions)}", Modifier.width(DELS_WIDTH), style = mono, color = c.error, maxLines = 1, softWrap = false)
    }
}
