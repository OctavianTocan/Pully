package com.octaviantocan.pulls.ui.review

import androidx.compose.ui.layout.layout
import com.octaviantocan.pulls.ui.home.SearchDim
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.saveable.rememberSaveable
import com.octaviantocan.pulls.data.JEV_FILE_WORDS
import com.octaviantocan.pulls.data.JEV_RISK_WORDS
import com.octaviantocan.pulls.data.PullJev
import com.octaviantocan.pulls.data.kindWords
import com.octaviantocan.pulls.data.riskLevel
import com.octaviantocan.pulls.data.riskWords
import androidx.compose.foundation.gestures.scrollable
import androidx.compose.foundation.layout.Spacer
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import com.octaviantocan.pulls.ui.design.SidePages
import com.octaviantocan.pulls.ui.design.t
import com.octaviantocan.pulls.ui.design.floatingBar
import com.octaviantocan.pulls.ui.design.rememberSidePages
import com.octaviantocan.pulls.ui.design.sideSwipe
import kotlin.math.roundToInt
import com.octaviantocan.pulls.core.OpenTarget
import com.octaviantocan.pulls.data.PullSizes
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.octaviantocan.pulls.core.showsDescription
import com.octaviantocan.pulls.core.summaryKey
import com.octaviantocan.pulls.data.ReviewFile
import com.octaviantocan.pulls.sound.Cue
import com.octaviantocan.pulls.sound.cueClickable
import com.octaviantocan.pulls.ui.ai.BriefSection
import com.octaviantocan.pulls.ui.ai.ProvideAiText
import com.octaviantocan.pulls.ui.ai.SharedAnalysisBanner
import com.octaviantocan.pulls.ui.ai.cliEngine
import com.octaviantocan.pulls.ui.ai.rememberLiveTask
import com.octaviantocan.pulls.ui.ai.shimmer
import com.octaviantocan.pulls.ui.design.BlurSwitch
import com.octaviantocan.pulls.ui.design.BreathingDot
import com.octaviantocan.pulls.ui.design.CapsuleTabs
import com.octaviantocan.pulls.ui.design.CodePlace
import com.octaviantocan.pulls.ui.design.FloatIcon
import com.octaviantocan.pulls.ui.design.IconSwap
import com.octaviantocan.pulls.ui.design.LocalPullsColors
import com.octaviantocan.pulls.ui.design.Lu
import com.octaviantocan.pulls.ui.design.LuIcon
import com.octaviantocan.pulls.ui.design.Markdown
import com.octaviantocan.pulls.ui.design.Orb
import com.octaviantocan.pulls.ui.design.PullsCheck
import com.octaviantocan.pulls.ui.design.PullsMotion
import com.octaviantocan.pulls.ui.design.PullsRadius
import com.octaviantocan.pulls.ui.design.PullsSpace
import com.octaviantocan.pulls.ui.design.PullsType
import com.octaviantocan.pulls.ui.design.ReviewState
import com.octaviantocan.pulls.ui.design.RowsSkeleton
import com.octaviantocan.pulls.ui.design.Skeleton
import com.octaviantocan.pulls.ui.design.TOUCH
import com.octaviantocan.pulls.ui.design.UnderlineTabs
import com.octaviantocan.pulls.ui.design.floatShadow
import com.octaviantocan.pulls.ui.home.StatusColors
import com.octaviantocan.pulls.ui.home.SwipeRow
import com.octaviantocan.pulls.ui.home.compactCount
import com.octaviantocan.pulls.ui.design.hairlineTop
import com.octaviantocan.pulls.ui.design.bleed
import com.octaviantocan.pulls.ui.design.edgeFade
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.offset


import com.octaviantocan.pulls.data.Brief

internal object AndroidOverviewAiContent : OverviewAiContent {
    override val canReadResults = true
    override val canStartPaidWork = true

    @Composable override fun Summary(session: ReviewSession, text: String, onCite: (Cite) -> Unit) {
        val c = LocalPullsColors.current
        ProvideAiText(session, { place -> onCite(Cite(place.path, place.line)) }) {
            Markdown(text, style = PullsType.read.copy(color = c.text))
        }
    }

    @Composable override fun Brief(session: ReviewSession, brief: Brief, onCite: (Cite) -> Unit, modifier: Modifier) {
        ProvideAiText(session, { place -> onCite(Cite(place.path, place.line)) }) {
            BriefSection(session, brief, onOpen = { target -> onCite(Cite(target.path, target.line ?: 0)) }, modifier = modifier)
        }
    }

    @Composable override fun NeedsHuman(session: ReviewSession, onCite: (Cite) -> Unit, onSheet: (String) -> Unit) = AndroidNeedsHuman(session, onCite, onSheet)

    @Composable override fun SharedBanner(session: ReviewSession, modifier: Modifier) = SharedAnalysisBanner(session, modifier)

    @Composable override fun FileNote(session: ReviewSession, text: String) {
        val c = LocalPullsColors.current
        com.octaviantocan.pulls.ui.ai.AiMarkdown(session, text, style = PullsType.sm.copy(lineHeight = PullsType.sm.fontSize * 1.4f, color = c.muted), maxLines = 2)
    }
}

/**
 * `What needs a human`: what the AI review found, most serious first, each pointing at its line. The heading has no
 * line under it, like the others (rule 75): a hairline sits between rows only. Looking for problems is the orb with a
 * small square, which stops it (rule 87), and "Look for problems" opens the review setup.
 */
@Composable
private fun AndroidNeedsHuman(session: ReviewSession, onCite: (Cite) -> Unit, onSheet: (String) -> Unit) {
    val c = LocalPullsColors.current
    val context = LocalContext.current
    if (session.key == null || !cliEngine(context)) return
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
    val stop = guardedStop(session, listOf("critique"), reviewing) { critiqueTask?.let { session.cancelTask(it.id) } }
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
                    Modifier.size(TOUCH).clip(CircleShape).cueClickable(role = Role.Button, onClickLabel = "Stop", onClick = { stop?.invoke() }),
                    contentAlignment = Alignment.Center,
                ) { Orb(size = 22.dp, stop = true) }
                Text("Looking for problems…", Modifier.weight(1f).shimmer(), style = PullsType.sm, color = c.muted)
            }
            findings.isEmpty() && session.saved?.critique != null -> Text(
                t("critique.noFindings"), Modifier.fillMaxWidth().then(line(tail)).padding(vertical = 11.dp), style = PullsType.sm, color = c.muted,
            )
            findings.isEmpty() -> Row(
                Modifier.fillMaxWidth().then(line(tail)).cueClickable(role = Role.Button) { onSheet("review") }.padding(vertical = 11.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("Look for problems", Modifier.weight(1f), style = PullsType.sm.copy(fontWeight = FontWeight.Medium), color = c.activeText)
                LuIcon(Lu.chev, tint = c.faint, size = 18.dp)
            }
        }
    }
}
