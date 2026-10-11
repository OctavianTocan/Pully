package com.octaviantocan.pulls.ui

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.octaviantocan.pulls.data.DiffLine

/** The web app forces unified below this width (`MIN_SPLIT_WIDTH_PX` in FileDiff.vue). */
internal val SPLIT_MIN_WIDTH = 640.dp

/** The web `diffs:layout` preference: one ambient choice for every diff, split by default. */
internal enum class DiffLayout {
    SPLIT, UNIFIED;

    companion object {
        private const val PREFS = "pulls-ui"

        fun load(context: Context): DiffLayout =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("layout", null)
                ?.let { runCatching { valueOf(it) }.getOrNull() } ?: SPLIT

        fun save(context: Context, layout: DiffLayout) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("layout", layout.name).apply()
        }
    }
}

/** One split row: indexes into the unified line list, `null` where that side is empty. */
internal data class SplitRow(val left: Int?, val right: Int?)

/**
 * Pairs a unified diff into two columns the way the web split view does: context on
 * both sides, each run of deletions paired line by line with the additions after it.
 */
internal fun splitRows(lines: List<DiffLine>): List<SplitRow> {
    val rows = mutableListOf<SplitRow>()
    var index = 0
    while (index < lines.size) {
        when (lines[index].kind) {
            ' ' -> {
                rows += SplitRow(index, index)
                index += 1
            }
            '-' -> {
                val deletions = mutableListOf<Int>()
                while (index < lines.size && lines[index].kind == '-') deletions += index++
                val additions = mutableListOf<Int>()
                while (index < lines.size && lines[index].kind == '+') additions += index++
                for (pair in 0 until maxOf(deletions.size, additions.size)) {
                    rows += SplitRow(deletions.getOrNull(pair), additions.getOrNull(pair))
                }
            }
            else -> {
                rows += SplitRow(null, index)
                index += 1
            }
        }
    }
    return rows
}

/** The web layout setting is a two-option segmented control (rows / columns). */
@Composable
internal fun LayoutSwitch(layout: DiffLayout, onChange: (DiffLayout) -> Unit) {
    val shape = RoundedCornerShape(8.dp)
    Row(Modifier.padding(horizontal = 12.dp, vertical = 6.dp).border(1.dp, MaterialTheme.colorScheme.outline, shape)) {
        listOf(DiffLayout.UNIFIED to "Unified", DiffLayout.SPLIT to "Split").forEach { (value, label) ->
            val active = value == layout
            TextButton(
                onClick = { onChange(value) },
                shape = shape,
                modifier = Modifier.heightIn(min = 44.dp)
                    .background(if (active) MaterialTheme.colorScheme.primaryContainer else Color.Transparent, shape),
            ) {
                Text(label, color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** Two columns with their own gutters; each side keeps the line's number, colors and comment button. */
@Composable
internal fun SplitLine(
    row: SplitRow,
    lines: List<DiffLine>,
    code: @Composable (Int) -> AnnotatedString,
    background: @Composable (Int) -> Color,
    commentable: Boolean,
    onComment: (Int) -> Unit,
    onToggle: (Int) -> Unit,
) {
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
        Side(row.left, lines, old = true, code, background, commentable, onComment, onToggle, Modifier.weight(1f))
        Box(Modifier.width(1.dp).fillMaxHeight().background(MaterialTheme.colorScheme.outline))
        Side(row.right, lines, old = false, code, background, commentable, onComment, onToggle, Modifier.weight(1f))
    }
}

/** Threads and drafts render once under the row, not once per column for a context line. */
@Composable
internal fun SplitExtras(row: SplitRow, extras: @Composable (Int) -> Unit) {
    row.left?.let { extras(it) }
    if (row.right != null && row.right != row.left) extras(row.right)
}

@Composable
private fun Side(
    index: Int?,
    lines: List<DiffLine>,
    old: Boolean,
    code: @Composable (Int) -> AnnotatedString,
    background: @Composable (Int) -> Color,
    commentable: Boolean,
    onComment: (Int) -> Unit,
    onToggle: (Int) -> Unit,
    modifier: Modifier,
) {
    if (index == null) {
        // The web view leaves the missing side as a muted block.
        Box(modifier.fillMaxHeight().background(MaterialTheme.colorScheme.surfaceVariant))
        return
    }
    val line = lines[index]
    val number = if (old) line.oldLine else line.newLine
    Row(modifier.fillMaxHeight().background(background(index)), verticalAlignment = Alignment.Top) {
        Text(
            (number?.toString() ?: "").padStart(4),
            Modifier.padding(start = 4.dp, top = 2.dp),
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (commentable && number != null) {
            TextButton(onClick = { onComment(index) }, modifier = Modifier.heightIn(min = 32.dp).width(40.dp)) { Text("+") }
        } else {
            Box(Modifier.width(40.dp))
        }
        Text(
            code(index),
            Modifier.weight(1f).horizontalScroll(rememberScrollState()).clickable { onToggle(index) }.padding(end = 6.dp, top = 2.dp, bottom = 2.dp),
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurface,
            softWrap = false,
        )
    }
}
