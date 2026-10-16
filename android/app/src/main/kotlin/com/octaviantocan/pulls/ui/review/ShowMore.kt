package com.octaviantocan.pulls.ui.review

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.octaviantocan.pulls.sound.Cue
import com.octaviantocan.pulls.sound.cueClickable
import com.octaviantocan.pulls.ui.design.LocalPullsColors
import com.octaviantocan.pulls.ui.design.Lu
import com.octaviantocan.pulls.ui.design.LuIcon
import com.octaviantocan.pulls.ui.design.PullsType
import com.octaviantocan.pulls.ui.design.TOUCH
import com.octaviantocan.pulls.ui.design.hairlineTop

/*
 * The one way a long list on the Overview stops early, as Claude's own lists do: its first few rows, then a divider and
 * "Show 7 more ⌄", which opens the rest in place. It is not a fold: there is no heading to tap and nothing closes again
 * ("they show a few rows and Show 7 more", recording of 16 Oct 12:10, 02:14 to 02:36, frames 68 to 77; N5).
 */

/** How many rows a long list shows before its [ShowMore]. */
internal const val FIRST_FEW = 5

/**
 * How many of [total] rows a list shows: all of them once [open], or when hiding them would leave a "Show 1 more" (one
 * more row is cheaper than the line that offers it); otherwise the first [first].
 */
internal fun firstFew(total: Int, open: Boolean, first: Int = FIRST_FEW): Int = if (open || total <= first + 1) total else first

/**
 * How many of the Overview's file [sections] show before "Show N more": the first few, carried on to the end of a run
 * that shares a heading (N34), so a heading never stands over only some of its groups.
 */
internal fun firstFewSections(sections: List<FileSection>, open: Boolean): Int {
    var cut = firstFew(sections.size, open)
    while (cut in 1 until sections.size && sections[cut].heading != null && !opensHeading(sections[cut], sections[cut - 1])) cut++
    return cut
}

/**
 * "Show N more ⌄" under the first rows of a long list: a hairline over it like the rows', then the words and a down
 * chevron in the muted ink at body size (rule 14). A tap opens the [hidden] rows in place with [onClick].
 */
@Composable
internal fun ShowMore(hidden: Int, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val c = LocalPullsColors.current
    val words = "Show $hidden more"
    Row(
        modifier.fillMaxWidth().hairlineTop(c.borderMute).defaultMinSize(minHeight = TOUCH)
            .cueClickable(Cue.Toggle, role = Role.Button, onClickLabel = words, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(words, style = PullsType.sm, color = c.muted, maxLines = 1)
        LuIcon(Lu.chevd, tint = c.muted, size = 16.dp)
    }
}
