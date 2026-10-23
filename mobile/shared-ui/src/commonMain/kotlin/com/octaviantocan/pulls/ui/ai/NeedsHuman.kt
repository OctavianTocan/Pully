package com.octaviantocan.pulls.ui.ai

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.octaviantocan.pulls.sound.cueClickable
import com.octaviantocan.pulls.ui.design.LocalPullsColors
import com.octaviantocan.pulls.ui.design.Lu
import com.octaviantocan.pulls.ui.design.LuIcon
import com.octaviantocan.pulls.ui.design.Orb
import com.octaviantocan.pulls.ui.design.PullsSpace
import com.octaviantocan.pulls.ui.design.PullsType
import com.octaviantocan.pulls.ui.design.TOUCH
import com.octaviantocan.pulls.ui.design.hairlineTop
import com.octaviantocan.pulls.ui.design.t
import com.octaviantocan.pulls.ui.review.Cite
import com.octaviantocan.pulls.ui.review.ReviewSession
import com.octaviantocan.pulls.ui.review.ShowMore
import com.octaviantocan.pulls.ui.review.appendCite
import com.octaviantocan.pulls.ui.review.firstFew
import com.octaviantocan.pulls.ui.review.firstLine
import com.octaviantocan.pulls.ui.review.guardedStop
import com.octaviantocan.pulls.ui.review.leadAndText

/**
 * `What needs a human`: what the AI review found, most serious first, each pointing at its line. The heading has no
 * line under it, like the others (rule 75): a hairline sits between rows only. Looking for problems is the orb with a
 * small square, which stops it (rule 87), and "Look for problems" opens the review setup.
 */
@Composable
fun NeedsHuman(session: ReviewSession, engineAvailable: Boolean, canStartPaidWork: Boolean, canCritique: Boolean, onCite: (Cite) -> Unit, onSheet: (String) -> Unit) {
    val c = LocalPullsColors.current
    if (session.key == null || !engineAvailable) return
    val critiqueTask = rememberLiveTask(session, "critique")
    val reviewing = critiqueTask?.running == true
    val findings = session.visibleFindings
    // Most serious first, nits last. The first few show, then "Show N more", which opens the rest in place (N5).
    val rank = mapOf("bug" to 0, "risk" to 1)
    val sorted = findings.sortedBy { rank[it.severity] ?: 2 }
    var all by rememberSaveable(session.key) { mutableStateOf(false) }
    val shown = sorted.take(firstFew(sorted.size, all))
    val critique = session.saved?.critique
    val mergeRisk = critique?.takeIf { session.settings.experiments.mergeRisk && it.headSha != null && it.headSha == session.opened?.headSha }?.mergeRisk
    if (findings.isEmpty() && !reviewing && !session.hasAiResult && critique == null) return
    // The first row has nothing above it; every later one has a hairline.
    fun line(index: Int): Modifier = if (index == 0) Modifier else Modifier.hairlineTop(c.borderMute)
    val beforeFindings = if (mergeRisk != null) 1 else 0
    val tail = beforeFindings + shown.size + if (findings.isNotEmpty()) 1 else 0
    val stop = guardedStop(session, listOf("critique"), critiqueTask, reviewing && canCritique)
    Column(Modifier.fillMaxWidth().padding(top = 14.dp)) {
        Text("What needs a human", Modifier.padding(bottom = 2.dp), style = PullsType.xl, color = c.text)
        mergeRisk?.let { risk ->
            Row(
                Modifier.fillMaxWidth().cueClickable(role = Role.Button) { onSheet("findings") }.padding(vertical = 11.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(PullsSpace.x2),
            ) {
                Text(
                    buildAnnotatedString {
                        append("${t("critique.mergeRisk.title")}: ")
                        withStyle(SpanStyle(color = if (risk.level == "high") c.amber else c.activeText, fontWeight = FontWeight.Medium)) {
                            append(t("critique.mergeRisk.level.${risk.level}"))
                        }
                        withStyle(SpanStyle(color = c.muted)) { append(" · ${t("settings.experiments.title")}") }
                    },
                    Modifier.weight(1f), style = PullsType.sm, color = c.activeText,
                )
                LuIcon(Lu.chev, tint = c.faint, size = 18.dp)
            }
        }
        shown.forEachIndexed { index, finding ->
            Text(
                buildAnnotatedString {
                    append(leadAndText(finding.title, firstLine(finding.body)))
                    append(" ")
                    appendCite("${finding.path.substringAfterLast('/')}:${finding.line}", c.activeText) { onCite(Cite(finding.path, finding.line)) }
                },
                Modifier.fillMaxWidth().then(line(index + beforeFindings)).cueClickable(role = Role.Button, onClickLabel = finding.title) { session.focusFinding(finding) }.padding(vertical = 11.dp),
                style = PullsType.sm.copy(lineHeight = PullsType.sm.fontSize * 1.45f), color = c.text,
            )
        }
        if (sorted.size > shown.size) ShowMore(sorted.size - shown.size) { all = true }
        if (findings.isNotEmpty()) {
            // The findings sheet is where findings are picked and posted as a review, so it is offered however long the list.
            Text(
                "Open the review", Modifier.fillMaxWidth().then(line(shown.size + beforeFindings)).cueClickable(role = Role.Button) { onSheet("findings") }.padding(vertical = 11.dp),
                style = PullsType.sm.copy(fontWeight = FontWeight.Medium), color = c.activeText,
            )
        }
        when {
            reviewing -> Row(
                Modifier.fillMaxWidth().then(line(tail)).padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Box(
                    Modifier.size(TOUCH).clip(CircleShape).then(
                        if (stop != null) Modifier.semantics { contentDescription = "Stop" }.cueClickable(role = Role.Button, onClickLabel = "Stop", onClick = stop) else Modifier,
                    ),
                    contentAlignment = Alignment.Center,
                ) { Orb(size = 22.dp, stop = stop != null) }
                Text("Looking for problems…", Modifier.weight(1f).shimmer(), style = PullsType.sm, color = c.muted)
            }
            findings.isEmpty() && session.saved?.critique != null -> Text(
                t("critique.noFindings"), Modifier.fillMaxWidth().then(line(tail)).padding(vertical = 11.dp), style = PullsType.sm, color = c.muted,
            )
            findings.isEmpty() && canStartPaidWork -> Row(
                Modifier.fillMaxWidth().then(line(tail)).cueClickable(role = Role.Button) { onSheet("review") }.padding(vertical = 11.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("Look for problems", Modifier.weight(1f), style = PullsType.sm.copy(fontWeight = FontWeight.Medium), color = c.activeText)
                LuIcon(Lu.chev, tint = c.faint, size = 18.dp)
            }
        }
    }
}
