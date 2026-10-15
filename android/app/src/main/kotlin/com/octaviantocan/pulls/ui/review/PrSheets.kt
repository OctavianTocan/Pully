package com.octaviantocan.pulls.ui.review

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.runtime.mutableStateOf
import com.octaviantocan.pulls.ui.ai.AnalyzePage
import com.octaviantocan.pulls.ui.ai.AnalyzePageContent
import com.octaviantocan.pulls.ui.ai.ReviewLensList
import com.octaviantocan.pulls.ui.ai.analyzePageTitle
import com.octaviantocan.pulls.ui.ai.rememberCustomAnalysis
import com.octaviantocan.pulls.ui.design.CtaButton
import com.octaviantocan.pulls.ui.design.PullsPagedSheet
import com.octaviantocan.pulls.data.ReviewFile
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.octaviantocan.pulls.R
import com.octaviantocan.pulls.data.Repo
import com.octaviantocan.pulls.data.ServerConfig
import com.octaviantocan.pulls.data.ServerStore
import com.octaviantocan.pulls.sound.Cue
import com.octaviantocan.pulls.ui.ai.CritiquePanel
import com.octaviantocan.pulls.ui.ai.cliEngine
import com.octaviantocan.pulls.ui.ai.runAnalyze
import com.octaviantocan.pulls.ui.design.LocalMessages
import com.octaviantocan.pulls.ui.design.EngineMark
import com.octaviantocan.pulls.ui.design.LocalPullsColors
import com.octaviantocan.pulls.ui.design.LocalToaster
import com.octaviantocan.pulls.ui.design.Lu
import com.octaviantocan.pulls.ui.design.LuGlyph
import com.octaviantocan.pulls.ui.design.LuIcon
import com.octaviantocan.pulls.ui.design.Messages
import com.octaviantocan.pulls.ui.design.PullsFonts
import com.octaviantocan.pulls.ui.design.PullsSheet
import com.octaviantocan.pulls.ui.design.PullsSwitch
import com.octaviantocan.pulls.ui.design.PullsType
import com.octaviantocan.pulls.ui.design.QuickAction
import com.octaviantocan.pulls.ui.design.SheetCard
import com.octaviantocan.pulls.ui.design.SheetRow
import com.octaviantocan.pulls.ui.design.t
import com.octaviantocan.pulls.ui.home.DrawableIcon
import com.octaviantocan.pulls.ui.home.StatusColors
import com.octaviantocan.pulls.ui.settings.AiSettingsLogic
import com.octaviantocan.pulls.ui.settings.CliCatalog
import com.octaviantocan.pulls.ui.settings.asCatalog
import kotlinx.coroutines.CancellationException

/** The model lists the server reported this run, so a sheet that opens again shows names at once. */
internal object ModelCatalogs {
    private val cache = HashMap<String, CliCatalog>()
    fun get(engine: String): CliCatalog? = synchronized(cache) { cache[engine] }
    fun put(engine: String, catalog: CliCatalog) { synchronized(cache) { cache[engine] = catalog } }
}

/** `ai-models` for [engine], cached; the failure comes back as a value so the sheet can say why. */
internal suspend fun fetchCatalog(repo: Repo, engine: String, refresh: Boolean = false): Result<CliCatalog> = try {
    Result.success(repo.models(engine, refresh).asCatalog().also { ModelCatalogs.put(engine, it) })
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    Result.failure(e)
}

/** `Opus 5.5 · High`: the model the analysis will use and its effort. */
internal fun analysisModelLabel(config: ServerConfig, catalog: CliCatalog?, effort: String, messages: Messages): String {
    val model = AiSettingsLogic.current(catalog, config.model)
    val name = model?.let(::modelLabel) ?: config.model.ifBlank { null }?.let(::prettyModelId) ?: "Default"
    val shown = effort.ifBlank { model?.defaultEffort.orEmpty() }
    return listOf(name, shown.takeIf { it.isNotBlank() }?.let { AiSettingsLogic.effortLabel(it) { key -> messages.t(key) } }).filterNotNull().joinToString(" · ")
}

/** The sheet title the PR sheets share: the repository and the number in Mona Sans. */
@Composable
private fun PrTitle(head: PrHead) {
    val c = LocalPullsColors.current
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(head.repo ?: head.title, Modifier.weight(1f, fill = false), style = PullsType.lg.copy(fontWeight = FontWeight.SemiBold), color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(head.label, style = PullsType.lg.copy(fontFamily = PullsFonts.display, fontWeight = FontWeight.SemiBold), color = c.text, maxLines = 1)
    }
}

