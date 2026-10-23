package com.octaviantocan.pulls.ui.review

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.octaviantocan.pulls.data.CritiqueFinding
import com.octaviantocan.pulls.sound.cueClickable
import com.octaviantocan.pulls.ui.ai.AskThreadCard
import com.octaviantocan.pulls.ui.ai.LocalAiCapabilities
import com.octaviantocan.pulls.ui.design.LocalPullsColors
import com.octaviantocan.pulls.ui.design.PullsRadius
import com.octaviantocan.pulls.ui.design.PullsShadow
import com.octaviantocan.pulls.ui.design.PullsType
import com.octaviantocan.pulls.ui.design.floatShadow

/** A finding pinned under its line of the diff, as the kit's white card (rule 49): severity, title, body, suggestion; a tap jumps to it. The one in focus sits on the active fill. */
@Composable
fun InlineFinding(session: ReviewSession, finding: CritiqueFinding, highlighted: Boolean) {
    if (!LocalAiCapabilities.current.canReadResults) return
    val c = LocalPullsColors.current
    val focus = rememberFindingFocusAction(session, finding)
    val shape = RoundedCornerShape(PullsRadius.card)
    Column(
        Modifier.padding(vertical = 4.dp).widthIn(max = ReviewCardMaxWidth).fillMaxWidth().floatShadow(shape, PullsShadow.Note).clip(shape)
            .background(if (highlighted) c.active else c.raised)
            .cueClickable(role = Role.Button, onClick = focus::open)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        com.octaviantocan.pulls.ui.ai.FindingHeading(finding.severity, finding.title)
        if (finding.body.isNotBlank()) com.octaviantocan.pulls.ui.ai.AiMarkdown(session, finding.body, style = PullsType.sm.copy(color = c.text))
        finding.suggestion?.let { suggestion -> com.octaviantocan.pulls.ui.ai.FindingSuggestion(session, finding.path, suggestion) }
    }
}

/** `AiAnnotations`: hunk notes (plain muted text, no icon), findings, ask threads. */
@Composable
fun AiAnnotationBlock(session: ReviewSession, anchor: AiAnchor, highlightedFinding: String?) {
    if (!LocalAiCapabilities.current.canReadResults) return
    val c = LocalPullsColors.current
    anchor.notes.forEach { note ->
        com.octaviantocan.pulls.ui.ai.AiMarkdown(session, note, Modifier.padding(horizontal = 16.dp, vertical = 6.dp).widthIn(max = ReviewCardMaxWidth), style = PullsType.sm.copy(color = c.muted))
    }
    anchor.findings.forEach { InlineFinding(session, it, highlighted = it.id == highlightedFinding) }
    anchor.asks.forEach { AskThreadCard(session, it, Modifier.padding(vertical = 4.dp).widthIn(max = ReviewCardMaxWidth)) }
}

/** The actual finding click keeps the full view it was drawn in, even if its callback is retained. */
internal class FindingFocusAction(
    private val session: ReviewSession,
    private val finding: CritiqueFinding,
    private val canRead: () -> Boolean,
) {
    private val owner = session.captureView()
    private var closed = false
    fun close() { closed = true }
    fun open() {
        if (!closed && canRead() && owner?.let(session::isCurrentView) == true) session.focusFinding(finding)
    }
}

@Composable
internal fun rememberFindingFocusAction(session: ReviewSession, finding: CritiqueFinding): FindingFocusAction {
    val owner = session.captureView()
    val capabilities by rememberUpdatedState(LocalAiCapabilities.current)
    val readiness = LocalReviewActionReadiness.current
    val action = remember(session, owner, finding, readiness) { FindingFocusAction(session, finding) { readiness.ai(capabilities).canReadResults } }
    DisposableEffect(action) { onDispose { action.close() } }
    return action
}
