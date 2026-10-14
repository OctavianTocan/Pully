package com.octaviantocan.pulls.ui.review

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import com.octaviantocan.pulls.R
import com.octaviantocan.pulls.data.Repo
import com.octaviantocan.pulls.data.ServerConfig
import com.octaviantocan.pulls.data.ServerStore
import com.octaviantocan.pulls.ui.ai.CritiquePanel
import com.octaviantocan.pulls.ui.ai.ShareButton
import com.octaviantocan.pulls.ui.ai.canShare
import com.octaviantocan.pulls.ui.ai.cliEngine
import com.octaviantocan.pulls.ui.ai.runAnalyze
import com.octaviantocan.pulls.ui.ai.runCritique
import com.octaviantocan.pulls.ui.design.CapsuleTabs
import com.octaviantocan.pulls.ui.design.LocalMessages
import com.octaviantocan.pulls.ui.design.LocalPullsColors
import com.octaviantocan.pulls.ui.design.LocalToaster
import com.octaviantocan.pulls.ui.design.Lu
import com.octaviantocan.pulls.ui.design.LuGlyph
import com.octaviantocan.pulls.ui.design.LuIcon
import com.octaviantocan.pulls.ui.design.Messages
import com.octaviantocan.pulls.ui.design.PhIcon
import com.octaviantocan.pulls.ui.design.PullsFonts
import com.octaviantocan.pulls.ui.design.PullsSheet
import com.octaviantocan.pulls.ui.design.PullsType
import com.octaviantocan.pulls.ui.design.QuickAction
import com.octaviantocan.pulls.ui.design.SheetCard
import com.octaviantocan.pulls.ui.design.SheetRow
import com.octaviantocan.pulls.ui.design.t
import com.octaviantocan.pulls.ui.home.DrawableIcon
import com.octaviantocan.pulls.ui.home.Spinner
import com.octaviantocan.pulls.ui.home.StatusColors
import com.octaviantocan.pulls.ui.settings.AiSettingsLogic
import com.octaviantocan.pulls.ui.settings.CliCatalog
import com.octaviantocan.pulls.ui.settings.CliModel
import com.octaviantocan.pulls.ui.settings.EngineModels
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
    val name = model?.name ?: config.model.ifBlank { "Default" }
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

/**
 * `#sheet`: Checks, GitHub and Analyze again as tiles; the analysis model; Approve and Request changes;
 * the merge; draft and close; and the few AI and refresh actions the prototype did not draw.
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

    PullsSheet(onClose, title = { PrTitle(head) }) {
        Row(Modifier.fillMaxWidth().padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            QuickAction("Checks", { onSheet("checks") }, Modifier.weight(1f)) {
                if (failing > 0) LuIcon(Lu.fail, tint = StatusColors.red(c.dark), size = 22.dp)
                else LuIcon(Lu.okc, tint = if ((overview?.checksSummary?.total ?: 0) > 0) StatusColors.green(c.dark) else c.muted, size = 22.dp, strokeWidth = 1.8f)
            }
            QuickAction("GitHub", { if (head.url.isNotBlank()) uri.openUri(head.url) }, Modifier.weight(1f)) { DrawableIcon(R.drawable.ic_carbon_logo_github, tint = c.text, size = 22.dp) }
            QuickAction("Analyze again", {
                onClose()
                if (session.taskFor("analyze")?.running == true) {
                    toaster.show("Already analyzing")
                } else {
                    session.runAnalyze()
                    toaster.show("Analyzing again with ${analysisModelLabel(config, catalog, settings.effort, messages)}")
                }
            }, Modifier.weight(1f)) { LuIcon(Lu.redo, tint = c.text, size = 22.dp) }
        }

        SheetCard {
            SheetRow("Model", { onSheet("model") }, first = true, icon = { LuIcon(Lu.bot, tint = c.activeText) }) {
                Text(analysisModelLabel(config, catalog, settings.effort, messages), Modifier.weight(1f, fill = false), style = PullsType.sm, color = c.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                LuIcon(Lu.chev, tint = c.faint, size = 18.dp)
            }
        }

        if (canReview && (pending > 0 || head.isOpen)) SheetCard {
            if (pending > 0) SheetRow("Submit comments", { top.reviewVerdict = "COMMENT"; top.reviewOpen = true; onClose() }, first = true, icon = { LuIcon(Lu.ask, tint = c.text) }) {
                Text("$pending comment${if (pending == 1) "" else "s"}", style = PullsType.sm, color = c.muted)
            }
            if (head.isOpen) {
                if (pending == 0) SheetRow("Leave a comment", { top.reviewVerdict = "COMMENT"; top.reviewOpen = true; onClose() }, first = true, icon = { LuIcon(Lu.ask, tint = c.text) })
                SheetRow("Approve", { top.reviewVerdict = "APPROVE"; top.reviewOpen = true; onClose() }, icon = { LuIcon(Lu.check, tint = c.text) })
                SheetRow("Request changes", { top.reviewVerdict = "REQUEST_CHANGES"; top.reviewOpen = true; onClose() }, icon = { LuIcon(Lu.req, tint = c.text) }) {
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
                        toaster.show(if (failing > 0) "$failing check${if (failing == 1) "" else "s"} failing · merge is blocked" else blockedText)
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
            if (overview.viewer.canUpdateBranch) {
                SheetRow(t("overview.actions.updateBranch"), {
                    onClose()
                    top.run(session, "update-branch") { session.repo.prAction(overview.id, "update-branch", expectedHeadOid = overview.headOid) }
                }, icon = { PhIcon("arrows-merge-duotone", tint = c.text, size = 20.dp) })
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
                SheetRow("Review with AI", {
                    onClose()
                    if (session.taskFor("critique")?.running == true) {
                        toaster.show("Already reviewing $number")
                    } else {
                        session.runCritique(context, null)
                        toaster.show("Reviewing $number")
                    }
                }, first = true, icon = { PhIcon("magnifying-glass-duotone", tint = c.text, size = 20.dp) })
                SheetRow("Review through a lens", { onSheet("lens") }, icon = { PhIcon("lightbulb-duotone", tint = c.text, size = 20.dp) }) {
                    LuIcon(Lu.chev, tint = c.faint, size = 18.dp)
                }
                first = false
            }
            if (canShare(session)) {
                Box(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) { ShareButton(session) }
                first = false
            }
            SheetRow("Refresh", { onClose(); top.polls = 0; session.refresh() }, first = first, icon = { PhIcon("arrows-clockwise-duotone", tint = c.text, size = 20.dp) })
        }
    }
}

/** `PrChecks` in a sheet. */
@Composable
internal fun ChecksSheet(overview: PrOverview, onClose: () -> Unit) {
    PullsSheet(onClose, "Checks") {
        SheetCard { Column(Modifier.padding(12.dp)) { Checks(overview.checks, overview.checksSummary, overview.url) } }
    }
}

