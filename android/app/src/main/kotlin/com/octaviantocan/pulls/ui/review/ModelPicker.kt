package com.octaviantocan.pulls.ui.review

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
import com.octaviantocan.pulls.data.AppSettings
import com.octaviantocan.pulls.data.Repo
import com.octaviantocan.pulls.data.ServerConfig
import com.octaviantocan.pulls.data.ServerStore
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

/** The models a picker shows first, and the ones it lists below the Effort row under "Older models" (rule 89). */
internal data class ModelGroups(val recent: List<CliModel>, val older: List<CliModel>)

private val VERSION = Regex("""\b(\d+)(?:[.\-_](\d+))?\b""")

/**
 * The version a model's name carries as `major to minor` (`Opus 5.5`, `claude-opus-4-1`), else the version its
 * description gives the family it is named by ("Opus" with "Opus 5.5 · …"), or null for an alias such as "Default" or
 * "Opus (1M context)", which stands for whatever is newest. "1M" is not a version.
 */
internal fun modelVersion(model: CliModel): Pair<Int, Int>? {
    val found = VERSION.find(model.name) ?: VERSION.find(model.id) ?: describedName(model)?.let { VERSION.find(it) } ?: return null
    return found.groupValues[1].toInt() to (found.groupValues[2].toIntOrNull() ?: 0)
}

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

/** What a model is a version of: its first word, so "Opus 4.1" and "Opus 5.5" are one family and "GPT-5.5-Codex" and "GPT-5.5" share a version. */
private fun family(model: CliModel): String = Regex("[a-z]+").find(model.name.lowercase())?.value.orEmpty()

/**
 * Splits a CLI's model list the way Claude's own picker does: the newest version of each family from the newest generation
 * (for Claude Code the 5.5 models and Fable), the default, and the aliases come first; every other version goes
 * under "Older models". The CLI's own order is kept inside each group. A list with nothing older is all recent.
 */
internal fun groupModels(models: List<CliModel>): ModelGroups {
    val versions = models.associateWith { modelVersion(it) }
    val newestMajor = versions.values.filterNotNull().maxOfOrNull { it.first } ?: return ModelGroups(models, emptyList())
    val newestOfFamily = models.filter { versions[it] != null }
        .groupBy { family(it) }
        .mapValues { (_, members) -> members.maxOf { versions.getValue(it)!!.let { (major, minor) -> major * 1000 + minor } } }
    val (recent, older) = models.partition { model ->
        val version = versions[model]
        version == null || model.isDefault || (version.first == newestMajor && version.first * 1000 + version.second == newestOfFamily[family(model)])
    }
    return ModelGroups(recent, older)
}

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
internal fun modelLabel(model: CliModel): String =
    tidyHyphens((describedName(model) ?: model.name).replace(Regex("""\s*\(recommended\)""", RegexOption.IGNORE_CASE), "").trim())

/** The one line a row adds under its name, only for the default, whose name alone does not say which model it is. */
internal fun modelNote(model: CliModel): String? =
    if (model.id == "default") model.description?.substringBefore(" · ")?.trim()?.takeIf { it.isNotEmpty() } else null

// --- The pages ---

/**
 * The engine tabs, the newest models, a row to the effort and below it the older models under a small heading, as
 * Claude's picker has them (rule 89). A row is a name, with no id line and no description, a "Recommended" pill on the
 * CLI's own pick and a plain ✓ on the one in use. Switching engine swaps the lists where they stand. [onDone] runs
 * once a model is chosen.
 */
@Composable
internal fun ColumnScope.ModelPage(settings: AppSettings, onDone: () -> Unit, onEffort: () -> Unit) {
    val c = LocalPullsColors.current
    val context = LocalContext.current
    val toaster = LocalToaster.current
    val messages = LocalMessages.current
    val repo = remember { Repo(context) }
    val memory = remember { EngineModels(context) }
    var config by remember { mutableStateOf(ServerStore.load(context)) }
    var attempt by remember { mutableStateOf(0) }
    val engine = config.engine
    // The producer restarts with the engine, but only after a frame; a result is tagged with its engine and
    // ignored for any other, so a tap in that frame cannot save the old engine's model under the new one.
    val produced by produceState<Pair<String, Result<CliCatalog>>?>(ModelCatalogs.get(engine)?.let { engine to Result.success(it) }, engine, attempt) {
        val cached = if (attempt == 0) ModelCatalogs.get(engine) else null
        value = cached?.let { engine to Result.success(it) }
        if (cached == null) value = engine to fetchCatalog(repo, engine, refresh = attempt > 0)
    }
    val loaded = produced?.takeIf { it.first == engine }?.second
    val catalog = loaded?.getOrNull()
    fun save(next: ServerConfig) {
        config = next
        ServerStore.save(context, next)
    }
    val inUse = catalog?.let { AiSettingsLogic.current(it, config.model)?.id ?: config.model }
    val groups = catalog?.let { groupModels(it.models) }
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
            listOf("Claude Code", "Codex"),
            if (config.engine == "codex") 1 else 0,
            { index ->
                val next = if (index == 1) "codex" else "claude-code"
                if (next != config.engine) {
                    memory.setModel(config.engine, config.model)
                    save(config.copy(engine = next, model = memory.model(next).orEmpty()))
                    settings.updateEffort("")
                }
            },
            Modifier.fillMaxWidth(),
            leading = { index -> EngineMark(if (index == 1) "codex" else "claude-code", size = 20.dp) },
        )
    }
    SheetCard(Modifier.animateContentSize(tween(PullsMotion.FAST, easing = PullsMotion.SmoothOut))) {
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
    val choice = AiSettingsLogic.current(catalog, config.model)
    if (choice != null && choice.efforts.isNotEmpty()) {
        SheetCard {
            // The level that will run: the chosen one, else the one the CLI runs by default. Never the word "Default" (F33).
            val effort = settings.effort.ifBlank { choice.defaultEffort.orEmpty() }
            SheetRow("Effort", onEffort, first = true, iconless = true) {
                if (effort.isNotBlank()) Text(AiSettingsLogic.effortLabel(effort) { messages.t(it) }, style = PullsType.sm, color = c.muted)
                LuIcon(Lu.chev, tint = c.faint, size = 18.dp)
            }
        }
    }
    // The rest sit below the Effort row under a small heading, as in Claude's picker, and are always there to scroll to.
    if (groups != null && groups.older.isNotEmpty()) {
        Text("Older models", Modifier.padding(start = 16.dp, top = 20.dp), style = PullsType.sm.copy(fontWeight = FontWeight.Medium), color = c.muted)
        SheetCard {
            groups.older.forEachIndexed { index, model -> ModelRow(model, model.id == inUse, first = index == 0) { choose(model) } }
        }
    }
}

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
