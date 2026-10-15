@file:OptIn(ExperimentalLayoutApi::class)

package com.octaviantocan.pulls.ui.review

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.semantics.Role
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
 * Which commits the changes are narrowed to, as their numbers in the pull request ("4" or "4–6"), or the short shas when
 * they are not in the list GitHub sent; null when the changes show the whole pull request.
 */
fun commitNumbers(selection: Pair<String, String>?, overview: PrOverview?): String? {
    val picked = selection ?: return null
    val commits = overview?.commits.orEmpty()
    val range = resolveCommitSelection(commits, CommitSelection(picked.first, picked.second))
    // Commits GitHub did not return are the oldest ones, so they shift every position.
    val offset = overview?.commitsHidden ?: 0
    return when {
        range != null && range.first == range.last -> "${range.first + 1 + offset}"
        range != null -> "${range.first + 1 + offset}–${range.last + 1 + offset}"
        picked.first == picked.second -> picked.first.take(7)
        else -> "${picked.first.take(7)}–${picked.second.take(7)}"
    }
}

/**
 * What the changes are narrowed to, in a few words ("Only commit 4", "Only commits 4–6"), or null when they show the
 * whole pull request. It replaces the box that sat over the pages (K25): the Commits number on the Overview and the
 * file pill on the Diff say it, and the Commits sheet's "All commits" goes back. "Commit 4 of 8" read like a page
 * count beside the file's own "3/12" ("it says commit 4 of 8 ... kind of confusing", recording 15 Oct 15:37, 07:06 to
 * 07:17; L11).
 */
fun commitLabel(selection: Pair<String, String>?, overview: PrOverview?): String? {
    val numbers = commitNumbers(selection, overview) ?: return null
    val several = numbers.contains('–')
    return if (several) "Only commits $numbers" else "Only commit $numbers"
}

/** Whether the PR has moved on since the diff on screen was fetched (the web's `isStale`). */
fun isStale(session: ReviewSession, overview: PrOverview?): Boolean {
    if (session.overviewStored) return false
    val head = overview?.headOid?.takeIf { it.isNotBlank() } ?: return false
    val shown = session.prOpened?.headSha?.takeIf { it.isNotBlank() } ?: return false
    return head != shown
}

/**
 * The stale banner from `DiffsPage.vue` (`border-amber:20 bg-amber:10 text-amber-700
 * dark:text-amber-400`): "has new commits", an "Auto refresh next time" checkbox (on by
 * default; Refresh commits it to the setting) and Refresh. Only a reader who switched auto refresh
 * off sees it; otherwise the page takes new commits by itself.
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
