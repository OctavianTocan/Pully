package com.octaviantocan.pulls.ui.review

import androidx.compose.ui.text.style.TextAlign
import com.octaviantocan.pulls.ui.design.matchesSearch
import com.octaviantocan.pulls.ui.design.SheetSearch
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.octaviantocan.pulls.settings.AppSettings
import com.octaviantocan.pulls.data.ENGINES
import com.octaviantocan.pulls.data.Repo
import com.octaviantocan.pulls.data.ServerConfig
import com.octaviantocan.pulls.data.ServerStore
import com.octaviantocan.pulls.data.engineName
import com.octaviantocan.pulls.sound.cueClickable
import com.octaviantocan.pulls.ui.design.CapsuleTabs
import com.octaviantocan.pulls.ui.design.EngineMark
import com.octaviantocan.pulls.ui.design.LocalMessages
import com.octaviantocan.pulls.ui.design.LocalPullsColors
import com.octaviantocan.pulls.ui.design.LocalToaster
import com.octaviantocan.pulls.ui.design.Lu
import com.octaviantocan.pulls.ui.design.LuIcon
import com.octaviantocan.pulls.ui.design.PullsMotion
import com.octaviantocan.pulls.ui.design.PullsType
import com.octaviantocan.pulls.ui.design.SheetCard
import com.octaviantocan.pulls.ui.design.SheetRow
import com.octaviantocan.pulls.ui.design.Skeleton
import com.octaviantocan.pulls.ui.design.t
import com.octaviantocan.pulls.ui.settings.AiSettingsLogic
import com.octaviantocan.pulls.ui.settings.CliCatalog
import com.octaviantocan.pulls.ui.settings.CliModel
import com.octaviantocan.pulls.ui.settings.EngineModels
import com.octaviantocan.pulls.ui.design.FloatIcon

// --- Which models to show first ---

/** The models a picker shows first, and the ones it lists below them under "Older models" (rule 89). */
internal data class ModelGroups(val recent: List<CliModel>, val older: List<CliModel>)

private val VERSION = Regex("""\b(\d+)(?:[.\-_](\d+))?\b""")

/**
 * The version a model's name carries as `major to minor` (`Opus 5.5`, `claude-opus-4-1`), else the version its
 * description gives the family it is named by ("Opus" with "Opus 5.5 · …"), or null for an alias such as "Default" or
 * "Opus (1M context)", which stands for whatever is newest. "1M" is not a version.
 */
internal fun modelVersion(model: CliModel): Pair<Int, Int>? {
    val found = versioned(model)?.second ?: return null
    return found.groupValues[1].toInt() to (found.groupValues[2].toIntOrNull() ?: 0)
}

/** The words a model's version is read from (its name, else its id, else its description's head) and the version in them. */
private fun versioned(model: CliModel): Pair<String, MatchResult>? =
    listOfNotNull(model.name, model.id, describedName(model)).firstNotNullOfOrNull { text -> VERSION.find(text)?.let { text to it } }

/**
 * The versioned name a model's description opens with when its own name is the family alone: Claude Code lists its
 * aliases as "Opus" with "Opus 5.5 · Best for everyday, complex tasks". Null when the name has a version already, or
 * the description opens with another model (the default's names the model it stands for).
 */
private fun describedName(model: CliModel): String? {
    if (VERSION.containsMatchIn(model.name)) return null
    val head = model.description?.substringBefore(" · ", "")?.trim().orEmpty()
    return head.takeIf { DESCRIBED.matches(it) && it.substringBefore(' ').equals(model.name.trim(), ignoreCase = true) }
}

private val DESCRIBED = Regex("""\S+ \d+(?:\.\d+)?""")

/** Words that name who makes a model, not which model it is: "GPT-6 Sol" is the Sol tier, "claude-opus-4-1" the Opus one. */
private val MAKERS = setOf("gpt", "claude")

private val WORD = Regex("[a-z][a-z0-9]*")

