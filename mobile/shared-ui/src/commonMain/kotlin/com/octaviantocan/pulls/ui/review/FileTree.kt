package com.octaviantocan.pulls.ui.review

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.octaviantocan.pulls.ui.design.FileIcon
import com.octaviantocan.pulls.ui.design.LocalPullsColors
import com.octaviantocan.pulls.ui.design.PullsType

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
        if (icon) FileIcon(fileIconName(base), Modifier.alpha(0.65f))
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
fun fileIconName(path: String): String {
    val name = path.substringAfterLast('/')
    return when {
        name == "package.json" -> "ic_cat_npm"
        name.endsWith(".vue") -> "ic_cat_vue"
        name.endsWith(".tsx") -> "ic_cat_typescript_react"
        name.endsWith(".jsx") -> "ic_cat_javascript_react"
        Regex("""\.d\.[cm]?ts$""").containsMatchIn(name) -> "ic_cat_typescript_def"
        Regex("""\.[cm]?ts$""").containsMatchIn(name) -> "ic_cat_typescript"
        Regex("""\.[cm]?js$""").containsMatchIn(name) -> "ic_cat_javascript"
        name.endsWith(".json") -> "ic_cat_json"
        name.endsWith(".yml") || name.endsWith(".yaml") -> "ic_cat_yaml"
        name.endsWith(".toml") -> "ic_cat_toml"
        name.endsWith(".md") -> "ic_cat_markdown"
        name.endsWith(".html") || name.endsWith(".htm") -> "ic_cat_html"
        name.endsWith(".css") -> "ic_cat_css"
        name.endsWith(".scss") || name.endsWith(".sass") -> "ic_cat_sass"
        name.endsWith(".svg") -> "ic_cat_svg"
        Regex("""\.(?:png|jpe?g|gif|webp|avif|ico)$""").containsMatchIn(name) -> "ic_cat_image"
        Regex("""\.(?:woff2?|ttf|otf|eot)$""").containsMatchIn(name) -> "ic_cat_font"
        else -> "ic_cat_file"
    }
}
