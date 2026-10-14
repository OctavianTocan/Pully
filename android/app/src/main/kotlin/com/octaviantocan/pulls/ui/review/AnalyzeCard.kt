package com.octaviantocan.pulls.ui.review

import android.os.SystemClock
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.octaviantocan.pulls.core.StepMark
import com.octaviantocan.pulls.core.analyzeCard
import com.octaviantocan.pulls.data.Repo
import com.octaviantocan.pulls.data.ServerStore
import com.octaviantocan.pulls.data.TaskState
import com.octaviantocan.pulls.sound.cueClickable
import com.octaviantocan.pulls.ui.ai.AiActivityLog
import com.octaviantocan.pulls.ui.ai.AnalyzeSplitButton
import com.octaviantocan.pulls.ui.ai.cliEngine
import com.octaviantocan.pulls.ui.ai.rememberLiveTask
import com.octaviantocan.pulls.ui.ai.runAnalyze
import com.octaviantocan.pulls.ui.ai.shimmer
import com.octaviantocan.pulls.ui.ai.startBrief
import com.octaviantocan.pulls.ui.ai.startTour
import com.octaviantocan.pulls.ui.design.BreathingDot
import com.octaviantocan.pulls.ui.design.FloatIcon
import com.octaviantocan.pulls.ui.design.LocalPullsColors
import com.octaviantocan.pulls.ui.design.Lu
import com.octaviantocan.pulls.ui.design.LuIcon
import com.octaviantocan.pulls.ui.design.OkDot
import com.octaviantocan.pulls.ui.design.Orb
import com.octaviantocan.pulls.ui.design.PendingDot
import com.octaviantocan.pulls.ui.design.PullsRadius
import com.octaviantocan.pulls.ui.design.PullsShadow
import com.octaviantocan.pulls.ui.design.PullsSheet
import com.octaviantocan.pulls.ui.design.PullsType
import com.octaviantocan.pulls.ui.design.SheetCard
import com.octaviantocan.pulls.ui.design.SheetRow
import com.octaviantocan.pulls.ui.design.TOUCH
import com.octaviantocan.pulls.ui.design.floatShadow

/** The Analyze card is this tall in every state, so nothing on the page moves while the AI works (rule 86). */
internal val AnalyzeCardHeight = 72.dp

/** How long after a run starts the orb ignores a tap, so the tap that started it can never also stop it (rule 63). */
private const val STOP_GRACE_MS = 700L

/** What the orb on the card says: nothing has run, it is running, or it finished. */
internal enum class CardOrb { Idle, Working, Done }

/**
 * `.anz`: the card the AI's progress sits in, raised on the page like the tour's cards. It is one height (72dp) in
 * every state. The orb is the only control for stopping: with [onStop] set it is a button with a small square in it
 * (rule 87). A card that [onOpen]s something shows more in a sheet, and holding it changes no colour (rule 82); one
 * that opens nothing is not pressable at all. [headline] is the status and [detail] one line under it that ends in an
 * ellipsis when it is too long (rule 86); [live] shimmers it while work runs (rule 72). [action] is the one control at
 * the right: the Analyze button before a run, a try-again icon after one that did not finish.
 */
