package com.octaviantocan.pulls.ui.review

import com.octaviantocan.pulls.ui.design.sideScrollFade
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.octaviantocan.pulls.sound.Cue
import com.octaviantocan.pulls.sound.cueClickable
import com.octaviantocan.pulls.ui.design.LocalPullsColors
import com.octaviantocan.pulls.ui.design.LocalToaster
import com.octaviantocan.pulls.ui.design.Lu
import com.octaviantocan.pulls.ui.design.LuIcon
import com.octaviantocan.pulls.ui.design.PullsFonts
import com.octaviantocan.pulls.ui.design.PullsRadius
import com.octaviantocan.pulls.ui.design.PullsType
import com.octaviantocan.pulls.ui.home.compactAge

/**
 * `.meta`: `dayflow #241 · author · 6h ago`, the repository and number first and the age last (rule 26, Row W). The
 * repository is a link, the number opens the canonical PR in the browser and the author is plain text (L21). Names yield at their ends so the metadata
 * stays on one line (rule 26), keeping the number and age visible on both opening and loaded pages.
 * A repository is only a link when [onOpenRepo] is given.
 */
@Composable
fun PrMetaLine(label: String, author: String?, createdAt: String?, owner: String?, repo: String?, onOpenRepo: ((String, String) -> Unit)?) {
    val c = LocalPullsColors.current
    val browser = LocalOverviewServices.current.native::openInBrowser
    val githubUrl = canonicalPrUrl(label, owner, repo)
    val sep = @Composable { Text("·", style = PullsType.sm, color = c.muted) }
    Row(
        Modifier.fillMaxWidth().padding(top = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (owner != null && repo != null) {
            Text(
                repo, Modifier.weight(1f, fill = false).then(if (onOpenRepo != null) Modifier.cueClickable(role = Role.Button, onClickLabel = "Open $repo") { onOpenRepo(owner, repo) } else Modifier),
                style = PullsType.sm, color = c.muted, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis,
            )
        }
        Text(
            label, Modifier.then(if (githubUrl != null) Modifier.cueClickable(role = Role.Button, onClickLabel = "Open $label on GitHub") { browser(githubUrl) } else Modifier),
            style = PullsType.num.copy(fontSize = PullsType.sm.fontSize), color = c.text, maxLines = 1, softWrap = false,
        )
        author?.let { login ->
            sep()
            // The author is a plain name: a tap that left for their GitHub profile surprised (recording 15 Oct 15:37,
            // 11:17 to 11:25; L21). The repository's name before it still opens the repository.
            Text(login, Modifier.weight(1f, fill = false), style = PullsType.sm, color = c.muted, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
        }
        createdAt?.let { iso ->
            val age = compactAge(prIsoMillis(iso), kotlin.time.Clock.System.now().toEpochMilliseconds())
            if (age.isNotEmpty()) {
                sep()
                Text(if (age == "now") "just now" else "$age ago", style = PullsType.sm, color = c.muted, maxLines = 1, softWrap = false)
            }
        }
    }
}

/**
 * `.brow`: the head branch, an arrow, the base branch. A tap copies the name and a small toast says so; the chip goes
 * on showing the branch, because a control that copies keeps its face (rule 83).
 */
@Composable
fun BranchRow(base: String, head: String) {
    val c = LocalPullsColors.current
    val native = LocalOverviewServices.current.native
    val toaster = LocalToaster.current
    @Composable
    fun Chip(name: String, modifier: Modifier) {
        Row(
            modifier.clip(RoundedCornerShape(PullsRadius.md)).background(c.accentWash)
                .cueClickable(role = Role.Button, onClickLabel = "Copy $name") { native.copyText(name); toaster.show("Branch name copied", cue = Cue.Success) }
                .padding(horizontal = 8.dp, vertical = 3.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(name, style = PullsType.sm.copy(fontFamily = PullsFonts.display, fontWeight = FontWeight.Medium), color = c.accentWashInk, maxLines = 1, softWrap = false)
        }
    }
    // Always one line (H31): a long branch runs past the edge and the row scrolls sideways to read it, never wraps and
    // never cuts the name short. Where it runs past an edge it dissolves into the page (J14).
    val scroll = rememberScrollState()
    Row(
        Modifier.fillMaxWidth().padding(top = 10.dp).sideScrollFade(scroll).horizontalScroll(scroll),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (head.isNotBlank()) Chip(head, Modifier)
        if (head.isNotBlank() && base.isNotBlank()) LuIcon(Lu.to, tint = c.muted, size = 14.dp)
        if (base.isNotBlank()) Chip(base, Modifier)
    }
}
