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
import androidx.compose.foundation.shape.CircleShape
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
import com.octaviantocan.pulls.core.PullWork
import com.octaviantocan.pulls.data.JEV_RISK_WORDS
import com.octaviantocan.pulls.data.PullRow
import com.octaviantocan.pulls.data.PullStack
import com.octaviantocan.pulls.data.riskLevel
import com.octaviantocan.pulls.data.riskWords
import com.octaviantocan.pulls.ui.design.LocalPullsColors
import com.octaviantocan.pulls.ui.design.Lu
import com.octaviantocan.pulls.ui.design.LuIcon
import com.octaviantocan.pulls.ui.design.OkDot
import com.octaviantocan.pulls.ui.design.Orb
import com.octaviantocan.pulls.ui.design.ProgressRing
import com.octaviantocan.pulls.ui.design.PullsRadius
import com.octaviantocan.pulls.ui.design.PullsType
import com.octaviantocan.pulls.ui.design.t

/*
 * The one pull request row of the Inbox, Tours and a repository's page, after Claude's own list of pull requests
 * (recording 16 Oct 12:10, 01:23 to 03:05; N2, N4): a small tinted square with the pull request's state on the left, the
 * title at most two lines beside it, and one line under the title that starts with the number and then carries the
 * author, the checks, the coloured diffstat and the age, each in a column of its own that does not move when a digit
 * or a name's length changes (rules 13, 15 and 36; N35).
 */

/** The state tile's side: Claude's square, a little under the 44dp touch size since the row is the target. */
private val TILE = 36.dp

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
internal enum class PullState { Open, Draft, Merged, Closed }

/** A listed pull request's state: merged, closed, a draft, or open. */
internal fun PullRow.pullState(): PullState = when {
    merged -> PullState.Merged
    state == "closed" -> PullState.Closed
    isDraft -> PullState.Draft
    else -> PullState.Open
}

/** A repository's pull request by the state GitHub gives it (`open`, `draft`, `closed`, `merged`). */
internal fun pullStateOf(state: String): PullState = when (state) {
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
internal fun StateTile(state: PullState) {
    val c = LocalPullsColors.current
    val (ink, glyph, word) = when (state) {
        PullState.Open -> Triple(c.stateOpen, Lu.prOpen, "Open")
        PullState.Draft -> Triple(c.stateDraft, Lu.prDraft, t("local.home.draft"))
        PullState.Merged -> Triple(c.stateMerged, Lu.prMerged, "Merged")
        PullState.Closed -> Triple(c.stateClosed, Lu.prClosed, "Closed")
    }
    RowTile(ink.copy(alpha = if (c.dark) 0.18f else 0.12f), Modifier.semantics { contentDescription = word }) {
        LuIcon(glyph, tint = ink, size = 20.dp)
    }
}

/** The square at the head of a row, filled with [fill]: a pull request's state, or a tour's ring. */
@Composable
internal fun RowTile(fill: Color, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(modifier.size(TILE).clip(RoundedCornerShape(PullsRadius.lg)).background(fill), contentAlignment = Alignment.Center) { content() }
}

/**
 * The frame every pull request row shares: [tile], then [title] at most two lines (a one-line title cut short reads
 * badly, N4), [under] for what a page adds (a repository's labels), and the one [meta] line. A hairline over the row
 * starts and ends on the page's 16dp margins, so the first row's is the heading's underline (rule 75).
 */
@Composable
internal fun PullRowFrame(
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
            Text(title, style = PullsType.base, color = c.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
            under?.invoke()
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp), content = meta)
        }
    }
}