/**
 * The tier a model is a version of, the line a newer version replaces: the words before its version less the maker's
 * name, with the first word after it. "Opus 4.1" and "Opus 5.5" are the tier "opus"; "GPT-6.1 Sol" and "GPT-6 Sol" the
 * tier "sol", which "GPT-6 Astra" and "GPT-6 Luna" are not, so a lineup of tiers that share a version number keeps each
 * tier's newest (recording 16 Oct 12:10, 04:13 to 04:31; N7). "GPT-5.5-Codex" is "codex" and "GPT-5.5" has none.
 */
internal fun modelTier(model: CliModel): String {
    val (text, version) = versioned(model) ?: return ""
    val lower = text.lowercase()
    val before = WORD.findAll(lower.substring(0, version.range.first)).map { it.value }.filter { it !in MAKERS }
    val after = WORD.find(lower, version.range.last + 1)?.value
    return (before + listOfNotNull(after)).joinToString(" ")
}

/**
 * Splits a CLI's model list the way Claude's own picker does: the newest version of each tier ([modelTier]) from the
 * newest generation (for Claude Code the 5.5 models and Fable; for Codex GPT-6.1 Sol, GPT-6 Astra and GPT-6 Luna, N7),
 * the default, and the aliases come first; every other version goes under "Older models". The CLI's own order is kept
 * inside each group. A list with nothing older is all recent.
 */
internal fun groupModels(models: List<CliModel>): ModelGroups {
    val versions = models.associateWith { modelVersion(it) }
    val newestMajor = versions.values.filterNotNull().maxOfOrNull { it.first } ?: return ModelGroups(models, emptyList())
    val newestOfFamily = models.filter { versions[it] != null }
        .groupBy { modelTier(it) }
        .mapValues { (_, members) -> members.maxOf { versions.getValue(it)!!.let { (major, minor) -> major * 1000 + minor } } }
    val (recent, older) = models.partition { model ->
        val version = versions[model]
        version == null || model.isDefault ||
            (version.first == newestMajor && version.first * 1000 + version.second == newestOfFamily[modelTier(model)])
    }
    return ModelGroups(recent, older)
}

/** One provider's models on a list that mixes providers: [heading] is the provider ("AGY", "Claude"), null when the server names none. */
internal data class ModelSection(val heading: String?, val models: List<CliModel>)

/**
 * Pi's list in sections, one per provider the server groups it by (K2), in the server's order: the default's group
 * first, the rest by name, each newest first. Pi has no "Older models" (K3): every model the endpoint serves is a
 * current choice. A server that sends no groups gives one section with no heading.
 */
internal fun modelSections(models: List<CliModel>): List<ModelSection> =
    if (models.none { it.group != null }) listOf(ModelSection(null, models))
    else models.groupBy { it.group?.trim()?.ifEmpty { null } ?: "Other" }.map { (heading, members) -> ModelSection(heading, members) }

/** The hyphen between two words reads as a line in a name: `GPT-5.1-Codex-Max` is shown `GPT-5.1 Codex Max`. */
private fun tidyHyphens(name: String): String {
    val parts = name.split(Regex("""(?<=\S)-(?=\S)"""))
    if (parts.size < 2) return name
    return buildString {
        parts.forEachIndexed { index, part ->
            // "GPT-5": a word and its number keep the hyphen they are written with.
            if (index > 0) append(if (index == 1 && parts[0].all { it.isLetter() } && part.firstOrNull()?.isDigit() == true) '-' else ' ')
            append(if (part.equals("gpt", ignoreCase = true)) "GPT" else part)
        }
    }
}

/**
 * The name a row shows: the CLI's display name with its version ("Opus 5.5", from the description when the name is the
 * family alone), without "(recommended)", which the pill says, and without hyphens between words.
 */
internal fun modelLabel(model: CliModel): String {
    val name = (describedName(model) ?: model.name).replace(Regex("""\s*\(recommended\)""", RegexOption.IGNORE_CASE), "").trim()
    // A CLI that names a model by its id alone (`gpt-6-luna`) still gets a name on the row (H7); a name of its own
    // (`o3-mini`) is kept as the CLI wrote it.
    return if (name == model.id) prettyModelId(name) else tidyHyphens(name)
}

/**
 * A model id written as a name, for when no catalog names it: `gpt-6-luna` is `GPT-6 Luna`, `claude-opus-4-1` is
 * `Claude Opus 4.1`. A word and its number keep their hyphen, two numbers become a version, and words are capitalised.
 */
