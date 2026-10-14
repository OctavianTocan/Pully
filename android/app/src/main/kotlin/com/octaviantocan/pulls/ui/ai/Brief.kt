package com.octaviantocan.pulls.ui.ai

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.octaviantocan.pulls.core.BriefBlock
import com.octaviantocan.pulls.core.BriefTarget
import com.octaviantocan.pulls.core.CalloutTone
import com.octaviantocan.pulls.core.FindingSeverity
import com.octaviantocan.pulls.core.markdown
import com.octaviantocan.pulls.data.Brief
import com.octaviantocan.pulls.data.TourBlock
import com.octaviantocan.pulls.ui.design.ActionButton
import com.octaviantocan.pulls.ui.design.ButtonVariant
import com.octaviantocan.pulls.ui.design.LocalPullsColors
import com.octaviantocan.pulls.ui.design.Lu
import com.octaviantocan.pulls.ui.design.LuIcon
import com.octaviantocan.pulls.ui.design.Markdown
import com.octaviantocan.pulls.ui.design.PendingDot
import com.octaviantocan.pulls.ui.design.PullsType
import com.octaviantocan.pulls.ui.home.StatusColors
import com.octaviantocan.pulls.ui.review.ReviewSession
import kotlinx.serialization.json.put

/*
 * The brief: the page an agent designed for one pull request. The server parses what the agent wrote
 * (OpenUI Lang) and the core module turns the tree into typed blocks (`core/Brief.kt`); this draws each
 * block with the app's own tokens. There is no WebView and no generated code: a block the agent can ask
 * for is a case below, and one it cannot ask for does not exist. Anything that points at the code opens
 * the diff there, through [onOpen].
 */

/** Designs a brief in the reader's language, unless one is already being written. */
fun startBrief(session: ReviewSession) {
    if (session.taskFor("brief")?.running == true) return
    session.startTask("brief", { put("locale", session.settings.locale) })
}

/** The brief under its heading. A page written for an earlier commit says so. */
@Composable
internal fun BriefSection(session: ReviewSession, brief: Brief, onOpen: (BriefTarget) -> Unit, modifier: Modifier = Modifier) {
    val c = LocalPullsColors.current
    val running = session.taskFor("brief")?.running == true
    val current = session.opened?.headSha
    val older = brief.headSha != null && current != null && brief.headSha != current
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Brief", Modifier.weight(1f), style = PullsType.xl, color = c.text)
            if (session.key != null && !running) ActionButton({ startBrief(session) }, variant = ButtonVariant.Text, small = true) { Text("Redo") }
        }
        if (older) Text("Written for an earlier commit.", style = PullsType.sm, color = c.muted)
        brief.page.blocks.forEach { block -> BriefBlockView(session, block, onOpen) }
    }
}

@Composable
private fun BriefBlockView(session: ReviewSession, block: BriefBlock, onOpen: (BriefTarget) -> Unit) {
    val c = LocalPullsColors.current
    when (block) {
        is BriefBlock.Text -> Markdown(block.markdown, style = PullsType.read.copy(color = c.text))
        is BriefBlock.Callout -> CalloutView(block)
        is BriefBlock.KeyValues -> Column(Modifier.fillMaxWidth()) {
            block.rows.forEachIndexed { index, (label, value) ->
                Row(
                    Modifier.fillMaxWidth().then(if (index > 0) Modifier.hairline(c.borderMute) else Modifier).padding(vertical = 9.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(label, Modifier.weight(0.4f), style = PullsType.sm, color = c.muted)
                    Text(value, Modifier.weight(0.6f), style = PullsType.sm, color = c.text)
                }
            }
        }
        // The Markdown view already draws a table, scrolling sideways when it is wide.
        is BriefBlock.Table -> Markdown(block.markdown(), style = PullsType.sm.copy(color = c.text))
        is BriefBlock.Checklist -> Column(Modifier.fillMaxWidth()) {
            block.items.forEachIndexed { index, item ->
                Row(
                    Modifier.fillMaxWidth().then(if (index > 0) Modifier.hairline(c.borderMute) else Modifier).padding(vertical = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    PendingDot(Modifier.padding(top = 5.dp))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(item.text, style = PullsType.sm, color = c.text)
                        item.target?.let { TargetLink(it, onOpen) }
                    }
                }
            }
        }
        is BriefBlock.Steps -> Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            block.items.forEachIndexed { index, step ->
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("${index + 1}", Modifier.width(18.dp), style = PullsType.sm.copy(fontFamily = PullsType.mono), color = c.muted)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(step.title, style = PullsType.sm.copy(fontWeight = FontWeight.Medium), color = c.text)
                        if (step.text.isNotBlank()) Markdown(step.text, style = PullsType.sm.copy(color = c.muted))
                    }
                }
            }
        }
        is BriefBlock.FileList -> Column(Modifier.fillMaxWidth()) {
            block.files.forEachIndexed { index, file ->
                Column(Modifier.fillMaxWidth().then(if (index > 0) Modifier.hairline(c.borderMute) else Modifier).padding(vertical = 2.dp)) {
                    TargetLink(file.target, onOpen)
                    if (file.note.isNotBlank()) Text(file.note, Modifier.padding(bottom = 8.dp), style = PullsType.sm, color = c.muted)
                }
            }
        }
        is BriefBlock.Diagram -> TourBlockView(session, TourBlock("diagram", block.caption.orEmpty(), body = block.mermaid))
        is BriefBlock.Code -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            block.caption?.let { Text(it, style = PullsType.xs, color = c.fade) }
            CodeWell(session, block.code)
        }
        is BriefBlock.BeforeAfter -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            block.title?.let { Text(it, style = PullsType.sm.copy(fontWeight = FontWeight.Medium), color = c.text) }
            Text("Before", style = PullsType.micro.copy(fontWeight = FontWeight.SemiBold), color = c.muted)
            CodeWell(session, block.before)
            Text("After", style = PullsType.micro.copy(fontWeight = FontWeight.SemiBold), color = c.accentTeal)
            CodeWell(session, block.after)
        }
        is BriefBlock.Finding -> FindingView(block, onOpen)
        is BriefBlock.Section -> SectionView(session, block, onOpen)
    }
}

