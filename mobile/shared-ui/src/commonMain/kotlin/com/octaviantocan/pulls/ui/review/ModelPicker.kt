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
import androidx.compose.runtime.key
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.octaviantocan.pulls.settings.AppSettings
import com.octaviantocan.pulls.data.ENGINES
import com.octaviantocan.pulls.data.Repo
import com.octaviantocan.pulls.ui.settings.ModelConfiguration
import com.octaviantocan.pulls.ui.settings.ModelSettingsServices
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
import com.octaviantocan.pulls.ui.design.FloatIcon

// --- The pages ---

/**
 * The engine tabs, the newest models and below them the older models under a small heading, as
 * Claude's picker has them (rule 89). A row is a name, with no id line and no description, a "Recommended" pill on the
 * CLI's own pick and a plain ✓ on the one in use. Pi, which lists many providers' models, shows them instead in a
 * card per provider under its name, with no "Older models" (K2, K3). Switching engine swaps the lists where they stand.
 * [onDone] runs once a model is chosen.
 */
@Composable
fun ColumnScope.ModelPage(settings: AppSettings, services: ModelSettingsServices, onDone: () -> Unit) {
    ModelPageOwner(services) { ModelPageBody(settings, services, onDone) }
}

/** Owns remembered input, focus, producer jobs and row callbacks as one page. */
@Composable
internal fun ModelPageOwner(services: ModelSettingsServices, content: @Composable () -> Unit) {
    key(services) { content() }
}

@Composable
private fun ColumnScope.ModelPageBody(settings: AppSettings, services: ModelSettingsServices, onDone: () -> Unit) {
    val c = LocalPullsColors.current
    val toaster = LocalToaster.current
    val messages = LocalMessages.current
    val page = rememberModelPageState(services)
    val config = page.config
    val engine = config.engine
    val loaded = page.loaded
    val catalog = loaded?.getOrNull()
    fun save(next: ModelConfiguration) { page.save(next) }
    // An id the server now lists under another one (Pi lists `claude-claude-opus-5` once, as `claude-opus-5`) ticks the row of the same name.
    val inUse = catalog?.let { AiSettingsLogic.current(it, config.model)?.id ?: sameNamed(it.models, config.model)?.id ?: config.model }
    // Pi mixes providers, so its list is in a section per provider with nothing folded under "Older models" (K2, K3).
    // A search over the names (and Pi's providers) once a list is long enough to need one ("most bottom sheets that we
    // have, they actually benefit a lot from search", recording 15 Oct 15:37, 12:26 to 12:36; L23).
    val query = page.query
    val searching = query.isNotBlank()
    val shownModels = catalog?.models?.filter { !searching || matchesSearch("${modelLabel(it)} ${it.id} ${it.group.orEmpty()}", query) }
    val sections = if (engine == "pi") shownModels?.let { modelSections(it) } else null
    val groups = if (sections == null) shownModels?.let { if (searching) ModelGroups(it, emptyList()) else groupModels(it) } else null
    fun choose(model: CliModel) {
        if (!services.current()) return
        val chosen = catalog ?: return
        val kept = AiSettingsLogic.keptEffort(chosen, model.id, settings.effort)
        services.remember(config.engine, model.id)
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
                if (!services.current()) return@CapsuleTabs
                val next = ENGINES[index]
                if (next != config.engine) {
                    services.remember(config.engine, config.model)
                    save(config.copy(engine = next, model = services.remembered(next).orEmpty()))
                    settings.updateEffort("")
                }
            },
            Modifier.fillMaxWidth(),
            leading = { index -> EngineMark(ENGINES[index], size = 20.dp) },
        )
    }
    if ((catalog?.models?.size ?: 0) > MODEL_SEARCH_FROM) {
        SheetSearch(query, { page.query = it }, "Find a model", Modifier.padding(top = com.octaviantocan.pulls.ui.design.SheetTopGap))
    }
    if (searching && shownModels.isNullOrEmpty()) {
        Text("No model matches “${query.trim()}”.", Modifier.fillMaxWidth().padding(vertical = 32.dp), style = PullsType.sm, color = c.muted, textAlign = TextAlign.Center)
    } else if (sections != null) {
        sections.forEachIndexed { index, section ->
            val heading = section.heading
            if (heading != null) ModelHeading(heading, first = index == 0)
            SheetCard(gap = if (heading != null) 8.dp else 12.dp) {
                section.models.forEachIndexed { row, model -> ModelRow(model, model.id == inUse, first = row == 0) { choose(model) } }
            }
        }
    } else SheetCard(Modifier.animateContentSize(tween(PullsMotion.FAST, easing = PullsMotion.SmoothOut))) {
        when {
            loaded == null -> ModelSkeleton()
            catalog == null || groups == null -> Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Couldn't load the model list", style = PullsType.row.copy(fontWeight = FontWeight.Medium), color = c.text)
                Text(loaded.exceptionOrNull()?.message.orEmpty(), style = PullsType.sm, color = c.muted)
                FloatIcon(Lu.redo, "Load the models again", { page.attempt++ }, flat = true, iconSize = 22.dp, tint = c.muted)
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
fun RecommendedPill() {
    val c = LocalPullsColors.current
    Box(Modifier.height(26.dp).clip(CircleShape).background(c.sunken).padding(horizontal = 10.dp), contentAlignment = Alignment.Center) {
        // raw-value-ok: rule 89 sets this pill at 13 to match the pill it was copied from
        Text("Recommended", style = PullsType.xs.copy(fontWeight = FontWeight.Medium), color = c.text, maxLines = 1)
    }
}

/** The efforts the chosen model supports; [onBack] runs once one is chosen. */
@Composable
fun ColumnScope.EffortPage(settings: AppSettings, services: ModelSettingsServices, onBack: () -> Unit) {
    val c = LocalPullsColors.current
    val messages = LocalMessages.current
    val config = remember(services) { services.configuration() }
    val catalog = services.cached(config.engine)
    val choice = AiSettingsLogic.current(catalog, config.model)
    val efforts = choice?.efforts.orEmpty()
    val current = settings.effort.ifBlank { choice?.defaultEffort.orEmpty() }
    SheetCard {
        efforts.forEachIndexed { index, id ->
            SheetRow(
                AiSettingsLogic.effortLabel(id) { messages.t(it) },
                { if (services.current()) { settings.updateEffort(if (id == choice?.defaultEffort) "" else id); onBack() } },
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
