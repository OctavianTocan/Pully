package com.octaviantocan.pulls.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
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
import com.octaviantocan.pulls.ui.design.PullsRadius
import com.octaviantocan.pulls.ui.design.PullsType
import com.octaviantocan.pulls.ui.design.t

/** The host supplies its existing translated state description to the shared tile. */
@Composable
fun StateTile(state: PullState) {
    val description = when (state) {
        PullState.Open -> "Open"
        PullState.Draft -> t("local.home.draft")
        PullState.Merged -> "Merged"
        PullState.Closed -> "Closed"
    }
    StateTile(state, description)
}

/** A listed pull request's state: merged, closed, a draft, or open. */
fun PullRow.pullState(): PullState = when {
    merged -> PullState.Merged
    state == "closed" -> PullState.Closed
    isDraft -> PullState.Draft
    else -> PullState.Open
}

/**
 * A stack row's tile: the row's square drawn as an outline in the bottom layer's state colour around the layers mark,
 * so a stack does not read as one more pull request beside the filled tiles ("it blends too much with the PRs
 * themselves": Tavi, 17 Oct 17:54; rule 202).
 */
@Composable
private fun StackTile(ink: Color, modifier: Modifier) {
    val shape = RoundedCornerShape(PullsRadius.lg)
    Box(modifier.size(TILE).clip(shape).border(1.5.dp, ink.copy(alpha = 0.7f), shape), contentAlignment = Alignment.Center) {
        LuIcon(Lu.layers, tint = ink, size = TILE_MARK)
    }
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
fun PullRowContent(pull: PullRow, now: Long, dimmed: Boolean, work: PullWork?, repo: String? = null, risk: Boolean = false) {
    val c = LocalPullsColors.current
    val requested = pull.requestedFromMe ?: (pull.role == "review-requested")
    PullRowFrame(AnnotatedString(pull.title), tile = { StateTile(pull.pullState()) }, dimmed = dimmed, open = isOpenBeside(pull.owner, pull.repo, pull.number)) {
        RowNumber(pull.number)
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (pull.stack != null) StackTag(pull, risk = risk) else if (risk) RiskTag(pull)
            if (requested) Box(Modifier.size(7.dp).clip(CircleShape).background(c.accentTeal))
            // One line, across (H15): a name never stacks its letters in a narrow column.
            Text(if (repo != null) "$repo · ${pull.author}" else pull.author, Modifier.weight(1f, fill = false), style = PullsType.sm, color = c.muted, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
            WorkMark(work)
        }
        RowFacts({ PullChecksMark(pull) }, pull.additions, pull.deletions, compactAge(pull.updatedAtMs, now))
    }
}

/**
 * `.stack`: the layers of one stack in the Inbox as one row (Tavi, 17 Oct 17:40; rule 202). The tile is an outline
 * ([StackTile]) around the layers mark in the bottom layer's state colour, the title and number are the bottom layer's,
 * the one that merges first, and the meta line carries the stack mark with how many layers the row holds. The checks
 * are the worst of the layers', the + and − their sum and the age the newest. A tap or a hold opens the Stack sheet
 * ([InboxStackSheet]).
 */
@Composable
fun StackRowContent(layers: List<PullRow>, now: Long, repo: String? = null) {
    val c = LocalPullsColors.current
    val bottom = layers.first()
    val ink = stateInk(bottom.pullState())
    val requested = layers.any { it.requestedFromMe ?: (it.role == "review-requested") }
    val worst = layers.firstOrNull { it.checks == "failure" || it.mergeable == "conflicting" }
        ?: layers.firstOrNull { it.checks == "pending" }
        ?: layers.firstOrNull { it.checks == "success" }
        ?: bottom
    val counted = layers.filter { it.additions != null }
    PullRowFrame(
        AnnotatedString(bottom.title),
        tile = {
            StackTile(ink, Modifier.semantics { contentDescription = "A stack of ${layers.size} pull requests" })
        },
    ) {
        RowNumber(bottom.number)
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            // The stack mark a lone layer carries, with how many layers the row holds ("Just use the icon we were already
            // using for stack and type a 3 next to it": Tavi, 17 Oct 17:51).
            Row(
                Modifier.semantics(mergeDescendants = true) { contentDescription = "${layers.size} pull requests in a stack" },
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                LuIcon(Lu.layers, tint = c.muted, size = 14.dp)
                Text("${layers.size}", style = PullsType.sm.copy(fontFeatureSettings = "tnum"), color = c.muted, maxLines = 1, softWrap = false)
            }
            if (requested) Box(Modifier.size(7.dp).clip(CircleShape).background(c.accentTeal))
            if (repo != null) Text(repo, Modifier.weight(1f, fill = false), style = PullsType.sm, color = c.muted, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
        }
        RowFacts(
            { PullChecksMark(worst) },
            if (counted.isEmpty()) null else counted.sumOf { it.additions ?: 0 },
            if (counted.isEmpty()) null else counted.sumOf { it.deletions ?: 0 },
            compactAge(layers.mapNotNull { it.updatedAtMs }.maxOrNull(), now),
        )
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
fun stackPlace(stack: PullStack): String? =
    if (stack.size >= stack.position && stack.size > 1 && stack.position > 0) "${stack.position}/${stack.size}" else null

/** The CI mark: a red cross when checks fail or the branch conflicts, a green tick when they pass, an amber dot while they run. */
@Composable
fun PullChecksMark(pull: PullRow) {
    val c = LocalPullsColors.current
    when {
        pull.checks == "failure" || pull.mergeable == "conflicting" ->
            LuIcon(Lu.xc, tint = c.error, size = 18.dp, contentDescription = if (pull.checks == "failure") t("pulls.checks.failure") else t("triage.conflict"))
        pull.checks == "success" -> LuIcon(Lu.okc, tint = c.success, size = 18.dp, contentDescription = t("pulls.checks.success"))
        pull.checks == "pending" -> Box(Modifier.size(8.dp).clip(CircleShape).background(c.checksPending))
    }
}

/** A repository's checks state (`success`, `failure`, `pending`) as the same mark. */
@Composable
fun ChecksMark(checks: String?) {
    val c = LocalPullsColors.current
    when (checks) {
        "failure" -> LuIcon(Lu.xc, tint = c.error, size = 18.dp, contentDescription = t("pulls.checks.failure"))
        "success" -> LuIcon(Lu.okc, tint = c.success, size = 18.dp, contentDescription = t("pulls.checks.success"))
        "pending" -> Box(Modifier.size(8.dp).clip(CircleShape).background(c.checksPending))
    }
}
