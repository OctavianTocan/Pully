package com.octaviantocan.pulls.ui.personal

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import com.octaviantocan.pulls.ui.design.LocalPullsColors
import com.octaviantocan.pulls.ui.design.PillButton
import com.octaviantocan.pulls.ui.design.PullsSheet
import com.octaviantocan.pulls.ui.design.PullsSpace
import com.octaviantocan.pulls.ui.design.PullsType
import com.octaviantocan.pulls.ui.design.t

@Composable
fun GitHubAccountEntrySheet(entry: GitHubAccountEntry) {
    val opening = entry.explanation ?: return
    PullsSheet({ entry.dismiss(opening) }, t("personal.title"), expandable = false) {
        Column(verticalArrangement = Arrangement.spacedBy(PullsSpace.x3)) {
            GitHubAccountUnavailableText(opening.reason)
            PillButton(t("explore.browse"), { entry.dismiss(opening) })
        }
    }
}

@Composable
internal fun GitHubAccountUnavailableText(reason: GitHubAccountEntry.Unavailable) {
    Text(t(if (reason == GitHubAccountEntry.Unavailable.Configuration) "personal.notConfigured" else "personal.deviceUnavailable"),
        style = PullsType.read, color = LocalPullsColors.current.muted)
}
