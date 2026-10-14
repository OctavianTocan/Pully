package com.octaviantocan.pulls.ui.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.octaviantocan.pulls.core.PullRef
import com.octaviantocan.pulls.core.PullWork
import com.octaviantocan.pulls.core.WorkTask
import com.octaviantocan.pulls.core.parsePullKey
import com.octaviantocan.pulls.core.pullWorkByKey
import com.octaviantocan.pulls.core.runningWork
import com.octaviantocan.pulls.data.Repo
import com.octaviantocan.pulls.data.TaskState
import com.octaviantocan.pulls.ui.design.BreathingDot
import com.octaviantocan.pulls.ui.design.LocalPullsColors
import com.octaviantocan.pulls.ui.design.Lu
import com.octaviantocan.pulls.ui.design.LuIcon
import com.octaviantocan.pulls.ui.design.PullsSheet
import com.octaviantocan.pulls.ui.design.PullsType
import com.octaviantocan.pulls.ui.design.SheetCard
import kotlinx.coroutines.delay

/*
 * What the server is analyzing, across every pull request. Analysis runs on the server and outlives the page, so
 * the Inbox can say which pull requests are being worked on and which just finished, without opening them.
 */

internal fun TaskState.asWork(): WorkTask = WorkTask(key = key, kind = kind, status = status, title = title, startedAt = startedAt, id = id)

/** Every diff's running and recent AI tasks, read again every few seconds while the Inbox is on screen (slower when idle). */
@Composable
internal fun rememberWork(repo: Repo): List<WorkTask> {
    var tasks by remember { mutableStateOf(emptyList<WorkTask>()) }
    LaunchedEffect(repo) {
        while (true) {
            runCatching { repo.allTasks() }.onSuccess { list -> tasks = list.map { it.asWork() } }
            delay(if (tasks.any { it.status == "running" }) 4_000 else 15_000)
        }
    }
    return tasks
}

/** The small mark after the author on an Inbox row. */
@Composable
internal fun WorkMark(work: PullWork?) {
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

/** A line above the list while pull requests are being analyzed; it opens the list of them. */
@Composable
internal fun ActivityStrip(tasks: List<WorkTask>, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val c = LocalPullsColors.current
    val count = pullWorkByKey(tasks).count { it.value == PullWork.Analyzing }
    if (count == 0) return
    Row(
        modifier.fillMaxWidth().defaultMinSize(minHeight = 44.dp).clickable(role = Role.Button, onClickLabel = "Show what is running", onClick = onClick).padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        BreathingDot()
        Text(if (count == 1) "Analyzing 1 pull request" else "Analyzing $count pull requests", Modifier.weight(1f), style = PullsType.sm, color = c.text, maxLines = 1)
        LuIcon(Lu.chev, tint = c.faint, size = 18.dp)
    }
}

/** The pull requests being worked on, each with what is running for it; tapping one opens it. */
@Composable
internal fun ActivitySheet(tasks: List<WorkTask>, onOpen: (PullRef) -> Unit, onClose: () -> Unit) {
    val c = LocalPullsColors.current
    val running = pullWorkByKey(tasks).filterValues { it == PullWork.Analyzing }.keys
    val rows = tasks.filter { it.key in running }.distinctBy { it.key }
    PullsSheet(onClose, "Running now", expandable = true) {
        SheetCard {
            if (rows.isEmpty()) Text("Nothing is running.", Modifier.padding(16.dp), style = PullsType.sm, color = c.muted)
            rows.forEachIndexed { index, task ->
                val ref = parsePullKey(task.key)
                Column(
                    Modifier.fillMaxWidth()
                        .drawBehind { if (index > 0) drawRect(c.borderMute, Offset(16.dp.toPx(), 0f), Size(size.width - 16.dp.toPx(), 1.dp.toPx())) }
                        .then(if (ref != null) Modifier.clickable(role = Role.Button, onClickLabel = "Open ${task.title ?: task.key}") { onOpen(ref); onClose() } else Modifier)
                        .padding(horizontal = 16.dp, vertical = 11.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(task.title ?: task.key, style = PullsType.row.copy(fontWeight = FontWeight.Medium), color = c.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(
                        listOfNotNull(ref?.let { "${it.repo} #${it.number}" }, runningWork(tasks, task.key).joinToString(", ").ifBlank { null }).joinToString(" · "),
                        style = PullsType.sm, color = c.muted, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}
