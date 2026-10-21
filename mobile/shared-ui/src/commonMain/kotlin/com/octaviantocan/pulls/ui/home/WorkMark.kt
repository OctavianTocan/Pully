package com.octaviantocan.pulls.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import com.octaviantocan.pulls.core.PullWork
import com.octaviantocan.pulls.ui.design.BreathingDot
import com.octaviantocan.pulls.ui.design.LocalPullsColors
import com.octaviantocan.pulls.ui.design.PullsType

/** The small mark after the author on an Inbox row. */
@Composable
fun WorkMark(work: PullWork?) {
    val c = LocalPullsColors.current
    when (work) {
        PullWork.Analyzing -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            BreathingDot(size = 7.dp)
            Text("Analyzing", style = PullsType.sm, color = c.activeText, maxLines = 1)
        }
        PullWork.Analyzed -> Text("Analyzed", style = PullsType.sm, color = c.muted, maxLines = 1)
        null -> Unit
    }
}
