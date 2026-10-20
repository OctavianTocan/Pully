package com.octaviantocan.pulls.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.octaviantocan.pulls.data.PullRow
import com.octaviantocan.pulls.ui.design.LocalPullsColors
import com.octaviantocan.pulls.ui.design.Lu
import com.octaviantocan.pulls.ui.design.LuIcon
import com.octaviantocan.pulls.ui.design.PullsSpace
import com.octaviantocan.pulls.ui.design.PullsType
import com.octaviantocan.pulls.ui.design.SheetCard
import com.octaviantocan.pulls.ui.design.SheetTopGap
import com.octaviantocan.pulls.ui.design.t
import com.octaviantocan.pulls.ui.design.tc

/** The PR hold sheet's summary: whole identifiers get their own width, and facts wrap below them. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun PullSummary(pull: PullRow) {
    val c = LocalPullsColors.current
    val state = when {
        pull.merged || pull.state == "merged" -> "merged"
        pull.state == "closed" -> "closed"
        pull.state == "open" && pull.isDraft -> "draft"
        pull.state == "open" || pull.state == "draft" -> pull.state
        else -> null
    }
    SheetCard(gap = SheetTopGap) {
        Column(
            Modifier.fillMaxWidth().padding(PullsSpace.x4),
            verticalArrangement = Arrangement.spacedBy(PullsSpace.x3),
        ) {
            Text(
                pull.title, Modifier.fillMaxWidth().semantics { heading() },
                style = PullsType.xl.copy(fontWeight = FontWeight.Medium), color = c.text,
            )
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(PullsSpace.x1)) {
                Text("${pull.owner}/${pull.repo}", Modifier.fillMaxWidth(), style = PullsType.base, color = c.text)
                if (pull.author.isNotBlank()) Text(pull.author, Modifier.fillMaxWidth(), style = PullsType.base, color = c.muted)
            }
            FlowRow(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(PullsSpace.x4),
                verticalArrangement = Arrangement.spacedBy(PullsSpace.x2),
                itemVerticalAlignment = Alignment.CenterVertically,
            ) {
                if (state != null) {
                    val (glyph, ink) = when (state) {
                        "merged" -> Lu.prMerged to c.stateMerged
                        "closed" -> Lu.prClosed to c.stateClosed
                        "draft" -> Lu.prDraft to c.stateDraft
                        else -> Lu.prOpen to c.stateOpen
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(PullsSpace.x1)) {
                        LuIcon(glyph, tint = ink, size = 18.dp)
                        Text(t("prState.$state"), style = PullsType.sm, color = ink)
                    }
                }
                pull.changedFiles?.let { Text(tc("common.files", it), style = PullsType.sm, color = c.muted) }
                pull.additions?.let { Text("+${"%,d".format(it)}", style = PullsType.num, color = c.success) }
                pull.deletions?.let { Text("−${"%,d".format(it)}", style = PullsType.num, color = c.error) }
            }
        }
    }
}