private fun stateGlyph(change: String): LuGlyph = when (change) {
    "ready" -> Lu.okc
    "draft" -> Lu.draft
    "close" -> Lu.shut
    else -> Lu.redo
}

private fun blockerLabel(blocker: String): String = when (blocker) {
    "draft" -> "Draft"
    "conflicts" -> "Conflicts"
    "blocked" -> "Blocked"
    "behind" -> "Behind"
    else -> "Checking…"
}

/** The blockers GitHub clears by itself once checks and reviews come in; conflicts, drafts and a stale base need a person. */
private val AUTO_MERGE_WAITS = setOf("blocked", "checking")

/**
 * The pages of the pull request's sheet. Everything one of its rows opens is a page that slides in inside the same sheet,
 * with ← back to the page it came from, and the phone's Back does the same (rule 78, J27, J33): the analysis model and
 * options, Checks, the AI review's lenses, and the page of decisions with the confirmation each one asks for.
 */
internal sealed interface PrPage {
    /** Aa on the Diff: the code's look and the file's own actions. */
    data object Tools : PrPage
    data object Font : PrPage

    /** ⋯: the pull request's actions. */
    data object Actions : PrPage

    /** Approve, request changes, merge, draft and close: the ones that cannot be taken back lightly (J29). */
    data object Decide : PrPage

    /** The check before one of [Decide]'s changes: what it does, and the one button that does it. */
    data class Confirm(val change: String) : PrPage
    data object Merge : PrPage
    data class Submit(val verdict: String) : PrPage
    data object Checks : PrPage
    data object Review : PrPage
    data object Progress : PrPage
    data class Ai(val page: AnalyzePage) : PrPage
}

/** The page ← goes back to from [page], in a sheet that opened on [start]. */
private fun prParent(page: PrPage, start: PrPage): PrPage? = when (page) {
    PrPage.Tools -> null
    PrPage.Font -> PrPage.Tools
    PrPage.Actions -> if (start == PrPage.Tools) PrPage.Tools else null
    PrPage.Decide, PrPage.Checks, PrPage.Review, PrPage.Progress -> PrPage.Actions
    is PrPage.Submit -> if (page.verdict == "COMMENT") PrPage.Actions else PrPage.Decide
    is PrPage.Confirm, PrPage.Merge -> PrPage.Decide
    is PrPage.Ai -> page.page.parent?.let { PrPage.Ai(it) } ?: PrPage.Actions
}

/**
 * The pull request's sheet: ⋯ opens it on its actions and Aa on the Diff on its text and tools, with the actions a page
 * further in. [onReview] starts an AI review with the lens picked; [file] is the file the Diff shows, for Aa.
 */
@Composable
internal fun PrSheet(
    session: ReviewSession,
    top: ReviewTopUi,
    data: PrOverviewData?,
    head: PrHead,
    start: PrPage,
    onClose: () -> Unit,
    onReview: (String?) -> Unit,
    ui: DiffUi? = null,
    file: ReviewFile? = null,
) {
    var page by remember { mutableStateOf(start) }
    val custom = rememberCustomAnalysis(session)
    val go: (PrPage) -> Unit = { page = it }
    PullsPagedSheet(
        onClose, page, parent = { prParent(it, start) }, onPage = go,
        title = { prPageTitle(it, head, data) },
        heading = { if (it == PrPage.Actions) ({ PrTitle(head) }) else null },
    ) { current ->
        when (current) {
            PrPage.Tools -> if (ui != null) ToolsMain(session, ui, file, onFont = { go(PrPage.Font) }, onClose = onClose, onActions = { go(PrPage.Actions) })
            PrPage.Font -> CodeFontPage(session.settings, onDone = { go(PrPage.Tools) })
            PrPage.Actions -> ActionsPage(session, data, head, onClose, go)
            PrPage.Decide -> DecidePage(session, top, data, head, onClose, go)
            is PrPage.Confirm -> data?.overview?.let { ConfirmPage(session, top, it, head, current.change, onClose) }
            PrPage.Merge -> data?.let { MergePage(session, top, it, onDone = onClose) }
            is PrPage.Submit -> ReviewSubmitPage(session, top, canVerdict = head.isOpen, start = current.verdict, onDone = onClose)
            PrPage.Checks -> data?.overview?.let { ChecksList(it.checks, it.checksSummary.hidden, it.url) }
            PrPage.Review -> ReviewLensList(session, onPick = { lens -> onClose(); onReview(lens) })
            PrPage.Progress -> ReviewProgressPage(session, onClose)
            is PrPage.Ai -> AnalyzePageContent(
                session, current.page, custom, onPage = { go(PrPage.Ai(it)) }, onClose = onClose,
                onModelDone = { go(prParent(current, start) ?: PrPage.Actions) },
            )
        }
    }
}

