package com.octaviantocan.pulls.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import com.octaviantocan.pulls.R
import com.octaviantocan.pulls.data.PullRow
import com.octaviantocan.pulls.sound.Cue
import com.octaviantocan.pulls.ui.design.LocalPullsColors
import com.octaviantocan.pulls.ui.design.LocalToaster
import com.octaviantocan.pulls.ui.design.Lu
import com.octaviantocan.pulls.ui.design.LuIcon
import com.octaviantocan.pulls.ui.design.PullsSheet
import com.octaviantocan.pulls.ui.design.PullsType
import com.octaviantocan.pulls.ui.design.SheetCard
import com.octaviantocan.pulls.ui.design.SheetHeading
import com.octaviantocan.pulls.ui.design.SheetRow
import com.octaviantocan.pulls.ui.design.SheetTopGap
import com.octaviantocan.pulls.ui.design.DiffStats
import com.octaviantocan.pulls.ui.design.PrStatusIcon
import com.octaviantocan.pulls.ui.review.copyText

/**
 * What holding a pull request row opens (H17): the pull request itself at the top, then two groups under the sheet's
 * own headings, what Pully does with it (its tour, its repository, archiving it) and GitHub's (the page and its link).
 * The title used to sit under the number as loose text, further left than the cards and tight under the ✕, and every
 * action was one block ("this text is starting too far to the left ... I would suggest separating these into two
 * sections ... maybe archive can actually go into that one ... this is too bland": recording 16 Oct 13:06, 08:31 to
 * 08:58; O19). Each row does its thing and closes the sheet. The GitHub mark says where Open on GitHub goes; no
 * arrow-out icon is ever used for that (rule 100).
 */
@Composable
internal fun PullActionsSheet(
    pull: PullRow,
    archived: Boolean,
    onTour: () -> Unit,
    onRepo: () -> Unit,
    onArchive: (() -> Unit)?,
    onClose: () -> Unit,
) {
    val c = LocalPullsColors.current
    val uri = LocalUriHandler.current
    val context = LocalContext.current
    val toaster = LocalToaster.current
    val link = pull.url.ifBlank { "https://github.com/${pull.owner}/${pull.repo}/pull/${pull.number}" }
    fun then(action: () -> Unit): () -> Unit = { action(); onClose() }
    val state = when {
        pull.merged -> "merged"
        pull.state == "closed" -> "closed"
        pull.isDraft -> "draft"
        else -> "open"
    }
    PullsSheet(onClose, "#${pull.number}") {
        HeldItem(pull.title, "${pull.owner}/${pull.repo} · ${pull.author}", icon = { PrStatusIcon(state, size = 22.dp) }) {
            DiffStats(pull.additions, pull.deletions)
        }
        SheetHeading("Pully")
        SheetCard(gap = 6.dp) {
            SheetRow("Start a tour", then(onTour), first = true, icon = { LuIcon(Lu.tour, tint = c.text, size = 20.dp) })
            SheetRow("Open ${pull.repo}", then(onRepo), icon = { LuIcon(Lu.list, tint = c.text, size = 20.dp) })
            // Only his own pull requests can be archived (K20).
            if (onArchive != null) SheetRow(if (archived) "Restore" else "Archive", then(onArchive), icon = { LuIcon(Lu.arch, tint = c.text, size = 20.dp) })
        }
        SheetHeading("GitHub")
        SheetCard(gap = 6.dp) {
            SheetRow("Open on GitHub", then { uri.openUri(link) }, first = true, icon = { DrawableIcon(R.drawable.ic_carbon_logo_github, tint = c.text, size = 20.dp) })
            SheetRow("Copy link", then { copyText(context, link); toaster.show("Link copied", cue = Cue.Success) }, icon = { LuIcon(Lu.copy, tint = c.text, size = 20.dp) })
        }
    }
}

/**
 * The thing a hold sheet acts on, at its top as a card of its own: its icon, its title in full and a muted line under
 * it, [SheetTopGap] under the ✕ like every sheet's first field (O19).
 */
@Composable
internal fun HeldItem(title: String, detail: String, icon: @Composable () -> Unit, trailing: (@Composable () -> Unit)? = null) {
    val c = LocalPullsColors.current
    SheetCard(gap = SheetTopGap) {
        Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Box(Modifier.size(22.dp), contentAlignment = Alignment.Center) { icon() }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, style = PullsType.row.copy(fontWeight = FontWeight.Medium), color = c.text)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(detail, Modifier.weight(1f, fill = false), style = PullsType.sm, color = c.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    trailing?.invoke()
                }
            }
        }
    }
}

/**
 * What holding a tour row opens: the tour, and stopping it while it is written or taking it off the list once it is
 * done. Tours rows have no swipe for that any more, since a swipe right goes back to the Inbox (M4). It starts with the
 * pull request the tour is of, like the pull request's own hold sheet (O19).
 */
@Composable
internal fun TourActionsSheet(
    entry: TourEntry,
    stoppable: Boolean,
    onOpen: () -> Unit,
    onDismiss: () -> Unit,
    onClose: () -> Unit,
) {
    val c = LocalPullsColors.current
    fun then(action: () -> Unit): () -> Unit = { action(); onClose() }
    PullsSheet(onClose, "#${entry.number}") {
        HeldItem(entry.prTitle, "${entry.owner}/${entry.repo}", icon = { LuIcon(Lu.tour, tint = c.text, size = 20.dp) })
        SheetCard {
            SheetRow("Open the tour", then(onOpen), first = true, icon = { LuIcon(Lu.tour, tint = c.text, size = 20.dp) })
            SheetRow(if (stoppable) "Stop the tour" else "Remove from Tours", then(onDismiss), icon = { LuIcon(Lu.close, tint = c.text, size = 20.dp) })
        }
    }
}