/** `Callout`: a tinted note with a bar on its left, coloured by how much it matters. No icon, the colour and the title say it. */
@Composable
private fun CalloutView(block: BriefBlock.Callout) {
    val c = LocalPullsColors.current
    val tone = when (block.tone) {
        CalloutTone.Risk -> StatusColors.red(c.dark)
        CalloutTone.Warning -> StatusColors.amberText(c.dark)
        CalloutTone.Good -> StatusColors.green(c.dark)
        CalloutTone.Info -> c.activeText
    }
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(tone.copy(alpha = 0.08f))
            .drawBehind { drawRect(tone, Offset.Zero, Size(3.dp.toPx(), size.height)) }
            .padding(start = 15.dp, end = 12.dp, top = 10.dp, bottom = 10.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        block.title?.let { Text(it, style = PullsType.sm.copy(fontWeight = FontWeight.SemiBold), color = tone) }
        Markdown(block.text, style = PullsType.sm.copy(color = c.text))
    }
}

@Composable
private fun FindingView(block: BriefBlock.Finding, onOpen: (BriefTarget) -> Unit) {
    val c = LocalPullsColors.current
    val (label, tint) = when (block.severity) {
        FindingSeverity.Bug -> "Bug" to StatusColors.red(c.dark)
        FindingSeverity.Risk -> "Risk" to StatusColors.amberText(c.dark)
        FindingSeverity.Nit -> "Nit" to c.muted
    }
    Column(Modifier.fillMaxWidth().hairline(c.borderMute).padding(top = 10.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(label, style = PullsType.micro.copy(fontWeight = FontWeight.SemiBold), color = tint)
            Text(block.title, Modifier.weight(1f), style = PullsType.sm.copy(fontWeight = FontWeight.Medium), color = c.text)
        }
        if (block.text.isNotBlank()) Markdown(block.text, style = PullsType.sm.copy(color = c.text))
        block.target?.let { TargetLink(it, onOpen) }
    }
}

/** A titled group the reader can fold; the agent says whether it starts open. */
@Composable
private fun SectionView(session: ReviewSession, block: BriefBlock.Section, onOpen: (BriefTarget) -> Unit) {
    val c = LocalPullsColors.current
    var open by rememberSaveable(block.title) { mutableStateOf(block.open) }
    Column(Modifier.fillMaxWidth().animateContentSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(
            Modifier.fillMaxWidth().hairline(c.borderMute).defaultMinSize(minHeight = 44.dp)
                .clickable(role = Role.Button, onClickLabel = if (open) "Fold ${block.title}" else "Unfold ${block.title}") { open = !open },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(block.title, Modifier.weight(1f), style = PullsType.base.copy(fontWeight = FontWeight.Medium), color = c.text)
            LuIcon(Lu.chev, Modifier.rotate(if (open) 90f else 0f), tint = c.faint, size = 18.dp)
        }
        if (open) block.blocks.forEach { inner -> BriefBlockView(session, inner, onOpen) }
    }
}

/** `file.ts:42`, underlined, opening the diff at that line. */
@Composable
private fun TargetLink(target: BriefTarget, onOpen: (BriefTarget) -> Unit) {
    val c = LocalPullsColors.current
    Text(
        target.path.substringAfterLast('/') + (target.line?.let { ":$it" } ?: ""),
        Modifier.defaultMinSize(minHeight = 36.dp).clickable(role = Role.Button, onClickLabel = "Open ${target.path}") { onOpen(target) }.padding(vertical = 8.dp),
        style = PullsType.sm.copy(fontFamily = PullsType.display, fontWeight = FontWeight.Medium, textDecoration = TextDecoration.Underline),
        color = c.activeText, maxLines = 1,
    )
}

/** Code the agent quoted, in the diff's own size setting, scrolling sideways instead of wrapping. */
@Composable
private fun CodeWell(session: ReviewSession, code: String) {
    val c = LocalPullsColors.current
    val zoom = session.settings.codeZoom
    Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(c.paper).horizontalScroll(rememberScrollState()).padding(12.dp)) {
        Text(code.trimEnd(), style = PullsType.xs.copy(fontFamily = PullsType.mono, fontSize = (12f * zoom).sp, lineHeight = (18f * zoom).sp), color = c.text, softWrap = false)
    }
}

private fun Modifier.hairline(color: Color): Modifier = drawBehind { drawRect(color, Offset.Zero, Size(size.width, 1.dp.toPx())) }
