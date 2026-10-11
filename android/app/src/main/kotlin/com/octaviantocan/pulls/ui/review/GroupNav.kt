@file:OptIn(ExperimentalLayoutApi::class)

package com.octaviantocan.pulls.ui.review

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.octaviantocan.pulls.data.FileGroup
import com.octaviantocan.pulls.data.ReviewFile
import com.octaviantocan.pulls.ui.design.DiffStats
import com.octaviantocan.pulls.ui.design.IconButton
import com.octaviantocan.pulls.ui.design.LocalPullsColors
import com.octaviantocan.pulls.ui.design.PhIcon
import com.octaviantocan.pulls.ui.design.PullsType
import com.octaviantocan.pulls.ui.design.TOUCH
import com.octaviantocan.pulls.ui.design.t
import com.octaviantocan.pulls.ui.design.tc

/** `CATEGORY_ICON` from `components/diff/category-icons.ts`. */
val CATEGORY_ICON: Map<String, String> = mapOf(
    "ui" to "compass-tool-duotone",
    "api" to "plugs-connected-duotone",
    "core" to "code-duotone",
    "data" to "database-duotone",
    "cli" to "terminal-duotone",
    "security" to "shield-check-duotone",
    "tests" to "flask-duotone",
    "docs" to "book-open-duotone",
    "examples" to "play-circle-duotone",
    "deps" to "package-duotone",
    "build" to "hammer-duotone",
    "scripts" to "wrench-duotone",
    "config" to "sliders-horizontal-duotone",
    "i18n" to "translate-duotone",
    "assets" to "image-duotone",
    "other" to "dots-three-circle-duotone",
)

/**
 * `CATEGORY_COLOR_CLASS`: one hue per category, the `-800` stop in light and `-300` in dark
 * (values from the compiled web CSS). ARGB pairs, light first.
 */
val CATEGORY_COLOR: Map<String, Pair<Long, Long>> = mapOf(
    "ui" to (0xFF9D174D to 0xFFF9A8D4), // pink
    "api" to (0xFF1E40AF to 0xFF93C5FD), // blue
    "core" to (0xFF5B21B6 to 0xFFC4B5FD), // violet
    "data" to (0xFF92400E to 0xFFFCD34D), // amber
    "cli" to (0xFF3F6212 to 0xFFBEF264), // lime
    "security" to (0xFF991B1B to 0xFFFCA5A5), // red
    "tests" to (0xFF166534 to 0xFF86EFAC), // green
    "docs" to (0xFF075985 to 0xFF7DD3FC), // sky
    "examples" to (0xFF155E75 to 0xFF67E8F9), // cyan
    "deps" to (0xFF9A3412 to 0xFFFDBA74), // orange
    "build" to (0xFF854D0E to 0xFFFDE047), // yellow
    "scripts" to (0xFF065F46 to 0xFF6EE7B7), // emerald
    "config" to (0xFF115E59 to 0xFF5EEAD4), // teal
    "i18n" to (0xFF3730A3 to 0xFFA5B4FC), // indigo
    "assets" to (0xFF86198F to 0xFFF0ABFC), // fuchsia
    "other" to (0xFF262626 to 0xFFD4D4D4), // neutral
)

fun categoryIcon(category: String): String = CATEGORY_ICON[category] ?: CATEGORY_ICON.getValue("other")

fun categoryColor(category: String, dark: Boolean): Color = (CATEGORY_COLOR[category] ?: CATEGORY_COLOR.getValue("other")).let { Color(if (dark) it.second else it.first) }

/** `GroupCategoryIcon`: the category glyph in its hue; grayscale (`saturate-0`) when not active. */
@Composable
fun GroupCategoryIcon(category: String, modifier: Modifier = Modifier, saturated: Boolean = true, size: androidx.compose.ui.unit.Dp = 16.dp) {
    val c = LocalPullsColors.current
    val color = categoryColor(category, c.dark)
    PhIcon(categoryIcon(category), modifier, tint = if (saturated) color else grayscale(color), size = size, contentDescription = t("category.$category"))
}

private fun grayscale(color: Color): Color {
    val luma = 0.2126f * color.red + 0.7152f * color.green + 0.0722f * color.blue
    return Color(luma, luma, luma, color.alpha)
}

/** `group-utils.ts` `groupProgress`: share of a group's files (and its children's) reviewed; an empty group counts as done. */
fun groupProgress(group: FileGroup, files: Map<String, ReviewFile>, reviewed: Set<String>): Float {
    val list = group.allPaths.mapNotNull(files::get)
    if (list.isEmpty()) return 1f
    return list.count { it.sha in reviewed }.toFloat() / list.size
}