@Composable
private fun prPageTitle(page: PrPage, head: PrHead, data: PrOverviewData?): String = when (page) {
    PrPage.Tools -> "Text and tools"
    PrPage.Font -> "Code font"
    PrPage.Actions -> "${head.repo ?: head.title} ${head.label}"
    PrPage.Decide -> "Approve, merge or close"
    is PrPage.Confirm -> confirmLabel(page.change)
    PrPage.Merge -> t("overview.merge.title", "n" to (data?.overview?.number ?: head.label))
    is PrPage.Submit -> t("review.reviewChanges")
    PrPage.Checks -> "Checks"
    PrPage.Review -> "Review"
    PrPage.Progress -> t("reviewProgress.title")
    is PrPage.Ai -> analyzePageTitle(page.page)
}

/** What the button that makes [change] says, which is also its page's title. */
@Composable
private fun confirmLabel(change: String): String = when (change) {
    "auto-merge" -> "Merge when checks pass"
    "update-branch" -> t("overview.actions.updateBranch")
    else -> t("overview.actions.$change")
}

/**
 * `#sheet`: Checks, GitHub and Analyze again as tiles; the analysis model; a comment; and a row into the decisions, which
 * sit a page further in so none of them is one slip of the thumb away (J29). There is no Refresh, since the page takes
 * new commits by itself, and no Commands, which a pull down on any page opens (J30).
 */
@Composable
private fun ColumnScope.ActionsPage(session: ReviewSession, data: PrOverviewData?, head: PrHead, onClose: () -> Unit, go: (PrPage) -> Unit) {
    val c = LocalPullsColors.current
    val context = LocalContext.current
    val uri = LocalUriHandler.current
    val toaster = LocalToaster.current
    val messages = LocalMessages.current
    val settings = session.settings
    val overview = data?.overview
    val failing = overview?.checksSummary?.failing ?: 0
    val repo = remember { Repo(context) }
    val config = remember { ServerStore.load(context) }
    val catalog by produceState<CliCatalog?>(ModelCatalogs.get(config.engine), config.engine) {
        if (value == null) value = fetchCatalog(repo, config.engine).getOrNull()
    }
    val pending = session.reviewData?.pendingCount ?: 0
    // A pending review can be submitted or discarded on a closed pull request too; a new verdict needs an open one.
    val canReview = session.pull != null && session.canWrite

    Row(Modifier.fillMaxWidth().padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        QuickAction("Checks", { go(PrPage.Checks) }, Modifier.weight(1f)) {
            if (failing > 0) LuIcon(Lu.fail, tint = StatusColors.red(c.dark), size = 22.dp)
            else LuIcon(Lu.okc, tint = if ((overview?.checksSummary?.total ?: 0) > 0) StatusColors.green(c.dark) else c.muted, size = 22.dp, strokeWidth = 1.8f)
        }
        QuickAction("GitHub", { if (head.url.isNotBlank()) uri.openUri(head.url) }, Modifier.weight(1f)) { DrawableIcon(R.drawable.ic_carbon_logo_github, tint = c.text, size = 22.dp) }
        QuickAction("Analyze", {
            onClose()
            if (session.taskFor("analyze")?.running == true) {
                toaster.show("Already analyzing")
            } else {
                session.runAnalyze(context)
                toaster.show("Analyzing again with ${analysisModelLabel(config, catalog, settings.effort, messages)}", cue = Cue.Loading)
            }
        }, Modifier.weight(1f)) { LuIcon(Lu.list, tint = c.text, size = 22.dp) }
    }

    SheetCard {
        SheetRow("Model", { go(PrPage.Ai(AnalyzePage.Model)) }, first = true, icon = { EngineMark(config.engine, size = 22.dp) }) {
            Text(analysisModelLabel(config, catalog, settings.effort, messages), Modifier.weight(1f, fill = false), style = PullsType.sm, color = c.muted)
            LuIcon(Lu.chev, tint = c.faint, size = 18.dp)
        }
        if (session.key != null && cliEngine(context)) {
            SheetRow("Analyze options", { go(PrPage.Ai(AnalyzePage.Main)) }, iconless = true) {
                Text(settings.analyzeChoice.title, style = PullsType.sm, color = c.muted, maxLines = 1)
                LuIcon(Lu.chev, tint = c.faint, size = 18.dp)
            }
        }
    }

    // A comment stays here (Tavi: "you can keep it"); the verdicts went in with the other decisions.
    if (canReview && (pending > 0 || head.isOpen)) SheetCard {
        if (pending > 0) SheetRow("Submit comments", { go(PrPage.Submit("COMMENT")) }, first = true, iconless = true) {
            Text("$pending comment${if (pending == 1) "" else "s"}", style = PullsType.sm, color = c.muted)
            LuIcon(Lu.chev, tint = c.faint, size = 18.dp)
        }
        else SheetRow("Leave a comment", { go(PrPage.Submit("COMMENT")) }, first = true, iconless = true) {
            LuIcon(Lu.chev, tint = c.faint, size = 18.dp)
        }
    }

    if (decisions(session, data, head)) SheetCard {
        SheetRow("Approve, merge or close", { go(PrPage.Decide) }, first = true, iconless = true) {
            LuIcon(Lu.chev, tint = c.faint, size = 18.dp)
        }
    }

    val ai = session.key != null && cliEngine(context)
    if (ai || session.files.isNotEmpty()) SheetCard {
        if (ai) {
            // One row: the Review page picks General or a lens, and a tap there starts the review.
            SheetRow("Review with AI", { go(PrPage.Review) }, first = true, icon = { LuIcon(Lu.check, tint = c.text) }) {
                LuIcon(Lu.chev, tint = c.faint, size = 18.dp)
            }
        }
        if (session.files.isNotEmpty()) {
            // Its own glyph, so the card's icon column stays whole and no glyph repeats (rule 48).
            SheetRow(t("reviewProgress.title"), { go(PrPage.Progress) }, first = !ai, icon = { LuIcon(Lu.okc, tint = c.text, size = 20.dp) }) {
                LuIcon(Lu.chev, tint = c.faint, size = 18.dp)
            }
        }
    }
}