internal fun prettyModelId(id: String): String {
    // A route prefix that repeats the family ("claude-claude-opus-5", from a proxy's credential prefix) says it once (K3).
    val parts = id.trim().substringAfterLast('/').split('-', '_', ':').filter { it.isNotEmpty() }
        .let { words -> words.filterIndexed { index, word -> index == 0 || !word.equals(words[index - 1], ignoreCase = true) } }
    if (parts.isEmpty()) return id
    return buildString {
        parts.forEachIndexed { index, part ->
            val previous = parts.getOrNull(index - 1)
            if (index > 0) append(
                when {
                    previous!!.all { it.isDigit() } && part.all { it.isDigit() } -> '.'
                    index == 1 && previous.all { it.isLetter() } && part.first().isDigit() && previous.length <= 3 -> '-'
                    else -> ' '
                },
            )
            append(
                when {
                    part.equals("gpt", ignoreCase = true) -> "GPT"
                    part.equals("agy", ignoreCase = true) -> "AGY"
                    part.first().isLetter() -> part.replaceFirstChar { it.titlecase() }
                    else -> part
                },
            )
        }
    }
}

/** The row a saved id the list no longer carries reads as: the one whose name its id makes, if any. */
internal fun sameNamed(models: List<CliModel>, id: String): CliModel? =
    if (id.isBlank()) null else prettyModelId(id).let { name -> models.firstOrNull { modelLabel(it).equals(name, ignoreCase = true) } }

/** The one line a row adds under its name, only for the default, whose name alone does not say which model it is. */
internal fun modelNote(model: CliModel): String? =
    if (model.id == "default") model.description?.substringBefore(" · ")?.trim()?.takeIf { it.isNotEmpty() } else null

// --- The pages ---

/**
 * The engine tabs, the newest models and below them the older models under a small heading, as
 * Claude's picker has them (rule 89). A row is a name, with no id line and no description, a "Recommended" pill on the
 * CLI's own pick and a plain ✓ on the one in use. Pi, which lists many providers' models, shows them instead in a
 * card per provider under its name, with no "Older models" (K2, K3). Switching engine swaps the lists where they stand.
 * [onDone] runs once a model is chosen.
 */