/** "#279", first on the meta line where Claude's list puts the state (N4), in its own column. */
@Composable
internal fun RowNumber(number: Int) {
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
internal fun RowFacts(checks: (@Composable () -> Unit)?, additions: Int?, deletions: Int?, age: String) {
    val c = LocalPullsColors.current
    if (checks != null) {
        Box(Modifier.width(CHECK_WIDTH), contentAlignment = Alignment.CenterStart) { checks() }
        Box(Modifier.width(ADDS_WIDTH + 4.dp + DELS_WIDTH), contentAlignment = Alignment.CenterEnd) { if (additions != null) DiffCounts(additions, deletions ?: 0) }
    }
    Text(age, Modifier.width(AGE_WIDTH), style = PullsType.sm.copy(fontFeatureSettings = "tnum"), color = c.muted, textAlign = TextAlign.End, maxLines = 1, softWrap = false)
}

/**
 * `.pr`: an Inbox row. Under a kind's heading, rows from several repositories mix, so the author's column starts with
 * the [repo] name, muted like the author it comes before. A stacked pull request says where it sits in its stack right
 * after its number, "⧉ 2/10" as GitHub counts it (H16), out of the number's column where it used to sit under it (N2).
 * With [risk] (the Inbox sorted Riskiest first or grouped by kind, M1) Jev's one risk word takes that place, so the row
 * is no taller for it; a stacked row shows the risk only as its stack mark turning amber when it is high. The author is
 * one line, always across (H15), cut only when it cannot fit at all.
 */
@Composable
internal fun PullRowContent(pull: PullRow, now: Long, dimmed: Boolean, work: PullWork?, repo: String? = null, risk: Boolean = false) {
    val c = LocalPullsColors.current
    val requested = pull.requestedFromMe ?: (pull.role == "review-requested")
    PullRowFrame(AnnotatedString(pull.title), tile = { StateTile(pull.pullState()) }, dimmed = dimmed) {
        RowNumber(pull.number)
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (pull.stack != null) StackTag(pull, risk = risk) else if (risk) RiskTag(pull)
            if (requested) Box(Modifier.size(7.dp).clip(CircleShape).background(c.accentTeal))
            // One line, across (H15): a name never stacks its letters in a narrow column.
            Text(if (repo != null) "$repo · ${pull.author}" else pull.author, Modifier.weight(1f, fill = false), style = PullsType.sm, color = c.muted, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
            WorkMark(work)
        }
        RowFacts({ CheckMark(pull) }, pull.additions, pull.deletions, compactAge(pull.updatedAtMs, now))
    }
}

/**
 * Jev's risk for the pull request, after its number on the meta line, so the row is no taller for it: one word ("Low",
 * "High"), "High" in the amber the app gives warnings. Shown only while the Inbox is sorted by risk or grouped by kind,
 * so a row says nothing of Jev unless he asked for it ("do not run it by default", "we are making the rows way too
 * tall": recording 16 Oct 09:50, 00:08 to 00:37; M1). Under a kind's heading the kind is the heading.
 */
@Composable
private fun RiskTag(pull: PullRow) {
    val jev = pull.jev ?: return
    val c = LocalPullsColors.current
    val high = jev.riskLevel == JEV_RISK_WORDS.lastIndex
    Text(
        JEV_RISK_SHORT[jev.riskLevel], Modifier.semantics { contentDescription = jev.riskWords },
        style = PullsType.sm, color = if (high) c.amber else c.muted, maxLines = 1, softWrap = false,
    )
}

/** [JEV_RISK_WORDS] in one word each, for the meta line. */
private val JEV_RISK_SHORT = listOf("Trivial", "Low", "Medium", "High")

/**
 * A stacked pull request's place in its stack, after its number: the layers glyph and "2/10", its layer and how many
 * there are, counted from the bottom as GitHub does (H16). The PR page's mark and Stack sheet show the whole stack.
 */
@Composable
private fun StackTag(pull: PullRow, risk: Boolean = false) {
    val stack = pull.stack ?: return
    val c = LocalPullsColors.current
    val place = stackPlace(stack)
    val jev = pull.jev?.takeIf { risk }
    val high = jev != null && jev.riskLevel == JEV_RISK_WORDS.lastIndex
    Row(
        Modifier.semantics(mergeDescendants = true) {
            contentDescription = (place?.let { "Layer ${stack.position} of ${stack.size} in a stack" } ?: "In a stack") + (jev?.let { ", ${it.riskWords}" } ?: "")
        },
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        LuIcon(Lu.layers, tint = if (high) c.amber else c.muted, size = 14.dp)
        place?.let { Text(it, style = PullsType.sm.copy(fontFeatureSettings = "tnum"), color = c.muted, maxLines = 1, softWrap = false) }
    }
}

/** "2/10": a layer's place in its stack, or null when the size is not known. */
internal fun stackPlace(stack: PullStack): String? =
    if (stack.size >= stack.position && stack.size > 1 && stack.position > 0) "${stack.position}/${stack.size}" else null

/** The CI mark: a red cross when checks fail or the branch conflicts, a green tick when they pass, an amber dot while they run. */
@Composable
private fun CheckMark(pull: PullRow) {
    val c = LocalPullsColors.current
    when {
        pull.checks == "failure" || pull.mergeable == "conflicting" ->
            LuIcon(Lu.xc, tint = c.error, size = 18.dp, contentDescription = if (pull.checks == "failure") t("pulls.checks.failure") else t("triage.conflict"))
        pull.checks == "success" -> LuIcon(Lu.okc, tint = c.success, size = 18.dp, contentDescription = t("pulls.checks.success"))
        pull.checks == "pending" -> Box(Modifier.size(8.dp).clip(CircleShape).background(StatusColors.amber500))
    }
}

/** A repository's checks state (`success`, `failure`, `pending`) as the same mark. */
@Composable
internal fun ChecksMark(checks: String?) {
    val c = LocalPullsColors.current
    when (checks) {
        "failure" -> LuIcon(Lu.xc, tint = c.error, size = 18.dp, contentDescription = t("pulls.checks.failure"))
        "success" -> LuIcon(Lu.okc, tint = c.success, size = 18.dp, contentDescription = t("pulls.checks.success"))
        "pending" -> Box(Modifier.size(8.dp).clip(CircleShape).background(StatusColors.amber500))
    }
}

/**
 * `.dx`: `+180` in green and `−32` in red, each right-aligned in a fixed column in the mono face, side by side. Every
 * list that counts lines (the Inbox, the Files sheet, a file section's heading) draws them with this one, so the numbers
 * of a list sit in one column (K27; recording 16 Oct 12:10, 18:03 to 18:21, N35).
 */
@Composable
internal fun DiffCounts(additions: Int, deletions: Int) {
    val c = LocalPullsColors.current
    val mono = PullsType.sm.copy(fontFamily = PullsType.mono, fontWeight = FontWeight.Medium, textAlign = TextAlign.End, fontFeatureSettings = "tnum")
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("+${compactCount(additions)}", Modifier.width(ADDS_WIDTH), style = mono, color = c.success, maxLines = 1, softWrap = false)
        Text("−${compactCount(deletions)}", Modifier.width(DELS_WIDTH), style = mono, color = c.error, maxLines = 1, softWrap = false)
    }
}