/** Whether the decisions page has anything on it for this reader and this pull request. */
private fun decisions(session: ReviewSession, data: PrOverviewData?, head: PrHead): Boolean {
    val overview = data?.overview ?: return session.pull != null && session.canWrite && head.isOpen
    val merging = overview.isOpen && data.mergeability.allowed
    return (session.pull != null && session.canWrite && head.isOpen) || merging || stateActions(overview).isNotEmpty()
}

/**
 * The decisions, each of which asks once more before it is made (J29): Approve and Request changes open the review with
 * that verdict, the merge opens its own page, and draft, close, reopen, merging later and updating the branch each open a
 * page that says what will happen, with one button that does it.
 */
@Composable
private fun ColumnScope.DecidePage(session: ReviewSession, top: ReviewTopUi, data: PrOverviewData?, head: PrHead, onClose: () -> Unit, go: (PrPage) -> Unit) {
    val c = LocalPullsColors.current
    val toaster = LocalToaster.current
    val overview = data?.overview
    val failing = overview?.checksSummary?.failing ?: 0
    val number = head.label
    val blockedText = t("overview.merge.blocked.${data?.mergeability?.blocker ?: "blocked"}")
    val canReview = session.pull != null && session.canWrite

    // The verdicts are words: a speech bubble on them carried no fact (rule 48).
    if (canReview && head.isOpen) SheetCard {
        SheetRow("Approve", { go(PrPage.Submit("APPROVE")) }, first = true, iconless = true) { LuIcon(Lu.chev, tint = c.faint, size = 18.dp) }
        SheetRow("Request changes", { go(PrPage.Submit("REQUEST_CHANGES")) }, iconless = true) { LuIcon(Lu.chev, tint = c.faint, size = 18.dp) }
    }

    val mergeability = data?.mergeability
    if (overview != null && overview.isOpen && mergeability != null && mergeability.allowed) SheetCard {
        SheetRow(
            t("overview.merge.method.${overview.merge.defaultMethod}"),
            {
                if (!mergeability.ready) {
                    toaster.show(if (failing > 0) "$failing check${if (failing == 1) "" else "s"} failing · merge is blocked" else blockedText, cue = Cue.Error)
                } else {
                    go(PrPage.Merge)
                }
            },
            first = true, icon = { LuIcon(Lu.merge, tint = c.text) },
        ) {
            when {
                failing > 0 -> {
                    LuIcon(Lu.xc, tint = StatusColors.red(c.dark), size = 18.dp)
                    Text("$failing failing", style = PullsType.sm, color = c.muted, maxLines = 1)
                }
                mergeability.blocker != null -> Text(blockerLabel(mergeability.blocker), style = PullsType.sm, color = c.muted, maxLines = 1)
                else -> Text("Ready", style = PullsType.sm, color = StatusColors.green(c.dark), maxLines = 1)
            }
        }
        // Merging later is the row under merging now (rule 29): turning it on asks first, turning it off does not, since
        // that only stops something from happening. It follows the blocker, not `ready`: an admin who could bypass
        // protection may still choose to wait for it.
        val waiting = overview.autoMergeMethod != null
        val autoMerge: (() -> Unit)? = when {
            waiting && overview.viewer.canDisableAutoMerge -> {
                {
                    onClose()
                    top.run(session, "cancel-auto-merge", onOk = { toaster.show("$number will not merge by itself") }) {
                        session.repo.prAction(overview.id, "cancel-auto-merge")
                    }
                }
            }
            overview.viewer.canEnableAutoMerge && mergeability.blocker in AUTO_MERGE_WAITS -> ({ go(PrPage.Confirm("auto-merge")) })
            else -> null
        }
        if (autoMerge != null) SheetRow("Merge when checks pass", autoMerge) {
            PullsSwitch(waiting, { autoMerge() }, "Merge when checks pass", Modifier.clearAndSetSemantics {})
        }
        if (overview.viewer.canUpdateBranch) {
            SheetRow(t("overview.actions.updateBranch"), { go(PrPage.Confirm("update-branch")) }, icon = { LuIcon(Lu.branch, tint = c.text) })
        }
    }

    val actions = overview?.let { stateActions(it) }.orEmpty()
    if (actions.isNotEmpty()) SheetCard {
        actions.forEachIndexed { index, (change, _) ->
            SheetRow(t("overview.actions.$change"), { go(PrPage.Confirm(change)) }, first = index == 0, danger = change == "close", icon = { LuIcon(stateGlyph(change), tint = if (change == "close") c.error else c.text) })
        }
    }
}

