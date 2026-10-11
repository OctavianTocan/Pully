package com.octaviantocan.pulls.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.size
import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import com.octaviantocan.pulls.R
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.octaviantocan.pulls.data.ReviewFile

private class Folder(val name: String) {
    val folders = linkedMapOf<String, Folder>()
    var file: ReviewFile? = null
}

/** A path tree like the web file tree: folders collapse when they have one child, files open the diff. */
@Composable
fun FileTree(files: List<ReviewFile>, reviewKey: String, onFile: (String) -> Unit) {
    val context = LocalContext.current
    val root = remember(files) { buildTree(files) }
    var open by remember(files) { mutableStateOf(setOf("")) }
    var reviewed by remember(reviewKey) { mutableStateOf(ReviewedMarks.load(context, reviewKey)) }
    Column {
        walk(root, "", 0, open, { key ->
            open = if (key in open) open - key else open + key
        }, reviewed, { sha ->
            ReviewedMarks.toggle(context, reviewKey, sha)
            reviewed = ReviewedMarks.load(context, reviewKey)
        }, onFile)
    }
}

@Composable
private fun walk(
    folder: Folder,
    path: String,
    depth: Int,
    open: Set<String>,
    toggle: (String) -> Unit,
    reviewed: Set<String>,
    onReviewed: (String) -> Unit,
    onFile: (String) -> Unit,
) {
    val pad = Modifier.padding(start = (12 + depth * 16).dp).heightIn(min = 48.dp).fillMaxWidth()
    folder.file?.let { file ->
        Row(pad, verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Checkbox(checked = file.sha.isNotEmpty() && file.sha in reviewed, onCheckedChange = { if (file.sha.isNotEmpty()) onReviewed(file.sha) })
            Image(painterResource(fileIcon(file.path)), contentDescription = null, modifier = Modifier.padding(end = 8.dp).size(16.dp))
            Text(
                "${file.path.substringAfterLast('/')}  +${file.additions} −${file.deletions}",
                Modifier.weight(1f).clickable { onFile(file.path) },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        return
    }
    if (path.isNotEmpty()) {
        val expanded = path in open
        Row(pad.clickable { toggle(path) }, verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Image(painterResource(if (expanded) R.drawable.ic_cat_folder_open else R.drawable.ic_cat_folder), contentDescription = null, modifier = Modifier.padding(end = 8.dp).size(16.dp))
            Text(folder.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (!expanded) return
    }
    folder.folders.values.forEach { child ->
        val childPath = if (path.isEmpty()) child.name else "$path/${child.name}"
        walk(child, childPath, if (path.isEmpty()) 0 else depth + 1, open, toggle, reviewed, onReviewed, onFile)
    }
}

private fun buildTree(files: List<ReviewFile>): Folder {
    val root = Folder("")
    for (file in files.sortedBy { it.path }) {
        val parts = file.path.split("/")
        var node = root
        parts.dropLast(1).forEach { part ->
            node = node.folders.getOrPut(part) { Folder(part) }
        }
        node.folders.getOrPut(parts.last()) { Folder(parts.last()) }.file = file
    }
    collapse(root)
    return root
}

/** A folder with one child folder is joined to it, matching the web tree. */
private fun collapse(folder: Folder) {
    val only = folder.folders.values.singleOrNull()
    if (only != null && only.file == null && folder.file == null && folder.name.isNotEmpty()) {
        folder.folders.clear()
        only.folders.forEach { (name, child) -> folder.folders[name] = child }
        folder.file = only.file
        collapse(folder)
        return
    }
    folder.folders.values.forEach(::collapse)
}

private fun fileIcon(path: String): Int {
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

private object ReviewedMarks {
    private const val PREFS = "pulls-reviewed"
    fun load(context: Context, key: String): Set<String> =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getStringSet(key, emptySet()) ?: emptySet()
    fun toggle(context: Context, key: String, sha: String) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val next = load(context, key).toMutableSet()
        if (!next.add(sha)) next.remove(sha)
        prefs.edit().putStringSet(key, next).apply()
    }
}