/**
 * A Tours row is the pull request row with the tour's ring in the tile's place: the ring says how far you read, the orb
 * turns while the tour is written, and a tour read to the end is a filled tick, so nothing has to say "Read" (H27).
 * When the pull request is not in the Inbox its author's column says where the tour stands instead.
 */
@Composable
internal fun TourRowContent(entry: TourEntry, pull: PullRow?, now: Long) {
    val c = LocalPullsColors.current
    PullRowFrame(
        AnnotatedString(entry.prTitle),
        tile = {
            RowTile(c.sunken) {
                when {
                    entry.writingAt(now) -> Orb(size = 22.dp)
                    entry.doneAt(now) -> OkDot(size = 20.dp)
                    else -> ProgressRing(entry.reached, entry.stops, size = 22.dp)
                }
            }
        },
    ) {
        RowNumber(entry.number)
        // One line, so it sits level with the checks, the numbers and the age (H28).
        Text(pull?.author ?: tourState(entry, now), Modifier.weight(1f), style = PullsType.sm, color = c.muted, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
        val checks: (@Composable () -> Unit)? = if (pull != null) ({ CheckMark(pull) }) else null
        RowFacts(checks, pull?.additions, pull?.deletions, compactAge(pull?.updatedAtMs ?: entry.updatedAt, now))
    }
}
