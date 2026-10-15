package com.octaviantocan.pulls.ui.review

import androidx.compose.foundation.layout.Arrangement
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
 * `#sheet`: Checks, GitHub and Analyze again as tiles; the analysis model; Approve and Request changes;
 * the merge; draft and close; and the few AI actions the prototype did not draw. There is no Refresh: the page takes
 * new commits by itself.
 */
@Composable
internal fun MoreSheet(session: ReviewSession, top: ReviewTopUi, data: PrOverviewData?, head: PrHead, onClose: () -> Unit, onSheet: (String) -> Unit) {
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
    val number = head.label
    val blockedText = t("overview.merge.blocked.${data?.mergeability?.blocker ?: "blocked"}")

    PullsSheet(onClose, title = { PrTitle(head) }, expandable = true) {
        Row(Modifier.fillMaxWidth().padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            QuickAction("Checks", { onSheet("checks") }, Modifier.weight(1f)) {
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
            SheetRow("Model", { onSheet("model") }, first = true, icon = { EngineMark(config.engine, size = 22.dp) }) {
                Text(analysisModelLabel(config, catalog, settings.effort, messages), Modifier.weight(1f, fill = false), style = PullsType.sm, color = c.muted)
                LuIcon(Lu.chev, tint = c.faint, size = 18.dp)
            }
            if (session.key != null && cliEngine(context)) {
                SheetRow("Analyze options", { onSheet("analyze") }, iconless = true) {
                    Text(settings.analyzeChoice.title, style = PullsType.sm, color = c.muted, maxLines = 1)
                    LuIcon(Lu.chev, tint = c.faint, size = 18.dp)
                }
            }
        }

        // The verdicts are words: a speech bubble on them carried no fact (rule 48).
        if (canReview && (pending > 0 || head.isOpen)) SheetCard {
            if (pending > 0) SheetRow("Submit comments", { top.reviewVerdict = "COMMENT"; top.reviewOpen = true; onClose() }, first = true, iconless = true) {
                Text("$pending comment${if (pending == 1) "" else "s"}", style = PullsType.sm, color = c.muted)
            }
            if (head.isOpen) {
                if (pending == 0) SheetRow("Leave a comment", { top.reviewVerdict = "COMMENT"; top.reviewOpen = true; onClose() }, first = true, iconless = true)
                SheetRow("Approve", { top.reviewVerdict = "APPROVE"; top.reviewOpen = true; onClose() }, iconless = true)
                SheetRow("Request changes", { top.reviewVerdict = "REQUEST_CHANGES"; top.reviewOpen = true; onClose() }, iconless = true) {
                    if (pending > 0) Text("$pending comment${if (pending == 1) "" else "s"}", style = PullsType.sm, color = c.muted)
                }
            }
        }

        val mergeability = data?.mergeability
        if (overview != null && overview.isOpen && mergeability != null && mergeability.allowed) SheetCard {
            SheetRow(
                t("overview.merge.method.${overview.merge.defaultMethod}"),
                {
                    if (!mergeability.ready) {
                        toaster.show(if (failing > 0) "$failing check${if (failing == 1) "" else "s"} failing · merge is blocked" else blockedText, cue = Cue.Error)
                    } else {
                        top.mergeOpen = true
                        onClose()
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
            // Merging later is the row under merging now (rule 29): on while it waits, off again from the same row. Its label
            // lines up under the merge row's, and the row and its switch do the same thing. It follows the blocker, not
            // `ready`: an admin who could bypass protection may still choose to wait for it.
            val method = overview.merge.defaultMethod
            val autoMerge: (() -> Unit)? = when {
                overview.autoMergeMethod != null && overview.viewer.canDisableAutoMerge -> {
                    {
                        onClose()
                        top.run(session, "cancel-auto-merge", onOk = { toaster.show("$number will not merge by itself") }) {
                            session.repo.prAction(overview.id, "cancel-auto-merge")
                        }
                    }
                }
                overview.viewer.canEnableAutoMerge && mergeability.blocker in AUTO_MERGE_WAITS -> {
                    {
                        onClose()
                        top.run(session, "auto-merge", onOk = { toaster.show("$number will merge when its checks pass") }) {
                            session.repo.prAction(overview.id, "auto-merge", method = method, expectedHeadOid = overview.headOid)
                        }
                    }
                }
                else -> null
            }
            if (autoMerge != null) SheetRow("Merge when checks pass", autoMerge) {
                PullsSwitch(overview.autoMergeMethod != null, { autoMerge() }, "Merge when checks pass", Modifier.clearAndSetSemantics {})
            }
            if (overview.viewer.canUpdateBranch) {
                SheetRow(t("overview.actions.updateBranch"), {
                    onClose()
                    top.run(session, "update-branch") { session.repo.prAction(overview.id, "update-branch", expectedHeadOid = overview.headOid) }
                }, icon = { LuIcon(Lu.branch, tint = c.text) })
            }
        }

        val actions = overview?.let { stateActions(it) }.orEmpty()
        if (overview != null && actions.isNotEmpty()) SheetCard {
            actions.forEachIndexed { index, (change, _) ->
                SheetRow(t("overview.actions.$change"), {
                    onClose()
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
                }, first = index == 0, danger = change == "close", icon = { LuIcon(stateGlyph(change), tint = if (change == "close") c.error else c.text) })
            }
        }

        SheetCard {
            var first = true
            if (session.key != null && cliEngine(context)) {
                // One row: the Review sheet picks General or a lens, and a tap there starts the review.
                SheetRow("Review with AI", { onSheet("review") }, first = true, icon = { LuIcon(Lu.check, tint = c.text) }) {
                    LuIcon(Lu.chev, tint = c.faint, size = 18.dp)
                }
                first = false
            }
            if (session.files.isNotEmpty()) {
                // Its own glyph, so the card's icon column stays whole and no glyph repeats (rule 48).
                SheetRow(t("reviewProgress.title"), { top.progressOpen = true; onClose() }, first = first, icon = { LuIcon(Lu.okc, tint = c.text, size = 20.dp) }) {
                    LuIcon(Lu.chev, tint = c.faint, size = 18.dp)
                }
                first = false
            }
            SheetRow("Commands", { onClose(); com.octaviantocan.pulls.ui.Commands.open() }, first = first, icon = { LuIcon(Lu.search, tint = c.text, size = 20.dp) }) {
                LuIcon(Lu.chev, tint = c.faint, size = 18.dp)
            }
        }
    }
}

/** Every finding of the AI review, with its filters and the option to post them as a review. */
@Composable
internal fun FindingsSheet(session: ReviewSession, onClose: () -> Unit) {
    PullsSheet(onClose, "Review findings", full = true) { CritiquePanel(session, Modifier.padding(top = 12.dp)) }
}