/** `countGroupStats`: a group's +/- with its children's. */
fun groupStats(group: FileGroup, files: Map<String, ReviewFile>): Pair<Int, Int> {
    val list = group.allPaths.mapNotNull(files::get)
    return list.sumOf { it.additions } to list.sumOf { it.deletions }
}

/** The label a group shows: its own, or "Uncategorized" for the trailing catch-all. */
@Composable
fun groupLabel(group: FileGroup): String = if (group.key == "uncategorized" && group.label.isBlank()) t("group.uncategorized") else group.label

/**
 * Port of `DiffGroupNav.vue`: a wrapping row of group chips; a group with subgroups has a
 * caret that shows its subgroups in a second row. Tapping a chip asks the diff body to
 * scroll there (`session.focusGroupKey`).
 */
@Composable
fun GroupNav(session: ReviewSession, visible: Set<String>, modifier: Modifier = Modifier) {
    val groups = session.groups
    if (groups.isEmpty()) return
    val c = LocalPullsColors.current
    val files = session.files.associateBy { it.path }
    var expandedKey by rememberSaveable { mutableStateOf<String?>(null) }
    val expanded = groups.firstOrNull { it.key == expandedKey }
    fun select(key: String) { session.focusGroupKey = key }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            groups.forEach { group ->
                GroupNavItem(
                    group, files, session.reviewed, active = group.key in visible, expanded = expandedKey == group.key,
                    onSelect = { select(group.key); if (group.children.isNotEmpty()) expandedKey = group.key },
                    onToggle = { expandedKey = if (expandedKey == group.key) null else group.key },
                )
            }
        }
        if (expanded != null && expanded.children.isNotEmpty()) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                PhIcon("folder-notch-open-duotone", tint = c.fade, size = 16.dp)
                Text(t("group.subgroupsOf") + " ", style = PullsType.sm, color = c.fade)
                Text(groupLabel(expanded), style = PullsType.sm, color = c.text)
                Box(Modifier.weight(1f).height(1.dp).background(c.border))
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                expanded.children.forEach { sub ->
                    GroupNavItem(sub.copy(children = emptyList()), files, session.reviewed, active = sub.key in visible, expanded = false, onSelect = { select(sub.key) }, onToggle = {})
                }
            }
        }
    }
}

/**
 * `DiffGroupNavItem.vue`: `border border-b-2 rounded-t` in the category hue, the bottom
 * border turning the hue when active; label row, then `+/-・n files・n subgroups`, the
 * review donut once any file is reviewed, and the subgroup caret.
 */
@Composable
private fun GroupNavItem(group: FileGroup, files: Map<String, ReviewFile>, reviewed: Set<String>, active: Boolean, expanded: Boolean, onSelect: () -> Unit, onToggle: () -> Unit) {
    val c = LocalPullsColors.current
    val hue = categoryColor(group.category, c.dark)
    val (added, deleted) = groupStats(group, files)
    val fileCount = group.allPaths.count { it in files }
    val subgroups = group.children.size
    val progress = groupProgress(group, files, reviewed)
    val shape = RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp)
    Row(
        Modifier
            .offset(y = if (active) (-1).dp else 0.dp)
            .clip(shape)
            .border(1.dp, c.border, shape)
            .drawBehind {
                val stroke = 2.dp.toPx()
                drawLine(if (active) hue else c.border, Offset(0f, size.height - stroke / 2), Offset(size.width, size.height - stroke / 2), stroke)
            }
            .clickable(role = Role.Button, onClick = onSelect)
            .defaultMinSize(minHeight = TOUCH)
            .padding(start = 8.dp, end = if (subgroups > 0) 0.dp else 8.dp, top = 2.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(Modifier.alpha(if (active) 1f else 0.65f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                GroupCategoryIcon(group.category, saturated = active)
                Text(groupLabel(group), style = PullsType.sm, color = c.text)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                DiffStats(added, deleted)
                Text("・" + tc("common.files", fileCount), style = PullsType.xs, color = c.text.copy(alpha = 0.5f))
                if (subgroups > 0) Text("・" + tc("common.subgroups", subgroups), style = PullsType.xs, color = c.text.copy(alpha = 0.5f))
            }
        }
        if (progress != 0f) Donut(progress, 18.dp, 2.dp, color = c.text)
        if (subgroups > 0) {
            IconButton(if (expanded) "caret-up" else "caret-down", t(if (expanded) "group.hideSubgroups" else "group.showSubgroups"), onToggle, Modifier.size(TOUCH), compact = true)
        }
    }
}
