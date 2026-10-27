package com.octaviantocan.pulls.ui.review

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.octaviantocan.pulls.core.OpenTarget
import com.octaviantocan.pulls.ui.design.headerTopPadding
import com.octaviantocan.pulls.ui.design.readableWidth
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
 * the real page draws them, and bars where the rest will be. The header, the numbers line, the tabs and the bottom bar
 * have the page's own shapes and places, so when the page replaces it nothing moves; only words fill in (K22).
 */
@Composable
fun PrOpening(target: OpenTarget, preview: PullPreview?, onBack: () -> Unit, onAsk: (question: String) -> Unit, askDraft: MutableState<String> = remember { mutableStateOf("") }) {
    val c = LocalPullsColors.current
    val pull = target as? OpenTarget.Pull
    val known = pull?.let { LocalOverviewServices.current.sizes.get(it.owner, it.repo, it.number) }
    val adds = preview?.additions ?: known?.additions
    val dels = preview?.deletions ?: known?.deletions
    val files = known?.files ?: preview?.files
    Box(Modifier.fillMaxSize().background(c.base)) {
        Column(Modifier.fillMaxSize().readableWidth()) {
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = headerTopPadding()), verticalAlignment = Alignment.CenterVertically) {
                FloatIcon(Lu.back, "Back", onBack, iconSize = 22.dp)
                Spacer(Modifier.weight(1f))
                SectionCapsule(selected = 0, live = false, onOverview = {}, onChanges = null, onTour = null, onCorner = null, corner = CapsuleCorner.More)
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
                preview?.let { PrLifecycleStatus(it.state) }
                // The branches are in the Inbox row too, so they are there from the first frame like the title (J13).
                if (preview != null && (preview.base.isNotBlank() || preview.head.isNotBlank())) BranchRow(preview.base, preview.head)
                OpeningStats(adds, dels, files)
                UnderlineTabs(OVERVIEW_TABS, 0, {}, Modifier.padding(top = 2.dp))
                RowsSkeleton(Modifier.padding(top = 14.dp), rows = 5)
            }
        }
        // The page's own bottom row, the Ask field included, so a question can be asked before the rest arrives (N32).
        // The draft is the review's, so a question begun here is still in the bar when the Overview takes over.
        if (LocalOverviewServices.current.ai.canStartPaidWork) ReviewBar(askDraft.value, { askDraft.value = it }, onAsk, Modifier.align(Alignment.BottomCenter))
    }
}
