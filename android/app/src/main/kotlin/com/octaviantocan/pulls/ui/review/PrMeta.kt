package com.octaviantocan.pulls.ui.review

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
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
 * author and repository are links. A name is never cut with an ellipsis (rule 2): when the parts do not fit one line,
 * the line breaks between them and each stays whole, and a name wider than the whole line wraps instead of being clipped. The page and the opening page draw it alike, so the line does not
 * change shape when the pull request arrives. A repository is only a link when [onOpenRepo] is given.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun PrMetaLine(label: String, author: String?, createdAt: String?, owner: String?, repo: String?, onOpenRepo: ((String, String) -> Unit)?) {
    val c = LocalPullsColors.current
    val uri = LocalUriHandler.current
    val sep = @Composable { Text("·", style = PullsType.sm, color = c.muted) }
    FlowRow(
        Modifier.fillMaxWidth().padding(top = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
        itemVerticalAlignment = Alignment.CenterVertically,
    ) {
        if (owner != null && repo != null) {
            Text(
                repo, if (onOpenRepo != null) Modifier.cueClickable(role = Role.Button, onClickLabel = "Open $repo") { onOpenRepo(owner, repo) } else Modifier,
                style = PullsType.sm, color = c.muted,
            )
        }
        Text(label, style = PullsType.num.copy(fontSize = PullsType.sm.fontSize), color = c.text, maxLines = 1, softWrap = false)
        author?.let { login ->
            sep()
            Text(
                login, Modifier.cueClickable(role = Role.Button, onClickLabel = "Open $login on GitHub") { uri.openUri("https://github.com/${login.removeSuffix("[bot]")}") },
                style = PullsType.sm, color = c.muted,
            )
        }
        createdAt?.let { iso ->
            val age = compactAge(prIsoMillis(iso), System.currentTimeMillis())
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
internal fun BranchRow(head: PrHead) {
    val c = LocalPullsColors.current
    val context = LocalContext.current
    val toaster = LocalToaster.current
    @Composable
    fun Chip(name: String, modifier: Modifier) {
        Row(
            modifier.clip(RoundedCornerShape(PullsRadius.md)).background(c.accentWash)
                .cueClickable(role = Role.Button, onClickLabel = "Copy $name") { copyText(context, name); toaster.show("Branch name copied", cue = Cue.Success) }
                .padding(horizontal = 8.dp, vertical = 3.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(name, style = PullsType.sm.copy(fontFamily = PullsFonts.display, fontWeight = FontWeight.Medium), color = c.accentWashInk, maxLines = 1, softWrap = false)
        }
    }
    // Always one line (H31): a long branch runs past the edge and the row scrolls sideways to read it, never wraps and
    // never cuts the name short.
    Row(
        Modifier.fillMaxWidth().padding(top = 10.dp).horizontalScroll(rememberScrollState()),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (head.head.isNotBlank()) Chip(head.head, Modifier)
        if (head.head.isNotBlank() && head.base.isNotBlank()) LuIcon(Lu.to, tint = c.muted, size = 14.dp)
        if (head.base.isNotBlank()) Chip(head.base, Modifier)
    }
}