@Composable
internal fun ColumnScope.ModelPage(settings: AppSettings, onDone: () -> Unit) {
    val c = LocalPullsColors.current
    val context = LocalContext.current
    val toaster = LocalToaster.current
    val messages = LocalMessages.current
    val repo = remember { Repo(context) }
    val memory = remember { EngineModels(context) }
    remember { ModelCatalogs.warm(context) }
    var config by remember { mutableStateOf(ServerStore.load(context)) }
    var attempt by remember { mutableStateOf(0) }
    val engine = config.engine
    // The producer restarts with the engine, but only after a frame; a result is tagged with its engine and
    // ignored for any other, so a tap in that frame cannot save the old engine's model under the new one.
    // A kept list shows at once and is refreshed underneath; only an engine never listed before waits, with bars.
    val produced by produceState<Pair<String, Result<CliCatalog>>?>(ModelCatalogs.get(engine)?.let { engine to Result.success(it) }, engine, attempt) {
        val cached = if (attempt == 0) ModelCatalogs.get(engine) else null
        value = cached?.let { engine to Result.success(it) }
        val fresh = fetchCatalog(repo, engine, refresh = attempt > 0)
        if (cached == null || fresh.isSuccess) value = engine to fresh
    }
    // The other engines' lists are fetched too, so switching tab finds names there as well.
    LaunchedEffect(Unit) { ENGINES.filter { it != engine && ModelCatalogs.get(it) == null }.forEach { fetchCatalog(repo, it) } }
    val loaded = produced?.takeIf { it.first == engine }?.second
    val catalog = loaded?.getOrNull()
    fun save(next: ServerConfig) {
        config = next
        ServerStore.save(context, next)
    }
    // An id the server now lists under another one (Pi lists `claude-claude-opus-5` once, as `claude-opus-5`) ticks the row of the same name.
    val inUse = catalog?.let { AiSettingsLogic.current(it, config.model)?.id ?: sameNamed(it.models, config.model)?.id ?: config.model }
    // Pi mixes providers, so its list is in a section per provider with nothing folded under "Older models" (K2, K3).
    // A search over the names (and Pi's providers) once a list is long enough to need one ("most bottom sheets that we
    // have, they actually benefit a lot from search", recording 15 Oct 15:37, 12:26 to 12:36; L23).
    var query by remember(engine) { mutableStateOf("") }
    val searching = query.isNotBlank()
    val shownModels = catalog?.models?.filter { !searching || matchesSearch("${modelLabel(it)} ${it.id} ${it.group.orEmpty()}", query) }
    val sections = if (engine == "pi") shownModels?.let { modelSections(it) } else null
    val groups = if (sections == null) shownModels?.let { if (searching) ModelGroups(it, emptyList()) else groupModels(it) } else null
    fun choose(model: CliModel) {
        val chosen = catalog ?: return
        val kept = AiSettingsLogic.keptEffort(chosen, model.id, settings.effort)
        memory.setModel(config.engine, model.id)
        save(config.copy(model = model.id))
        settings.updateEffort(kept)
        onDone()
        toaster.show("Analysis model: ${analysisModelLabel(config.copy(model = model.id), chosen, kept, messages)}")
    }

    Box(Modifier.padding(top = 14.dp)) {
        CapsuleTabs(
            // The maker's name alone fits a third of the sheet beside its mark; Settings names the engine in full.
            ENGINES.map { if (it == "claude-code") "Claude" else engineName(it) },
            ENGINES.indexOf(config.engine).coerceAtLeast(0),
            { index ->
                val next = ENGINES[index]
                if (next != config.engine) {
                    memory.setModel(config.engine, config.model)
                    save(config.copy(engine = next, model = memory.model(next).orEmpty()))
                    settings.updateEffort("")
                }
            },
            Modifier.fillMaxWidth(),
            leading = { index -> EngineMark(ENGINES[index], size = 20.dp) },
        )
    }
    if ((catalog?.models?.size ?: 0) > MODEL_SEARCH_FROM) {
        SheetSearch(query, { query = it }, "Find a model", Modifier.padding(top = com.octaviantocan.pulls.ui.design.SheetTopGap))
    }
    if (searching && shownModels.isNullOrEmpty()) {
        Text("No model matches “${query.trim()}”.", Modifier.fillMaxWidth().padding(vertical = 32.dp), style = PullsType.sm, color = c.muted, textAlign = TextAlign.Center)
    } else if (sections != null) {
        sections.forEachIndexed { index, section ->
            if (section.heading != null) ModelHeading(section.heading, first = index == 0)
            SheetCard(gap = if (section.heading != null) 8.dp else 12.dp) {
                section.models.forEachIndexed { row, model -> ModelRow(model, model.id == inUse, first = row == 0) { choose(model) } }
            }
        }
    } else SheetCard(Modifier.animateContentSize(tween(PullsMotion.FAST, easing = PullsMotion.SmoothOut))) {
        when {
            loaded == null -> ModelSkeleton()
            catalog == null || groups == null -> Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Couldn't load the model list", style = PullsType.row.copy(fontWeight = FontWeight.Medium), color = c.text)
                Text(loaded.exceptionOrNull()?.message.orEmpty(), style = PullsType.sm, color = c.muted)
                FloatIcon(Lu.redo, "Load the models again", { attempt++ }, flat = true, iconSize = 22.dp, tint = c.muted)
            }
            else -> groups.recent.forEachIndexed { index, model -> ModelRow(model, model.id == inUse, first = index == 0) { choose(model) } }
        }
    }
    // The rest sit under a small heading, as in Claude's picker, and are always there to scroll to. The effort is not
    // here: it is a row under Model wherever the model is shown (M2).
    if (groups != null && groups.older.isNotEmpty()) {
        ModelHeading("Older models")
        SheetCard {
            groups.older.forEachIndexed { index, model -> ModelRow(model, model.id == inUse, first = index == 0) { choose(model) } }
        }
    }
}

/** Above this many models the picker has a search. */
private const val MODEL_SEARCH_FROM = 8

