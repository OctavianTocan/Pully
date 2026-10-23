package com.octaviantocan.pulls.ui.review

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.runtime.mutableStateOf
import com.octaviantocan.pulls.ui.ai.ReviewSetup
import com.octaviantocan.pulls.ui.ai.AnalyzePage
import com.octaviantocan.pulls.ui.ai.AnalyzePageContent
import com.octaviantocan.pulls.ui.ai.analyzePageTitle
import com.octaviantocan.pulls.ui.ai.rememberCustomAnalysis
import com.octaviantocan.pulls.ui.ai.ModelCard
import com.octaviantocan.pulls.ui.design.CtaButton
import com.octaviantocan.pulls.ui.design.PullsPagedSheet
import com.octaviantocan.pulls.data.ReviewFile
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.getValue
import com.octaviantocan.pulls.data.ENGINES
import com.octaviantocan.pulls.ui.ai.LocalAiCapabilities
import com.octaviantocan.pulls.ui.ai.rememberAiSetupActions
import com.octaviantocan.pulls.ui.settings.ModelSettingsServices
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.octaviantocan.pulls.sound.Cue
import com.octaviantocan.pulls.ui.ai.CritiquePanel
import com.octaviantocan.pulls.ui.design.LocalPullsColors
import com.octaviantocan.pulls.ui.design.LocalToaster
import com.octaviantocan.pulls.ui.design.Lu
import com.octaviantocan.pulls.ui.design.LuGlyph
import com.octaviantocan.pulls.ui.design.LuIcon
import com.octaviantocan.pulls.ui.design.PullsFonts
import com.octaviantocan.pulls.ui.design.PullsSwitch
import com.octaviantocan.pulls.ui.design.PullsType
import com.octaviantocan.pulls.ui.design.QuickAction
import com.octaviantocan.pulls.ui.design.SheetCard
import com.octaviantocan.pulls.ui.design.SheetRow
import com.octaviantocan.pulls.ui.design.SheetTopGap
import com.octaviantocan.pulls.ui.design.t
import com.octaviantocan.pulls.ui.home.GithubIcon
import com.octaviantocan.pulls.ui.home.StatusColors

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

/** The page ← goes back to from [page], in a sheet that opened on [start]. */
private fun prParent(page: PrPage, start: PrPage): PrPage? = when (page) {
    PrPage.Tools -> null
    PrPage.Font -> PrPage.Tools
    PrPage.Actions -> if (start == PrPage.Tools) PrPage.Tools else null
    PrPage.Decide, PrPage.Review -> PrPage.Actions
    PrPage.ReviewModel, PrPage.ReviewEffort -> PrPage.Review
    is PrPage.Analyze -> when (page.page) {
        AnalyzePage.Model, AnalyzePage.Effort -> PrPage.Analyze(page.origin)
        else -> page.page.parent?.let { PrPage.Analyze(it) } ?: PrPage.Actions
    }
    is PrPage.Submit -> if (page.verdict == "COMMENT") PrPage.Actions else PrPage.Decide
    is PrPage.Discard -> PrPage.Submit(page.verdict)
    is PrPage.Confirm, PrPage.Merge -> PrPage.Decide
}

/**
 * The pull request's sheet: ⋯ opens it on its actions and Aa on the Diff on its text and tools, with the actions a page
 * further in. [onReview] starts an AI review with the lens picked; [file] is the file the Diff shows, for Aa.
 */
@Composable
fun PrSheet(
    session: ReviewSession,
    top: ReviewTopUi,
    data: PrOverviewData?,
    head: PrHead,
    start: PrPage,
    services: PrSheetServices,
    onClose: () -> Unit,
    onReview: (String?) -> Unit,
    ui: DiffUi? = null,
    file: ReviewFile? = null,
) {
    key(session, session.captureView(), services) {
        PrSheetBody(session, top, data, head, start, services, onClose, onReview, ui, file)
    }
}

