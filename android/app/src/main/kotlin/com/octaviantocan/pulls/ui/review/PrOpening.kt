package com.octaviantocan.pulls.ui.review

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.octaviantocan.pulls.core.OpenTarget
import com.octaviantocan.pulls.ui.design.FloatIcon
import com.octaviantocan.pulls.ui.design.LocalPullsColors
import com.octaviantocan.pulls.ui.design.Lu
import com.octaviantocan.pulls.ui.design.PullsType
import com.octaviantocan.pulls.ui.design.RowsSkeleton
import com.octaviantocan.pulls.ui.design.Skeleton
import com.octaviantocan.pulls.ui.design.UnderlineTabs
import com.octaviantocan.pulls.ui.home.StatusColors
import com.octaviantocan.pulls.ui.home.compactCount

/**
 * The overview while the pull request is still on its way: the title and number the phone already knows, drawn where
 * the real page draws them, and bars where the rest will be. Nothing here spins, and the page replaces it in place.
 */
@Composable
internal fun PrOpening(target: OpenTarget, preview: PullPreview?, onBack: () -> Unit) {
    val c = LocalPullsColors.current
    val pull = target as? OpenTarget.Pull
    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            FloatIcon(Lu.back, "Back", onBack, iconSize = 22.dp)
        }
        Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 12.dp)) {
            if (preview != null) {
                Text(preview.title, style = PullsType.xxl, color = c.text)
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(vertical = 4.dp)) {
                    Skeleton(Modifier.fillMaxWidth(0.92f).height(22.dp))
                    Skeleton(Modifier.fillMaxWidth(0.55f).height(22.dp))
                }
            }
            // The same line the page draws, so it keeps its shape when the pull request arrives: names stay whole and wrap.
            PrMetaLine(pull?.let { "#${it.number}" }.orEmpty(), preview?.author, null, pull?.owner, pull?.repo, onOpenRepo = null)
            if (preview?.additions != null && preview.deletions != null) {
                // The Inbox row already told the phone how big the change is.
                Row(Modifier.fillMaxWidth().padding(top = 18.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("+${compactCount(preview.additions)}", style = PullsType.sm.copy(fontWeight = FontWeight.Medium), color = StatusColors.green(c.dark), maxLines = 1)
                    Text("−${compactCount(preview.deletions)}", style = PullsType.sm.copy(fontWeight = FontWeight.Medium), color = StatusColors.red(c.dark), maxLines = 1)
                    preview.files?.let { Text("$it file${if (it == 1) "" else "s"}", style = PullsType.sm, color = c.muted, maxLines = 1) }
                }
            } else {
                Row(Modifier.fillMaxWidth().padding(top = 18.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Skeleton(Modifier.width(104.dp))
                    Skeleton(Modifier.width(76.dp))
                    Skeleton(Modifier.width(52.dp))
                }
            }
            UnderlineTabs(listOf("Summary", "Description", "Conversation"), 0, {}, Modifier.padding(top = 8.dp))
        }
        RowsSkeleton(Modifier.padding(top = 14.dp), rows = 5)
    }
}