/** The small heading over a card of models: "Older models" on the CLIs' lists, a provider on Pi's. */
@Composable
private fun ModelHeading(text: String, first: Boolean = false) = com.octaviantocan.pulls.ui.design.SheetHeading(text, first = first)

/** Where the model rows will be while the list is on its way: bars the length of names, not a spinner. */
@Composable
private fun ModelSkeleton() {
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(0.dp)) {
        listOf(0.42f, 0.34f, 0.5f, 0.38f).forEach { width ->
            Box(Modifier.fillMaxWidth().defaultMinSize(minHeight = 56.dp).padding(horizontal = 16.dp), contentAlignment = Alignment.CenterStart) {
                Skeleton(Modifier.fillMaxWidth(width))
            }
        }
    }
}

private fun Modifier.ruleAbove(show: Boolean, color: Color): Modifier =
    if (show) drawBehind { drawRect(color, Offset(16.dp.toPx(), 0f), Size(size.width - 16.dp.toPx(), 1.dp.toPx())) } else this

/** A model: its name, "Recommended" on the CLI's own pick, and a plain ✓ on the one in use. */
@Composable
private fun ModelRow(model: CliModel, selected: Boolean, first: Boolean, onClick: () -> Unit) {
    val c = LocalPullsColors.current
    val note = modelNote(model)
    Row(
        Modifier.fillMaxWidth().defaultMinSize(minHeight = 56.dp).ruleAbove(!first, c.borderMute)
            .cueClickable(role = Role.RadioButton, onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(modelLabel(model), Modifier.weight(1f, fill = false), style = PullsType.row, color = c.text)
                if (model.isDefault) RecommendedPill()
            }
            if (note != null) Text(note, style = PullsType.sm, color = c.muted)
        }
        Tick(selected)
    }
}

/** The ✓ of a chosen row: plain, in the text colour, in a slot of its own so a row never shifts when it appears. */
@Composable
private fun Tick(shown: Boolean) {
    Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
        if (shown) LuIcon(Lu.check, tint = LocalPullsColors.current.text, size = 24.dp)
    }
}

/** `Recommended`: the pill on the choice a CLI makes for you, 26dp tall, 13sp medium, a neutral grey with dark text, as Claude's is (rule 89). */
@Composable
internal fun RecommendedPill() {
    val c = LocalPullsColors.current
    Box(Modifier.height(26.dp).clip(CircleShape).background(c.sunken).padding(horizontal = 10.dp), contentAlignment = Alignment.Center) {
        // raw-value-ok: rule 89 sets this pill at 13 to match the pill it was copied from
        Text("Recommended", style = PullsType.xs.copy(fontWeight = FontWeight.Medium), color = c.text, maxLines = 1)
    }
}

/** The efforts the chosen model supports; [onBack] runs once one is chosen. */
@Composable
internal fun ColumnScope.EffortPage(settings: AppSettings, onBack: () -> Unit) {
    val c = LocalPullsColors.current
    val context = LocalContext.current
    val messages = LocalMessages.current
    val config = remember { ServerStore.load(context) }
    remember { ModelCatalogs.warm(context) }
    val catalog = ModelCatalogs.get(config.engine)
    val choice = AiSettingsLogic.current(catalog, config.model)
    val efforts = choice?.efforts.orEmpty()
    val current = settings.effort.ifBlank { choice?.defaultEffort.orEmpty() }
    SheetCard {
        efforts.forEachIndexed { index, id ->
            SheetRow(
                AiSettingsLogic.effortLabel(id) { messages.t(it) },
                { settings.updateEffort(if (id == choice?.defaultEffort) "" else id); onBack() },
                first = index == 0, iconless = true,
            ) {
                if (id == choice?.defaultEffort) RecommendedPill()
                Tick(id == current)
            }
        }
    }
    Text(
        "Higher effort means a more thorough analysis, but it takes longer and uses your limits faster.",
        Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp), style = PullsType.xs.copy(lineHeight = PullsType.xs.fontSize * 1.4f), color = c.muted, // raw-value-ok: rule 89 sets the footnote under a list tiny, as Claude's is
    )
}
