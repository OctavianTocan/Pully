package com.octaviantocan.pulls.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
 * (recording 16 Oct 12:10, 01:23 to 03:05; N2, N4): a tinted square with the pull request's state on the left, the
 * title beside it on at most two lines, and one line under the title that starts with the number and then carries the
 * author, the checks, the coloured diffstat and the age, each in a column of its own that does not move when a digit or
 * a name's length changes (rules 13, 15 and 36; N35). The title never runs to a third line (Q1; Tavi, 17 Oct 17:14).
 */

/**
 * A Tours row is the pull request row with the tour's ring in the tile's place: the ring says how far you read, the orb
 * turns while the tour is written, and a tour read to the end is a filled tick, so nothing has to say "Read" (H27).
 * When the pull request is not in the Inbox its author's column says where the tour stands instead.
 */
@Composable
fun TourRowContent(entry: TourEntry, pull: PullRow?, now: Long) {
    val c = LocalPullsColors.current
    PullRowFrame(
        AnnotatedString(entry.prTitle),
        open = isOpenBeside(entry.owner, entry.repo, entry.number),
        tile = {
            RowTile(c.sunken) {
                when {
                    entry.writingAt(now) -> Orb(size = TILE_MARK)
                    entry.doneAt(now) -> OkDot(size = TILE_MARK)
                    else -> ProgressRing(entry.reached, entry.stops, size = TILE_MARK)
                }
            }
        },
    ) {
        RowNumber(entry.number)
        // One line, so it sits level with the checks, the numbers and the age (H28).
        Text(pull?.author ?: tourState(entry, now), Modifier.weight(1f), style = PullsType.sm, color = c.muted, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
        val checks: (@Composable () -> Unit)? = if (pull != null) ({ PullChecksMark(pull) }) else null
        RowFacts(checks, pull?.additions, pull?.deletions, compactAge(pull?.updatedAtMs ?: entry.updatedAt, now))
    }
}