/** What [change] does, in a line, for the page that asks before it is made. */
private fun confirmText(change: String, number: String, base: String, method: String): String = when (change) {
    "close" -> "$number closes without merging. You can reopen it later."
    "draft" -> "$number goes back to draft, so reviewers are not asked for a review until it is marked ready."
    "ready" -> "$number is marked ready, and its reviewers are asked for a review."
    "reopen" -> "$number opens again where it was left."
    "auto-merge" -> "$number merges by itself ($method) as soon as its required checks and reviews pass."
    "update-branch" -> "The newest commits on $base are merged into $number's branch."
    else -> ""
}

/** The check before a change to the pull request: what it does, and the button that does it, under ← back (J29). */
@Composable
private fun ColumnScope.ConfirmPage(session: ReviewSession, top: ReviewTopUi, overview: PrOverview, head: PrHead, change: String, onClose: () -> Unit) {
    val c = LocalPullsColors.current
    val toaster = LocalToaster.current
    val number = head.label
    val method = overview.merge.defaultMethod
    Text(
        confirmText(change, number, overview.baseRef, t("overview.merge.method.$method")),
        Modifier.padding(start = 4.dp, end = 4.dp, top = 6.dp),
        style = PullsType.read, color = c.muted,
    )
    CtaButton(confirmLabel(change), {
        onClose()
        when (change) {
            "auto-merge" -> top.run(session, "auto-merge", onOk = { toaster.show("$number will merge when its checks pass") }) {
                session.repo.prAction(overview.id, "auto-merge", method = method, expectedHeadOid = overview.headOid)
            }
            "update-branch" -> top.run(session, "update-branch") { session.repo.prAction(overview.id, "update-branch", expectedHeadOid = overview.headOid) }
            else -> {
                val inverse = mapOf("ready" to "draft", "draft" to "ready", "close" to "reopen", "reopen" to "close").getValue(change)
                val message = when (change) {
                    "ready" -> "$number is ready for review"
                    "draft" -> "Converted $number to draft"
                    "close" -> "Closed $number"
                    else -> "Reopened $number"
                }
                top.run(session, change, onOk = {
                    toaster.show(message, "Undo") { top.run(session, inverse) { session.repo.prAction(overview.id, inverse) } }
                }) { session.repo.prAction(overview.id, change) }
            }
        }
    }, Modifier.fillMaxWidth().padding(top = 20.dp), container = if (change == "close") c.error else c.cta)
}

/** Every finding of the AI review, with its filters and the option to post them as a review. */
@Composable
internal fun FindingsSheet(session: ReviewSession, onClose: () -> Unit) {
    PullsSheet(onClose, "Review findings", full = true) { CritiquePanel(session, Modifier.padding(top = 12.dp)) }
}
