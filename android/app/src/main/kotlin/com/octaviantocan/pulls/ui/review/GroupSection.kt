package com.octaviantocan.pulls.ui.review

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.octaviantocan.pulls.data.FileGroup
import com.octaviantocan.pulls.data.ReviewFile
import com.octaviantocan.pulls.ui.design.DiffStats
import com.octaviantocan.pulls.ui.design.LocalPullsColors
import com.octaviantocan.pulls.ui.design.Markdown
import com.octaviantocan.pulls.ui.design.PhIcon
import com.octaviantocan.pulls.ui.design.PullsColors
import com.octaviantocan.pulls.ui.design.PullsType
import com.octaviantocan.pulls.ui.design.t
import com.octaviantocan.pulls.ui.design.tc

/*
 * Port of `DiffGroup.vue` headers (chapter band and normal header), `GroupCategoryIcon.vue`
 * with `category-icons.ts`, `DisplayDonut` and the inline `DiffGroupNav` chips.
 */

/** `DisplayDonut`: a progress ring, track at `op-mute`, progress in `color-active`. */
@Composable
fun Donut(value: Float, size: Dp = 12.dp, thickness: Dp = 2.5.dp, color: Color? = null, modifier: Modifier = Modifier) {
    val c = LocalPullsColors.current
    val track = c.mute
    val fg = color ?: c.activeText
    Canvas(modifier.size(size)) {
        val stroke = thickness.toPx()
        val inset = stroke / 2
        val arcSize = Size(this.size.width - stroke, this.size.height - stroke)
        drawArc(track, 0f, 360f, false, Offset(inset, inset), arcSize, style = Stroke(stroke))
        val sweep = 360f * value.coerceIn(0f, 1f)
        if (sweep > 0f) drawArc(fg, -90f, sweep, false, Offset(inset, inset), arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
    }
}

/** A group with its resolved files, the web `ResolvedGroup` (+ the label the uncategorized group gets). */
data class GroupView(val group: FileGroup, val label: String, val summary: String, val files: List<ReviewFile>, val missing: List<String>, val children: List<GroupView>) {
    val key: String get() = group.key
    val allFiles: List<ReviewFile> get() = files + children.flatMap { it.files }
    val added: Int get() = allFiles.sumOf { it.additions }
    val deleted: Int get() = allFiles.sumOf { it.deletions }
}

fun groupProgress(view: GroupView, reviewed: Set<String>): Float {
    val files = view.allFiles
    if (files.isEmpty()) return 1f
    return files.count { it.sha in reviewed }.toFloat() / files.size
}

@Composable
private fun Caret(collapsed: Boolean, top: Dp) {
    val c = LocalPullsColors.current
    // `ActionIconButton compact ml--5 op-mute`: the caret hangs in the left padding.
    Box(Modifier.padding(top = top).size(20.dp), contentAlignment = Alignment.Center) {
        PhIcon(if (collapsed) "caret-right" else "caret-down", tint = c.mute, size = 16.dp, contentDescription = t("group.toggle"))
    }
}

/** The chapter band of a parent group with children (`isChapter`). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ChapterHeader(view: GroupView, collapsed: Boolean, reviewed: Set<String>, onToggle: () -> Unit, onChild: (String) -> Unit) {
    val c = LocalPullsColors.current
    Column(
        Modifier.fillMaxWidth().background(c.base).drawBehind {
            drawLine(c.border, Offset(0f, size.height - 0.5f), Offset(size.width, size.height - 0.5f), 1.dp.toPx())
        }.padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(4.dp)).clickable(role = Role.Button, onClick = onToggle).padding(end = 8.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Caret(collapsed, top = 2.dp)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    GroupCategoryIcon(view.group.category, size = 20.dp)
                    Text(view.label, style = PullsType.xxl.copy(fontWeight = FontWeight.Medium, lineHeight = 26.sp), color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                GroupStatsRow(view, reviewed, subgroups = true)
            }
        }
        if (!collapsed) {
            if (view.summary.isNotBlank()) Markdown(view.summary, Modifier.padding(horizontal = 8.dp), PullsType.sm.copy(color = c.fade))
            FlowRow(Modifier.padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                view.children.forEach { child -> GroupNavChip(child, reviewed) { onChild(child.key) } }
            }
        }
    }
}

/** `DiffGroupNavItem`, the inline sub-nav of a chapter (phones have no group sidebar). */
@Composable
private fun GroupNavChip(view: GroupView, reviewed: Set<String>, onClick: () -> Unit) {
    val c = LocalPullsColors.current
    val hue = categoryColor(view.group.category, c.dark)
    val shape = RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp)
    Row(
        Modifier
            .defaultMinSize(minHeight = 44.dp)
            .clip(shape)
            .border(1.dp, c.border, shape)
            .drawBehind { drawLine(c.border, Offset(0f, size.height - 1.dp.toPx()), Offset(size.width, size.height - 1.dp.toPx()), 2.dp.toPx()) }
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                PhIcon(CATEGORY_ICON[view.group.category] ?: CATEGORY_ICON.getValue("other"), tint = hue, size = 14.dp)
                Text(view.label, style = PullsType.sm, color = c.fade, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                DiffStats(view.added, view.deleted)
                Text("・" + tc("common.files", view.allFiles.size), style = PullsType.xs, color = c.mute)
            }
        }
        val progress = groupProgress(view, reviewed)
        if (progress > 0f) Donut(progress, size = 18.dp, thickness = 2.dp, color = c.text)
    }
}

