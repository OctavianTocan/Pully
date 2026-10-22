package com.octaviantocan.pulls.ui.review

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.octaviantocan.pulls.core.FileLinks
import com.octaviantocan.pulls.data.ReviewFile
import com.octaviantocan.pulls.sound.Cue
import com.octaviantocan.pulls.ui.design.CapsuleTabs
import com.octaviantocan.pulls.ui.design.CodeFont
import com.octaviantocan.pulls.ui.design.LocalToaster
import com.octaviantocan.pulls.ui.design.Lu
import com.octaviantocan.pulls.ui.design.LuIcon
import com.octaviantocan.pulls.ui.design.LocalPullsColors
import com.octaviantocan.pulls.ui.design.PullsSwitch
import com.octaviantocan.pulls.ui.design.PullsType
import com.octaviantocan.pulls.ui.design.SheetCard
import com.octaviantocan.pulls.ui.design.SheetRow
import com.octaviantocan.pulls.ui.home.GithubIcon
import com.octaviantocan.pulls.ui.home.NativeHomeActions
import com.octaviantocan.pulls.ui.settings.CodeSample
import com.octaviantocan.pulls.ui.settings.Group
import com.octaviantocan.pulls.ui.settings.LayoutChoices
import com.octaviantocan.pulls.ui.settings.ZOOMS

/**
 * What the Aa button in the capsule opens (rule 93), the first page of the pull request's sheet: the diff's own text and
 * tools. Each choice that changes how code looks shows the result in a sample under it (rule 81), the layout of the diff
 * is the first of them, and the actions of this file follow. [onActions] goes on to the pull request's actions, a page
 * further into the same sheet (J27), since the Diff's capsule has no corner left for ⋯.
 */
@Composable
internal fun ColumnScope.ToolsMain(session: ReviewSession, ui: DiffUi, top: ReviewTopUi, file: ReviewFile?, native: NativeHomeActions, onFont: () -> Unit, onClose: () -> Unit, onActions: () -> Unit) {
    val c = LocalPullsColors.current
    val actions = rememberReviewMutationActions(session, top)
    val read = file?.let { ui.captureFileRead(it) }
    fun fileAction(action: () -> Unit) = actions.read { if (file != null && ui.isCurrentFileRead(read, file)) action() }
    val toaster = LocalToaster.current
    val settings = session.settings
    val opened = session.opened
    val repo = FileLinks.repoOf(session.target)
    val sha = if (file?.status == "removed") opened?.baseSha else opened?.headSha
    val blob = if (file != null && repo != null && opened?.refKind.orEmpty().startsWith("github") && !sha.isNullOrBlank()) FileLinks.blob(repo.first, repo.second, sha, file.path) else null
    val zoomIndex = ZOOMS.indexOfFirst { kotlin.math.abs(settings.codeZoom - it.second) < 0.04f }.coerceAtLeast(0)

    Group("Layout") { LayoutChoices(settings, Modifier.padding(top = 10.dp)) { layout -> actions.read { settings.updateLayout(layout) } } }
    Group("Code") {
        SheetCard {
            Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(12.dp)) {
                CapsuleTabs(ZOOMS.map { it.first }, zoomIndex, { index -> actions.read { settings.updateCodeZoom(ZOOMS[index].second) } }, Modifier.fillMaxWidth())
                CodeSample(settings.codeZoom, wrap = settings.codeWrap)
            }
            SheetRow("Code font", onFont, iconless = true) {
                Text(CodeFont.of(settings.codeFont).label, style = PullsType.sm, color = c.muted, maxLines = 1)
                LuIcon(Lu.chev, tint = c.faint, size = 18.dp)
            }
            SheetRow("Wrap long lines", { actions.read { settings.updateCodeWrap(!settings.codeWrap) } }, iconless = true) {
                PullsSwitch(settings.codeWrap, { wrap -> actions.read { settings.updateCodeWrap(wrap) } }, "Wrap long lines")
            }
            if (session.reviewData != null) {
                SheetRow("Show review comments", { actions.read { settings.updateShowThreads(!settings.showThreads) } }, iconless = true) {
                    PullsSwitch(settings.showThreads, { show -> actions.read { settings.updateShowThreads(show) } }, "Show review comments")
                }
            }
        }
    }
    if (file != null) {
        Group(file.path.substringAfterLast('/')) {
            SheetCard {
                SheetRow("Copy path", { fileAction { native.copyText(file.path); toaster.show("Copied ${file.path.substringAfterLast('/')}", cue = Cue.Success); onClose() } }, first = true, icon = { LuIcon(Lu.copy, tint = c.text, size = 20.dp) })
                if (blob != null) {
                    SheetRow("Open on GitHub", { fileAction { native.openOnGithub(blob); onClose() } }, icon = { GithubIcon( tint = c.text, size = 20.dp) })
                }
                if (ui.canLoadFull(file)) {
                    SheetRow("Load the whole file", { fileAction { ui.loadFullFile(file); onClose() } }, icon = { LuIcon(Lu.exp, tint = c.text, size = 20.dp) })
                }
            }
        }
    }
    SheetCard(Modifier.padding(top = 12.dp)) {
        SheetRow("Pull request actions", onActions, first = true, icon = { LuIcon(Lu.more, tint = c.text, size = 20.dp) }) {
            LuIcon(Lu.chev, tint = c.faint, size = 18.dp)
        }
    }
}