/** The pull request's commits: tap one to see only its changes, long-press to extend the range. */
@Composable
internal fun CommitsSheet(session: ReviewSession, overview: PrOverview, onClose: () -> Unit) {
    val c = LocalPullsColors.current
    val selection = session.selection?.let { CommitSelection(it.first, it.second) }
    PullsSheet(onClose, "Commits") {
        SheetCard {
            SheetRow("All commits", { session.selectCommits(null); onClose() }, first = true, iconless = true) {
                Text("${overview.commits.size + overview.commitsHidden}", style = PullsType.sm.copy(fontFamily = PullsType.mono), color = c.muted)
                if (selection == null) LuIcon(Lu.check, tint = c.accentTeal, size = 20.dp)
            }
            Box(Modifier.padding(horizontal = 8.dp, vertical = 6.dp)) {
                CommitList(
                    commits = overview.commits, hidden = overview.commitsHidden, url = overview.url, selection = selection,
                    onSelect = { picked, extend -> session.selectCommits(picked?.pair); if (!extend) onClose() },
                )
            }
        }
    }
}

/** Every finding of the AI review, with its filters and the option to post them as a review. */
@Composable
internal fun FindingsSheet(session: ReviewSession, onClose: () -> Unit) {
    PullsSheet(onClose, "Review findings", full = true) { CritiquePanel(session, Modifier.padding(top = 12.dp)) }
}

// --- The analysis model ---