@Composable
private fun PrSheetBody(session: ReviewSession, top: ReviewTopUi, data: PrOverviewData?, head: PrHead, start: PrPage, services: PrSheetServices, onClose: () -> Unit, onReview: (String?) -> Unit, ui: DiffUi?, file: ReviewFile?) {
    val actions = rememberReviewMutationActions(session, top, data)
    val ai = rememberAiSetupActions(session, services.models)
    var page by remember { mutableStateOf(start) }
    val custom = rememberCustomAnalysis(session)
    val go: (PrPage) -> Unit = { actions.read { page = it } }
    PullsPagedSheet(
        onClose, page, parent = { prParent(it, start) }, onPage = go,
        title = { prPageTitle(it, head, data) },
        heading = { if (it == PrPage.Actions) ({ PrTitle(head) }) else null },
    ) { current ->
        when (current) {
            PrPage.Tools -> if (ui != null) ToolsMain(session, ui, top, file, services.native, onFont = { go(PrPage.Font) }, onClose = onClose, onActions = { go(PrPage.Actions) })
            PrPage.Font -> CodeFontPage(session.settings, current = { actions.current }, onDone = { go(PrPage.Tools) })
            PrPage.Actions -> ActionsPage(session, top, data, head, services, onClose, go)
            PrPage.Decide -> DecidePage(session, top, data, head, onClose, go)
            is PrPage.Confirm -> data?.overview?.let { ConfirmPage(session, top, it, head, current.change, onClose) }
            PrPage.Merge -> data?.let { MergePage(session, top, it, onDone = onClose) }
            is PrPage.Submit -> ReviewSubmitPage(session, top, canVerdict = head.isOpen, start = current.verdict, onDiscard = { go(PrPage.Discard(current.verdict)) }, onDone = onClose)
            is PrPage.Discard -> ReviewDiscardPage(session, top, onDone = onClose)
            PrPage.Review -> if (ai.canCritique) ReviewSetup(session, services.models, onPick = { lens -> if (ai.canCritique) { onReview(lens); onClose() } }, onModel = { go(PrPage.ReviewModel) }, onEffort = { go(PrPage.ReviewEffort) })
            PrPage.ReviewModel -> ModelPage(session.settings, services.models) { go(PrPage.Review) }
            PrPage.ReviewEffort -> EffortPage(session.settings, services.models) { go(PrPage.Review) }
            is PrPage.Analyze -> AnalyzePageContent(
                session, current.page, custom, services.models,
                onPage = { next -> go(PrPage.Analyze(next, origin = current.page)) },
                onModelDone = { go(PrPage.Analyze(current.origin)) }, onClose = onClose,
                onEffortDone = { go(PrPage.Analyze(current.origin)) },
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
    is PrPage.Discard -> t("review.discard")
    PrPage.Review -> "Find problems"
    PrPage.ReviewModel -> "Select model"
    PrPage.ReviewEffort -> "Effort"
    is PrPage.Analyze -> analyzePageTitle(page.page)
}

/** What the button that makes [change] says, which is also its page's title. */
@Composable
private fun confirmLabel(change: String): String = when (change) {
    "auto-merge" -> "Merge when checks pass"
    "update-branch" -> t("overview.actions.updateBranch")
    else -> t("overview.actions.$change")
}

/**
 * `#sheet`: GitHub, Copy link and Analyze setup as tiles; a comment; a row into the decisions, which sit a page further in so none
 * of them is one slip of the thumb away (J29); and Review with AI. There is no Refresh, since the page takes new commits
 * by itself, and no Commands, which a pull down on any page opens (J30). Checks is not a tile, since the Overview keeps
 * them pinned at the top, and the model, Analyze options and Review progress are gone ("confusing", "fully", recording
 * of 16 Oct 12:10, 21:11 to 21:38; N39).
 */
@Composable
private fun ColumnScope.ActionsPage(session: ReviewSession, top: ReviewTopUi, data: PrOverviewData?, head: PrHead, services: PrSheetServices, onClose: () -> Unit, go: (PrPage) -> Unit) {
    val c = LocalPullsColors.current
    val actions = rememberReviewMutationActions(session, top, data)
    val ai = rememberAiSetupActions(session, services.models)
    val toaster = LocalToaster.current
    val pending = session.reviewData?.pendingCount ?: 0
    // A pending review can be submitted or discarded on a closed pull request too; a new verdict needs an open one.
    val canReview = actions.canSubmit

    Row(Modifier.fillMaxWidth().padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        // GitHub opens the page outside Pully, which would otherwise take its own link back; Copy link is the link to
        // send on, as the Inbox row's hold sheet has it (Tavi, 20 Oct).
        if (head.url.isNotBlank()) QuickAction("GitHub", { actions.read { services.native.openOnGithub(head.url) } }, Modifier.weight(1f)) { GithubIcon(tint = c.text, size = 22.dp) }
        if (head.url.isNotBlank()) QuickAction("Copy link", {
            actions.read { services.native.copyText(head.url); toaster.show("Link copied", cue = Cue.Success); onClose() }
        }, Modifier.weight(1f)) { LuIcon(Lu.copy, tint = c.text, size = 22.dp) }
        if (LocalAiCapabilities.current.canAnalyze) QuickAction("Analyze", { ai.read { go(PrPage.Analyze()) } }, Modifier.weight(1f)) { LuIcon(Lu.list, tint = c.text, size = 22.dp) }
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

    if ((canReview && head.isOpen) || (actions.canEditPullRequest && decisions(session, data, head))) SheetCard {
        SheetRow("Approve, merge or close", { go(PrPage.Decide) }, first = true, iconless = true) {
            LuIcon(Lu.chev, tint = c.faint, size = 18.dp)
        }
    }

    if (actions.current) services.checks?.invoke(session.pull, head.isOpen)

    // One row: the review setup shows its model before General or a skill starts work. Alone in its card it needs no
    // icon column, like the rows above it.
    if (session.key != null && ai.canCritique && services.models.configuration().engine in ENGINES) SheetCard {
        SheetRow("Find problems", { go(PrPage.Review) }, first = true, iconless = true) {
            LuIcon(Lu.chev, tint = c.faint, size = 18.dp)
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
    val actions = rememberReviewMutationActions(session, top, data)
    val overview = data?.overview
    val failing = overview?.checksSummary?.failing ?: 0
    val number = head.label
    val blockedText = t("overview.merge.blocked.${data?.mergeability?.blocker ?: "blocked"}")
    val canReview = actions.canSubmit

    // The verdicts are words: a speech bubble on them carried no fact (rule 48).
    if (canReview && head.isOpen) SheetCard {
        SheetRow("Approve", { go(PrPage.Submit("APPROVE")) }, first = true, iconless = true) { LuIcon(Lu.chev, tint = c.faint, size = 18.dp) }
        SheetRow("Request changes", { go(PrPage.Submit("REQUEST_CHANGES")) }, iconless = true) { LuIcon(Lu.chev, tint = c.faint, size = 18.dp) }
    }

    val mergeability = data?.mergeability
    if (actions.canEditPullRequest && overview != null && overview.isOpen && mergeability != null && mergeability.allowed) SheetCard {
        SheetRow(
            t("overview.merge.method.${overview.merge.defaultMethod}"),
            {
                if (!mergeability.ready) {
                    toaster.show(
                        if (mergeability.blocker == "conflicts") blockedText
                        else if (failing > 0) "$failing check${if (failing == 1) "" else "s"} failing · merge is blocked"
                        else blockedText,
                        cue = Cue.Error,
                    )
                } else {
                    go(PrPage.Merge)
                }
            },
            first = true, icon = { LuIcon(Lu.merge, tint = c.text) },
        ) {
            when {
                mergeability.blocker == "conflicts" -> Text(blockerLabel("conflicts"), style = PullsType.sm, color = c.muted, maxLines = 1)
                failing > 0 -> {
                    LuIcon(Lu.xc, tint = StatusColors.red(c.dark), size = 18.dp)
                    Text("$failing failing", style = PullsType.sm, color = c.muted, maxLines = 1)
                }
                mergeability.blocker != null -> Text(blockerLabel(checkNotNull(mergeability.blocker)), style = PullsType.sm, color = c.muted, maxLines = 1)
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
                    actions.pullRequest("cancel-auto-merge") { toaster.show("$number will not merge by itself"); onClose() }
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

    val stateChanges = if (actions.canEditPullRequest) overview?.let { stateActions(it) }.orEmpty() else emptyList()
    if (stateChanges.isNotEmpty()) SheetCard {
        stateChanges.forEachIndexed { index, (change, _) ->
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
    val actions = rememberReviewMutationActions(session, top)
    val number = head.label
    val method = overview.merge.defaultMethod
    Text(
        confirmText(change, number, overview.baseRef, t("overview.merge.method.$method")),
        Modifier.padding(start = 4.dp, end = 4.dp, top = 6.dp),
        style = PullsType.read, color = c.muted,
    )
    CtaButton(confirmLabel(change), {
        when (change) {
            "auto-merge" -> actions.pullRequest("auto-merge", method) { toaster.show("$number will merge when its checks pass"); onClose() }
            "update-branch" -> actions.pullRequest("update-branch", after = onClose)
            else -> {
                val inverse = mapOf("ready" to "draft", "draft" to "ready", "close" to "reopen", "reopen" to "close").getValue(change)
                val message = when (change) {
                    "ready" -> "$number is ready for review"
                    "draft" -> "Converted $number to draft"
                    "close" -> "Closed $number"
                    else -> "Reopened $number"
                }
                actions.pullRequest(change) { actions.showUndo(toaster, message, inverse); onClose() }
            }
        }
    }, Modifier.fillMaxWidth().padding(top = 20.dp), enabled = !actions.busy && actions.canPrAction(change, if (change == "auto-merge") method else null), container = if (change == "close") c.error else c.cta)
}

/** Every finding of the AI review, with its filters and the option to post them as a review. */
@Composable
fun FindingsSheet(session: ReviewSession, models: ModelSettingsServices, onClose: () -> Unit) {
    val actions = rememberAiSetupActions(session, models)
    var page by remember { mutableStateOf(FindingsPage.Main) }
    var lens by remember { mutableStateOf<String?>(null) }
    PullsPagedSheet(
        onClose, page,
        parent = { when (it) { FindingsPage.Main -> null; FindingsPage.Retry -> FindingsPage.Main; else -> FindingsPage.Retry } },
        onPage = { page = it }, full = true,
        title = { when (it) { FindingsPage.Main -> "Review findings"; FindingsPage.Retry -> "Review again"; FindingsPage.Model -> "Select model"; FindingsPage.Effort -> "Effort" } },
    ) { current ->
        when (current) {
            FindingsPage.Main -> CritiquePanel(session, Modifier.padding(top = 12.dp), onRetry = { previous -> lens = previous; page = FindingsPage.Retry })
            FindingsPage.Retry -> {
                ModelCard(session, models, { page = FindingsPage.Model }, onEffort = { page = FindingsPage.Effort }, gap = SheetTopGap)
                CtaButton("Run the review again", {
                    if (session.taskFor("critique")?.running != true) {
                        if (actions.critique(lens)) page = FindingsPage.Main
                    }
                }, Modifier.fillMaxWidth().padding(top = 16.dp), enabled = actions.canCritique && session.taskFor("critique")?.running != true)
            }
            FindingsPage.Model -> ModelPage(session.settings, models) { page = FindingsPage.Retry }
            FindingsPage.Effort -> EffortPage(session.settings, models) { page = FindingsPage.Retry }
        }
    }
}

private enum class FindingsPage { Main, Retry, Model, Effort }
