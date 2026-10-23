package com.octaviantocan.pulls.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.octaviantocan.pulls.data.PostedRow
import com.octaviantocan.pulls.ui.design.LocalPullsColors
import com.octaviantocan.pulls.ui.design.Lu
import com.octaviantocan.pulls.ui.design.LuIcon
import com.octaviantocan.pulls.ui.design.PullsSheet
import com.octaviantocan.pulls.ui.design.PullsType
import com.octaviantocan.pulls.ui.design.SheetCard
import com.octaviantocan.pulls.ui.design.SheetRow
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** The heading key of the diffs agents posted: one group of its own in the Inbox, never mixed into a repository's. */
const val POSTED_SECTION = "posted:agents"

/** A posted diff's row key in the Inbox's list. */
fun postedKey(row: PostedRow): String = "posted:${row.id}"

/** The posted diffs a search finds: by title or by who posted them. */
fun List<PostedRow>.matching(needle: String): List<PostedRow> =
    if (needle.isEmpty()) this else filter { "${it.title} ${it.author}".lowercase().contains(needle) }

/** When a posted diff last changed, in ms; null when the server sent no time it can read. */
fun PostedRow.updatedAtMs(): Long? = runCatching { kotlin.time.Instant.parse(updatedAt).toEpochMilliseconds() }.getOrNull()

/**
 * A posted diff's row: the row every pull request has (rule 75), led by a tile in the group's own magenta with the
 * terminal mark, since an agent made it. Its meta line says who posted it, then the + and − and the age in the same
 * columns as the rows under them. No number, no checks and no chips: a bare diff has none of them (Tavi, 16 Oct 12:47).
 */
@Composable
fun PostedRowContent(row: PostedRow, now: Long) {
    val c = LocalPullsColors.current
    PullRowFrame(
        AnnotatedString(row.title),
        tile = {
            RowTile(c.accentMagenta.copy(alpha = if (c.dark) 0.18f else 0.12f), Modifier.semantics { contentDescription = "Posted by an agent" }) {
                LuIcon(Lu.terminal, tint = c.accentMagenta, size = TILE_MARK)
            }
        },
    ) {
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(row.author, Modifier.weight(1f, fill = false), style = PullsType.sm, color = c.muted, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
        }
        RowFacts({}, row.additions, row.deletions, compactAge(row.updatedAtMs(), now))
    }
}

/** What a held posted diff offers: open it, or delete it for good, link and all. */
@Composable
fun PostedActionsSheet(row: PostedRow, onOpen: () -> Unit, onDelete: (() -> Unit)?, onClose: () -> Unit) {
    val c = LocalPullsColors.current
    PullsSheet(onClose = onClose, title = row.title) {
        SheetCard(Modifier.fillMaxWidth()) {
            SheetRow("Open", { onClose(); onOpen() }, first = true, icon = { LuIcon(Lu.diff, tint = c.muted, size = 20.dp) })
            if (onDelete != null) SheetRow("Delete", { onClose(); onDelete() }, danger = true, icon = { LuIcon(Lu.trash, tint = c.error, size = 20.dp) })
        }
    }
}
