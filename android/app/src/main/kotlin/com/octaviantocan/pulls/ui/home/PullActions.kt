package com.octaviantocan.pulls.ui.home

import androidx.compose.material3.Text
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
import com.octaviantocan.pulls.ui.design.SheetRow
import com.octaviantocan.pulls.ui.review.copyText

/**
 * What holding a pull request row opens (H17): the pull request on GitHub, its link copied, its tour, its repository,
 * and archiving it. Each row does its thing and closes the sheet. The GitHub mark says where the first one goes; no
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
    PullsSheet(onClose, "#${pull.number}") {
        Text(pull.title, style = PullsType.row, color = c.muted, maxLines = 2)
        SheetCard {
            SheetRow("Open on GitHub", then { uri.openUri(link) }, first = true, icon = { DrawableIcon(R.drawable.ic_carbon_logo_github, tint = c.text, size = 20.dp) })
            SheetRow("Copy link", then { copyText(context, link); toaster.show("Link copied", cue = Cue.Success) }, icon = { LuIcon(Lu.copy, tint = c.text, size = 20.dp) })
            SheetRow("Start a tour", then(onTour), icon = { LuIcon(Lu.tour, tint = c.text, size = 20.dp) })
            SheetRow("Open ${pull.repo}", then(onRepo), icon = { LuIcon(Lu.list, tint = c.text, size = 20.dp) })
        }
        // Only his own pull requests can be archived (K20).
        if (onArchive != null) SheetCard {
            SheetRow(if (archived) "Restore" else "Archive", then(onArchive), first = true, icon = { LuIcon(Lu.arch, tint = c.text, size = 20.dp) })
        }
    }
}
