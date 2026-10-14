@file:OptIn(ExperimentalLayoutApi::class)

package com.octaviantocan.pulls.ui.review

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.octaviantocan.pulls.sound.cueClickable
import com.octaviantocan.pulls.sound.directionOf
import com.octaviantocan.pulls.ui.design.FloatIcon
import com.octaviantocan.pulls.ui.design.LocalPullsColors
import com.octaviantocan.pulls.ui.design.Lu
import com.octaviantocan.pulls.ui.design.LuIcon
import com.octaviantocan.pulls.ui.design.PillButton
import com.octaviantocan.pulls.ui.design.PullsCheck
import com.octaviantocan.pulls.ui.design.PullsRadius
import com.octaviantocan.pulls.ui.design.PullsShadow
import com.octaviantocan.pulls.ui.design.PullsType
import com.octaviantocan.pulls.ui.design.TOUCH
import com.octaviantocan.pulls.ui.design.floatShadow
import com.octaviantocan.pulls.ui.design.t

/**
 * Port of `CommitRangeBanner.vue`: what the diff is narrowed to ("Viewing 2 of 5 commits"
 * and the headline, a range, or bare shas), previous / next commit, and "Show all changes".
 */
@Composable
fun CommitRangeBanner(session: ReviewSession, overview: PrOverview?, modifier: Modifier = Modifier) {
    val picked = session.selection ?: return
    val c = LocalPullsColors.current
    val selection = CommitSelection(picked.first, picked.second)
    val commits = overview?.commits.orEmpty()
    val range = resolveCommitSelection(commits, selection)
    val (previous, next) = adjacentCommits(commits, selection)
    // Commits GitHub did not return are the oldest ones, so they shift every position.
    val offset = overview?.commitsHidden ?: 0
    val total = commits.size + offset
    val shape = RoundedCornerShape(PullsRadius.card)
    FlowRow(
        modifier.fillMaxWidth().floatShadow(shape, PullsShadow.Note).clip(shape).background(c.raised).padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        itemVerticalAlignment = Alignment.CenterVertically,
    ) {
        LuIcon(Lu.commit, tint = c.muted, size = 20.dp)
        when {
            range != null && range.first == range.last -> Row(Modifier.weight(1f, fill = false), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(t("overview.selection.viewing", "index" to range.first + 1 + offset, "total" to total), style = PullsType.sm, color = c.text, maxLines = 1)
                commits.getOrNull(range.first)?.headline?.let { Text(it, Modifier.weight(1f, fill = false), style = PullsType.sm, color = c.fade, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            }
            range != null -> Text(t("overview.selection.viewingRange", "start" to range.first + 1 + offset, "end" to range.last + 1 + offset, "total" to total), style = PullsType.sm, color = c.text)
            selection.from == selection.to -> Text(t("overview.selection.viewingSha", "sha" to selection.from.take(7)), style = PullsType.sm.copy(fontFamily = PullsType.mono), color = c.text)
            else -> Text(t("overview.selection.viewingShaRange", "from" to selection.from.take(7), "to" to selection.to.take(7)), style = PullsType.sm.copy(fontFamily = PullsType.mono), color = c.text)
        }
        Spacer(Modifier.weight(1f))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            FloatIcon(Lu.prev, t("overview.selection.previous"), { session.selectCommits(previous?.pair) }, flat = true, size = 40.dp, enabled = previous != null)
            FloatIcon(Lu.next, t("overview.selection.next"), { session.selectCommits(next?.pair) }, flat = true, size = 40.dp, enabled = next != null)
        }
        PillButton(t("overview.selection.showAll"), { session.selectCommits(null) })
    }
}

/** Whether the PR has moved on since the diff on screen was fetched (the web's `isStale`). */
fun isStale(session: ReviewSession, overview: PrOverview?): Boolean {
    val head = overview?.headOid?.takeIf { it.isNotBlank() } ?: return false
    val shown = session.prOpened?.headSha?.takeIf { it.isNotBlank() } ?: return false
    return head != shown
}

/**
 * The stale banner from `DiffsPage.vue` (`border-amber:20 bg-amber:10 text-amber-700
 * dark:text-amber-400`): "has new commits", an "Auto refresh next time" checkbox (on by
 * default; Refresh commits it to the setting) and Refresh.
 */
@Composable
fun StaleBanner(session: ReviewSession, onRefresh: () -> Unit, modifier: Modifier = Modifier) {
    val c = LocalPullsColors.current
    val text = WebColors.amberText(c.dark)
    var autoNextTime by rememberSaveable { mutableStateOf(true) }
    val shape = RoundedCornerShape(PullsRadius.card)
    FlowRow(
        modifier.fillMaxWidth().floatShadow(shape, PullsShadow.Note).clip(shape).background(c.raised).padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.Start),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        itemVerticalAlignment = Alignment.CenterVertically,
    ) {
        Text(t("pr.newCommits"), Modifier.weight(1f, fill = false), style = PullsType.sm, color = text)
        Spacer(Modifier.weight(1f))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(
                Modifier.defaultMinSize(minHeight = TOUCH).clip(RoundedCornerShape(PullsRadius.md)).cueClickable(direction = directionOf(!autoNextTime), role = Role.Checkbox) { autoNextTime = !autoNextTime },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                PullsCheck(autoNextTime, size = 20.dp)
                Text(t("pr.autoRefreshNextTime"), style = PullsType.sm, color = c.text)
            }
            PillButton(t("common.refresh"), {
                session.settings.updateAutoRefresh(autoNextTime)
                onRefresh()
            })
        }
    }
}
