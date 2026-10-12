package com.octaviantocan.pulls.ui.review

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.octaviantocan.pulls.R
import com.octaviantocan.pulls.ui.design.DiffStats
import com.octaviantocan.pulls.ui.design.FileIcon
import com.octaviantocan.pulls.ui.design.FileStatus
import com.octaviantocan.pulls.ui.design.LocalPullsColors
import com.octaviantocan.pulls.ui.design.PullsType
import com.octaviantocan.pulls.ui.design.ReviewCheckbox
import com.octaviantocan.pulls.ui.design.t

/**
 * Port of `FileTree.vue`: one group's files as a tree, single-child folder chains joined,
 * a review checkbox on every file and folder (folders show `partial`), paths the analysis
 * named that left the diff struck through as removed, the files on screen highlighted.
 * Phones cap it at 60% of the screen and scroll it on its own (`max-md:max-h-[60dvh]`).
 */
@Composable
fun FileTreeView(session: ReviewSession, rows: List<TreeRow>, visiblePaths: Set<String>, onNavigate: (String) -> Unit, modifier: Modifier = Modifier) {
    val maxHeight = (LocalConfiguration.current.screenHeightDp * 0.6f).dp
    if (rows.size <= 12) {
        // Short trees lay out inline: a nested lazy list needs a bounded height anyway.
        androidx.compose.foundation.layout.Column(modifier.fillMaxWidth()) { rows.forEach { TreeRowView(session, it, visiblePaths, onNavigate) } }
    } else {
        LazyColumn(modifier.fillMaxWidth().heightIn(max = maxHeight)) {
            items(rows, key = { it.key }) { row -> TreeRowView(session, row, visiblePaths, onNavigate) }
        }
    }
}

@Composable
private fun TreeRowView(session: ReviewSession, row: TreeRow, visiblePaths: Set<String>, onNavigate: (String) -> Unit) {
    val c = LocalPullsColors.current
    val file = row.file
    val active = file != null && file.path in visiblePaths
    // Rows are 44dp touch targets; indent `0.5em + depth * 0.6em` at 16sp.
    Row(
        Modifier
            .fillMaxWidth()
            .height(44.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(if (active) c.active else Color.Transparent)
            .padding(start = (8 + row.depth * 9.6).dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        when (row.type) {
            TreeRowType.Folder -> {
                ReviewCheckbox(
                    folderState(row.files, session.reviewed),
                    { value -> session.setReviewed(row.files.map { it.sha }, value) },
                    Modifier.width(28.dp),
                    label = t("file.markFolderReviewed", "name" to row.name),
                )
                FileIcon(R.drawable.ic_cat_folder, Modifier.alpha(0.65f))
                Text(row.name, style = PullsType.sm, color = c.fade, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            TreeRowType.File -> {
                val f = file ?: return@Row
                ReviewCheckbox(session.reviewState(f), { value -> session.setReviewed(listOf(f.sha), value) }, Modifier.width(28.dp), label = t("file.markReviewed"))
                Row(
                    Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(4.dp)).clickable(role = Role.Button) { onNavigate(f.path) }.padding(end = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    FilePath(row.name, Modifier.weight(1f), dim = false)
                    DiffStats(f.additions, f.deletions)
                    FileStatus(f.status)
                }
            }
            TreeRowType.Missing -> {
                Box(Modifier.width(28.dp))
                Row(Modifier.weight(1f).alpha(0.5f).padding(end = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilePath(row.name, Modifier.weight(1f), dim = false, strike = true)
                    Text(t("file.removed"), style = PullsType.xs, color = c.text)
                }
            }
        }
    }
}

/**
 * `DisplayFilePath`: a Catppuccin icon at `op-fade`, the directory at `op-mute` (when
 * [dim]) and the base name in the body color, monospace, truncated at the end.
 */
@Composable
fun FilePath(path: String, modifier: Modifier = Modifier, dim: Boolean = true, strike: Boolean = false, icon: Boolean = true, style: TextStyle = PullsType.sm, wrap: Boolean = false) {
    val c = LocalPullsColors.current
    val dir = path.substringBeforeLast('/', "").let { if (it.isEmpty()) "" else "$it/" }
    val base = path.substringAfterLast('/')
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        if (icon) FileIcon(fileIconRes(base), Modifier.alpha(0.65f))
        Text(
            buildAnnotatedString {
                if (dir.isNotEmpty()) withStyle(SpanStyle(color = if (dim) c.mute else c.text)) { append(dir) }
                withStyle(SpanStyle(color = c.text)) { append(base) }
            },
            style = style.copy(fontFamily = PullsType.mono, textDecoration = if (strike) TextDecoration.LineThrough else null),
            // `max-sm:whitespace-normal break-all` where asked: the file name at the end is never cut off.
            maxLines = if (wrap) Int.MAX_VALUE else 1,
            overflow = if (wrap) TextOverflow.Clip else TextOverflow.Ellipsis,
        )
    }
}

/** The Catppuccin icon `getFileIcon` picks for a file name (the vectors bundled in res/drawable). */
@DrawableRes
fun fileIconRes(path: String): Int {
    val name = path.substringAfterLast('/')
    return when {
        name == "package.json" -> R.drawable.ic_cat_npm
        name.endsWith(".vue") -> R.drawable.ic_cat_vue
        name.endsWith(".tsx") -> R.drawable.ic_cat_typescript_react
        name.endsWith(".jsx") -> R.drawable.ic_cat_javascript_react
        Regex("""\.d\.[cm]?ts$""").containsMatchIn(name) -> R.drawable.ic_cat_typescript_def
        Regex("""\.[cm]?ts$""").containsMatchIn(name) -> R.drawable.ic_cat_typescript
        Regex("""\.[cm]?js$""").containsMatchIn(name) -> R.drawable.ic_cat_javascript
        name.endsWith(".json") -> R.drawable.ic_cat_json
        name.endsWith(".yml") || name.endsWith(".yaml") -> R.drawable.ic_cat_yaml
        name.endsWith(".toml") -> R.drawable.ic_cat_toml
        name.endsWith(".md") -> R.drawable.ic_cat_markdown
        name.endsWith(".html") || name.endsWith(".htm") -> R.drawable.ic_cat_html
        name.endsWith(".css") -> R.drawable.ic_cat_css
        name.endsWith(".scss") || name.endsWith(".sass") -> R.drawable.ic_cat_sass
        name.endsWith(".svg") -> R.drawable.ic_cat_svg
        Regex("""\.(?:png|jpe?g|gif|webp|avif|ico)$""").containsMatchIn(name) -> R.drawable.ic_cat_image
        Regex("""\.(?:woff2?|ttf|otf|eot)$""").containsMatchIn(name) -> R.drawable.ic_cat_font
        else -> R.drawable.ic_cat_file
    }
}