/** `#modelsheet`: the engine, the model list with its ids and descriptions, and a row to the effort. */
@Composable
internal fun ModelSheet(session: ReviewSession, onClose: () -> Unit, onEffort: () -> Unit) {
    val c = LocalPullsColors.current
    val context = LocalContext.current
    val toaster = LocalToaster.current
    val messages = LocalMessages.current
    val settings = session.settings
    val repo = remember { Repo(context) }
    val memory = remember { EngineModels(context) }
    var config by remember { mutableStateOf(ServerStore.load(context)) }
    var attempt by remember { mutableStateOf(0) }
    // Restarts with the engine, so the producer starts from whatever the new engine has cached, never the old engine's list.
    val loaded by produceState<Result<CliCatalog>?>(ModelCatalogs.get(config.engine)?.let { Result.success(it) }, config.engine, attempt) {
        val cached = if (attempt == 0) ModelCatalogs.get(config.engine) else null
        value = cached?.let { Result.success(it) }
        if (cached == null) value = fetchCatalog(repo, config.engine, refresh = attempt > 0)
    }
    val catalog = loaded?.getOrNull()
    fun save(next: ServerConfig) {
        config = next
        ServerStore.save(context, next)
    }

    PullsSheet(onClose, "Select model") {
        Box(Modifier.padding(top = 14.dp)) {
            CapsuleTabs(
                listOf("Claude Code", "Codex"),
                if (config.engine == "codex") 1 else 0,
                { index ->
                    val engine = if (index == 1) "codex" else "claude-code"
                    if (engine != config.engine) {
                        memory.setModel(config.engine, config.model)
                        save(config.copy(engine = engine, model = memory.model(engine).orEmpty()))
                        settings.updateEffort("")
                    }
                },
                Modifier.fillMaxWidth(),
                flat = true,
            )
        }
        SheetCard {
            when {
                loaded == null -> Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Spinner(16.dp)
                    Text("Loading models…", style = PullsType.sm, color = c.muted)
                }
                catalog == null -> Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Couldn't load the model list", style = PullsType.row.copy(fontWeight = FontWeight.Medium), color = c.text)
                    Text(loaded?.exceptionOrNull()?.message.orEmpty(), style = PullsType.sm, color = c.muted)
                    com.octaviantocan.pulls.ui.design.ActionButton({ attempt++ }, small = true) { Text(t("common.retry")) }
                }
                else -> catalog.models.forEachIndexed { index, model ->
                    val selected = model.id == (AiSettingsLogic.current(catalog, config.model)?.id ?: config.model)
                    ModelRow(model, selected, first = index == 0) {
                        val kept = AiSettingsLogic.keptEffort(catalog, model.id, settings.effort)
                        memory.setModel(config.engine, model.id)
                        save(config.copy(model = model.id))
                        settings.updateEffort(kept)
                        onClose()
                        toaster.show("Analysis model: ${analysisModelLabel(config.copy(model = model.id), catalog, kept, messages)}")
                    }
                }
            }
        }
        val choice = AiSettingsLogic.current(catalog, config.model)
        if (choice != null && choice.efforts.isNotEmpty()) {
            SheetCard {
                val effort = settings.effort.ifBlank { choice.defaultEffort.orEmpty() }
                SheetRow("Effort", onEffort, first = true, iconless = true) {
                    Text(if (effort.isBlank()) "Default" else AiSettingsLogic.effortLabel(effort) { messages.t(it) }, style = PullsType.sm, color = c.muted)
                    LuIcon(Lu.chev, tint = c.faint, size = 18.dp)
                }
            }
        }
        Text(
            if (config.engine == "codex") "Used for the analysis, summary and tour of every pull request. Runs through the Codex CLI."
            else "Used for the analysis, summary and tour of every pull request. Default lets Claude Code choose.",
            Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp), style = PullsType.sm.copy(lineHeight = PullsType.sm.fontSize * 1.45f), color = c.muted,
        )
    }
}

@Composable
private fun ModelRow(model: CliModel, selected: Boolean, first: Boolean, onClick: () -> Unit) {
    val c = LocalPullsColors.current
    Row(
        Modifier.fillMaxWidth()
            .drawBehind { if (!first) drawRect(c.borderMute, Offset(16.dp.toPx(), 0f), Size(size.width - 16.dp.toPx(), 1.dp.toPx())) }
            .clickable(role = Role.RadioButton, onClick = onClick).padding(horizontal = 16.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(model.name, Modifier.weight(1f, fill = false), style = PullsType.row, color = c.text, maxLines = 2)
                if (model.isDefault && !model.name.startsWith("default", ignoreCase = true)) DefaultBadge()
            }
            if (model.id != "default" && model.name.lowercase() != model.id) {
                Text(model.id, style = PullsType.xs.copy(fontFamily = PullsType.mono, lineHeight = PullsType.xs.fontSize * 1.4f), color = c.muted)
            }
            model.description?.let { Text(it, style = PullsType.sm, color = c.muted) }
        }
        Box(Modifier.padding(start = 4.dp)) { if (selected) LuIcon(Lu.check, tint = c.accentTeal, size = 20.dp) }
    }
}

@Composable
private fun DefaultBadge() {
    val c = LocalPullsColors.current
    Text(
        "Default",
        Modifier.clip(RoundedCornerShape(50)).background(c.paper2).padding(horizontal = 8.dp),
        style = PullsType.micro.copy(fontWeight = FontWeight.Medium, letterSpacing = 0.01.em),
        color = c.muted, maxLines = 1,
    )
}

/** `#effortsheet`: the efforts the chosen model supports. */
@Composable
internal fun EffortSheet(session: ReviewSession, onBack: () -> Unit, onClose: () -> Unit) {
    val c = LocalPullsColors.current
    val context = LocalContext.current
    val messages = LocalMessages.current
    val settings = session.settings
    val config = remember { ServerStore.load(context) }
    val catalog = ModelCatalogs.get(config.engine)
    val choice = AiSettingsLogic.current(catalog, config.model)
    val efforts = choice?.efforts.orEmpty()
    val current = settings.effort.ifBlank { choice?.defaultEffort.orEmpty() }
    PullsSheet(onClose, "Effort", back = onBack) {
        SheetCard {
            efforts.forEachIndexed { index, id ->
                SheetRow(
                    AiSettingsLogic.effortLabel(id) { messages.t(it) },
                    { settings.updateEffort(if (id == choice?.defaultEffort) "" else id); onBack() },
                    first = index == 0, iconless = true,
                ) {
                    if (id == choice?.defaultEffort) DefaultBadge()
                    if (id == current) LuIcon(Lu.check, tint = c.accentTeal, size = 20.dp)
                }
            }
        }
        Text(
            "Higher effort means a more thorough analysis, but it takes longer and uses your limits faster.",
            Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp), style = PullsType.sm.copy(lineHeight = PullsType.sm.fontSize * 1.45f), color = c.muted,
        )
    }
}