@Composable
private fun GroupStatsRow(view: GroupView, reviewed: Set<String>, subgroups: Boolean) {
    val c = LocalPullsColors.current
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        DiffStats(view.added, view.deleted)
        Text(tc("common.files", view.allFiles.size), style = PullsType.xs, color = c.fade)
        if (subgroups) Text(tc("common.subgroups", view.children.size), style = PullsType.xs, color = c.fade)
        Donut(groupProgress(view, reviewed))
    }
}

/** The normal group header in the aside, with the parent breadcrumb for a chapter's child. */
@Composable
fun GroupHeader(view: GroupView, collapsed: Boolean, reviewed: Set<String>, parentLabel: String?, onToggle: () -> Unit, onParent: () -> Unit) {
    val c = LocalPullsColors.current
    Column(Modifier.fillMaxWidth().background(c.base).padding(start = 12.dp, end = 12.dp, top = 12.dp)) {
        if (parentLabel != null) {
            Text(
                "$parentLabel ›",
                Modifier.clip(RoundedCornerShape(4.dp)).clickable(role = Role.Button, onClick = onParent).padding(start = 8.dp, top = 8.dp, bottom = 8.dp, end = 8.dp),
                style = PullsType.xs, color = c.fade, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(4.dp)).clickable(role = Role.Button, onClick = onToggle).padding(end = 8.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Caret(collapsed, top = 4.dp)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    GroupCategoryIcon(view.group.category, size = 16.dp)
                    // `useFitText(label, 14, 20)`: the label shrinks to fit before it truncates.
                    BasicText(
                        view.label,
                        Modifier.weight(1f),
                        style = PullsType.xl.copy(fontWeight = FontWeight.Medium, color = c.text),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        autoSize = TextAutoSize.StepBased(minFontSize = 14.sp, maxFontSize = 20.sp, stepSize = 1.sp),
                    )
                }
                GroupStatsRow(view, reviewed, subgroups = false)
            }
        }
        if (!collapsed && view.summary.isNotBlank()) {
            Spacer(Modifier.height(12.dp))
            Box(Modifier.fillMaxWidth().drawBehind {
                drawLine(c.border, Offset(0f, size.height - 0.5f), Offset(size.width, size.height - 0.5f), 1.dp.toPx())
            }.padding(start = 8.dp, bottom = 8.dp)) {
                Markdown(view.summary, style = PullsType.sm.copy(color = c.fade))
            }
        }
        Spacer(Modifier.height(12.dp))
    }
}

/** `mt-200 p2 text-center text-xs italic op50`. */
@Composable
fun EndOfDiff() {
    val c = LocalPullsColors.current
    Box(Modifier.fillMaxWidth().padding(top = 160.dp).padding(8.dp), contentAlignment = Alignment.Center) {
        Text(t("pr.endOfDiff"), style = PullsType.xs.copy(fontStyle = androidx.compose.ui.text.font.FontStyle.Italic), color = c.text.copy(alpha = 0.5f))
    }
}