@Composable
internal fun AnalyzeStatusCard(
    orb: CardOrb,
    headline: String,
    modifier: Modifier = Modifier,
    detail: String? = null,
    problem: Boolean = false,
    live: Boolean = false,
    onOpen: (() -> Unit)? = null,
    onStop: (() -> Unit)? = null,
    action: (@Composable RowScope.() -> Unit)? = null,
) {
    val c = LocalPullsColors.current
    val shape = RoundedCornerShape(PullsRadius.card)
    Row(
        modifier.testTag("analyze-card").fillMaxWidth().height(AnalyzeCardHeight).floatShadow(shape, PullsShadow.Note).clip(shape).background(c.raised)
            .then(if (onOpen != null) Modifier.cueClickable(remember { MutableInteractionSource() }, null, role = Role.Button, onClickLabel = "Show what it did", onClick = onOpen) else Modifier)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(TOUCH).then(if (onStop != null) Modifier.testTag("analyze-stop").clip(CircleShape).cueClickable(role = Role.Button, onClickLabel = "Stop", onClick = onStop) else Modifier),
            contentAlignment = Alignment.Center,
        ) { Orb(size = 30.dp, done = orb == CardOrb.Done, idle = orb == CardOrb.Idle, stop = onStop != null) }
        Column(Modifier.weight(1f).padding(start = 4.dp, end = 8.dp)) {
            Text(headline, style = PullsType.row.copy(fontWeight = FontWeight.Medium), color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (detail != null) {
                Text(
                    detail, if (live) Modifier.shimmer() else Modifier,
                    style = PullsType.sm, color = if (problem) c.error else c.muted, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
        }
        action?.invoke(this)
    }
}

/** The try-again icon at the right of the card, with its label for a screen reader (rule 87). */
@Composable
private fun TryAgain(label: String, onClick: () -> Unit) {
    FloatIcon(Lu.redo, label, onClick, flat = true, iconSize = 22.dp)
}

/**
 * [stop] for an orb that is the Stop button: null while nothing runs, and while something does it ignores a tap in the
 * first moments, so the tap that started the work can never also stop it (rule 63).
 */
@Composable
internal fun guardedStop(running: Boolean, stop: () -> Unit): (() -> Unit)? {
    val since = remember(running) { SystemClock.elapsedRealtime() }
    val latest by rememberUpdatedState(stop)
    return if (running) ({ if (SystemClock.elapsedRealtime() - since > STOP_GRACE_MS) latest() }) else null
}

/** What the card says under its title while something runs: the latest step of the work log, or that the engine is starting. */
internal fun liveLine(task: TaskState, engine: String): String =
    task.activities.lastOrNull()?.title ?: "Starting ${if (engine == "codex") "Codex" else "Claude Code"}…"

/**
 * The AI's status on the Summary tab. Before any analysis it offers to start one: the button runs the last choice and
 * its chevron opens the others. After Analyze is pressed it is there at once and holds its height through every state:
 * working (a shimmering line of what it is doing, the orb is Stop), finished, stopped or failed (a try-again icon). A
 * tap on it opens the work log in a sheet; the steps are not on the card (rule 86).
 */
@Composable
internal fun AnalysisBlock(session: ReviewSession, onSheet: (String) -> Unit) {
    val context = LocalContext.current
    if (session.key == null || !cliEngine(context)) return
    val analyzeTask = rememberLiveTask(session, "analyze")
    val tourTask = rememberLiveTask(session, "tour")
    val briefTask = rememberLiveTask(session, "brief")
    val engine = remember { ServerStore.load(context).engine }

    if (analyzeTask == null && tourTask?.running != true && briefTask?.running != true) {
        if (session.hasAiResult) return
        // Quick and Deep read the model's efforts from its list; have it before the button is pressed.
        val repo = remember { Repo(context) }
        LaunchedEffect(engine) { if (ModelCatalogs.get(engine) == null) fetchCatalog(repo, engine) }
        val choice = session.settings.analyzeChoice
        AnalyzeStatusCard(CardOrb.Idle, "Not analyzed yet", Modifier.padding(top = 16.dp)) {
            AnalyzeSplitButton(choice.title, onRun = { session.runAnalyze(context) }, onMenu = { onSheet("analyze") })
        }
        return
    }

    val card = analyzeCard(
        analyze = analyzeTask?.status,
        tour = tourTask?.status,
        brief = briefTask?.status,
        hasTour = session.saved?.tour != null,
        hasBrief = session.saved?.brief != null,
        then = analyzeTask?.then.orEmpty(),
    )
    val tasks = listOfNotNull(analyzeTask, tourTask, briefTask)
    val live = tasks.firstOrNull { it.running }
    val failed = analyzeTask?.status == "failed"
    val stopped = analyzeTask?.status == "cancelled"
    // A tour or brief that failed after a good analysis does not undo it; it is run again on its own.
    val partial = if (card.working || failed || stopped) null else listOf("The tour" to tourTask, "The brief" to briefTask).firstNotNullOfOrNull { (name, found) -> found?.takeIf { it.status == "failed" }?.let { name to it } }
    val stop = guardedStop(card.working) { tasks.forEach { task -> if (task.running) session.cancelTask(task.id) } }

    AnalyzeStatusCard(
        orb = when {
            card.working -> CardOrb.Working
            failed || stopped -> CardOrb.Idle
            else -> CardOrb.Done
        },
        headline = card.title,
        modifier = Modifier.padding(top = 16.dp),
        detail = when {
            live != null -> liveLine(live, engine)
            failed -> analyzeTask?.error ?: "The analysis did not finish."
            partial != null -> partial.second.error ?: "${partial.first} did not finish."
            else -> null
        },
        problem = live == null && (failed || partial != null),
        live = live != null,
        onOpen = { onSheet("log") },
        onStop = stop,
    ) {
        when {
            failed || stopped -> TryAgain("Try again") { session.runAnalyze(context) }
            partial != null -> TryAgain("Try ${partial.first.lowercase()} again") { if (partial.second.kind == "tour") startTour(session) else startBrief(session) }
        }
    }
}

/** One step of the analysis as the log sheet shows it: a hollow ring, a breathing dot, a tick or a cross. */
@Composable
internal fun StepDot(mark: StepMark) {
    val c = LocalPullsColors.current
    when (mark) {
        StepMark.Pending, StepMark.Stopped -> PendingDot()
        StepMark.Active -> BreathingDot()
        StepMark.Done -> OkDot()
        StepMark.Failed -> LuIcon(Lu.xc, tint = c.error, size = 14.dp)
    }
}

/**
 * What a tap on the Analyze card opens: the steps of the analysis, then the work log of each task that ran. The page
 * keeps its shape while the AI works and this is where the detail lives (rule 86).
 */
@Composable
internal fun AnalysisLogSheet(session: ReviewSession, onClose: () -> Unit) {
    val c = LocalPullsColors.current
    val analyzeTask = rememberLiveTask(session, "analyze")
    val tourTask = rememberLiveTask(session, "tour")
    val briefTask = rememberLiveTask(session, "brief")
    val card = analyzeCard(
        analyze = analyzeTask?.status,
        tour = tourTask?.status,
        brief = briefTask?.status,
        hasTour = session.saved?.tour != null,
        hasBrief = session.saved?.brief != null,
        then = analyzeTask?.then.orEmpty(),
    )
    val tasks = listOfNotNull(analyzeTask, tourTask, briefTask)
    PullsSheet(onClose, card.title) {
        SheetCard {
            card.steps.forEachIndexed { index, step ->
                SheetRow(step.label, null, first = index == 0, icon = { StepDot(step.mark) })
            }
        }
        analyzeTask?.takeIf { it.status == "failed" }?.error?.let { error ->
            Text(error, Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp), style = PullsType.sm, color = c.error)
        }
        Column(Modifier.padding(top = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            tasks.forEach { task -> AiActivityLog(task.activities, task.running) }
        }
    }
}
