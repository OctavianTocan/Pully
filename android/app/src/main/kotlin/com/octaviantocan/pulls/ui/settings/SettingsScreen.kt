package com.octaviantocan.pulls.ui.settings

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import kotlin.math.roundToInt
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.octaviantocan.pulls.R
import com.octaviantocan.pulls.data.AppSettings
import com.octaviantocan.pulls.data.Repo
import com.octaviantocan.pulls.data.ServerConfig
import com.octaviantocan.pulls.data.ServerStore
import com.octaviantocan.pulls.ui.Nav
import com.octaviantocan.pulls.ui.design.ActionButton
import com.octaviantocan.pulls.ui.design.ButtonVariant
import com.octaviantocan.pulls.ui.design.IconButton
import com.octaviantocan.pulls.ui.design.LocalPullsColors
import com.octaviantocan.pulls.ui.design.PhIcon
import com.octaviantocan.pulls.ui.design.PullsType
import com.octaviantocan.pulls.ui.design.TOUCH
import com.octaviantocan.pulls.ui.design.TextInput
import com.octaviantocan.pulls.ui.design.ToggleGroup
import com.octaviantocan.pulls.ui.design.t
import com.octaviantocan.pulls.ui.home.DrawableIcon
import com.octaviantocan.pulls.ui.home.NavControls
import com.octaviantocan.pulls.ui.home.Spinner
import com.octaviantocan.pulls.ui.home.bottomBorder
import com.octaviantocan.pulls.ui.home.pressTint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** A model a CLI reports (`CliModel`). */
data class CliModel(val id: String, val name: String, val description: String?, val efforts: List<String>, val defaultEffort: String?, val isDefault: Boolean)

/** `CliModelCatalog`. */
data class CliCatalog(val models: List<CliModel>, val account: String?, val version: String?) {
    /** `cliLine`: account and version joined with ` · `. */
    val line: String get() = listOfNotNull(account?.ifBlank { null }, version?.ifBlank { null }).joinToString(" · ")
}

fun JsonObject.asCatalog(): CliCatalog {
    fun JsonObject.s(key: String) = (this[key] as? JsonPrimitive)?.contentOrNull
    return CliCatalog(
        models = (this["models"] as? JsonArray).orEmpty().mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            CliModel(
                id = o.s("id").orEmpty(),
                name = o.s("name") ?: o.s("id").orEmpty(),
                description = o.s("description")?.ifBlank { null },
                efforts = (o["efforts"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull },
                defaultEffort = o.s("defaultEffort")?.ifBlank { null },
                isDefault = o.s("isDefault") == "true",
            )
        },
        account = s("account"),
        version = s("version"),
    )
}

/** Settings logic shared with tests: `keptEffort` and `effortLabel` from the web AI settings. */
object AiSettingsLogic {
    val NAMED_EFFORTS = listOf("minimal", "low", "medium", "high", "xhigh", "max", "ultra")

    /** An effort survives a model switch only when the new model lists it. */
    fun keptEffort(catalog: CliCatalog?, id: String, effort: String): String =
        if (effort.isNotEmpty() && catalog?.models?.firstOrNull { it.id == id }?.efforts?.contains(effort) == true) effort else ""

    /** The i18n key of a named effort, or a capitalized id. */
    fun effortLabel(id: String, translate: (String) -> String): String =
        if (id in NAMED_EFFORTS) translate("ai.effort.$id") else id.replaceFirstChar { it.uppercase() }

    /** The model a blank id stands for: the CLI's default. */
    fun current(catalog: CliCatalog?, id: String): CliModel? =
        catalog?.models?.firstOrNull { it.id == id } ?: if (id.isEmpty()) catalog?.models?.firstOrNull { it.isDefault } else null
}

/** Per-engine model memory, so switching engines back restores the model (the web keeps `claudeCodeModel` and `codexModel`). */
private class EngineModels(context: Context) {
    private val prefs = context.getSharedPreferences("pulls-settings-ui", Context.MODE_PRIVATE)
    fun model(engine: String): String? = prefs.getString("model:$engine", null)
    fun setModel(engine: String, id: String) = prefs.edit().putString("model:$engine", id).apply()
}

/** Port of `SettingsModal`/`SettingsPanel` (local build), plus the phone's own server and Access fields. */
/**
 * `SettingsModal` + `SettingsPanel`: the web opens Settings as an `AppModal` over the page
 * (a bottom sheet on phones, a dialog from `sm`), body `flex flex-col gap-4 p2`. The local
 * build has no GitHub token section; the phone adds its server section at the end.
 */
@Composable
fun SettingsScreen(nav: Nav) {
    com.octaviantocan.pulls.ui.design.AppModal(onClose = nav.back, title = t("common.settings")) {
        Column(Modifier.fillMaxWidth().padding(8.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            LayoutSettingsSection()
            Divider()
            AutoRefreshSettingsSection()
            Divider()
            LlmSettingsSection()
            Divider()
            ServerSection()
        }
    }
}

@Composable
private fun Divider() = Box(Modifier.fillMaxWidth().height(1.dp).background(LocalPullsColors.current.border))

@Composable
private fun SectionTitle(text: String) = Text(text, Modifier.padding(bottom = 8.dp), style = PullsType.sm.copy(fontWeight = FontWeight.Medium), color = LocalPullsColors.current.text)

/** `FormField`: label, control, description, error. */
@Composable
private fun FormField(label: String?, description: String? = null, error: String? = null, descriptionContent: (@Composable () -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    val c = LocalPullsColors.current
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (label != null) Text(label, style = PullsType.sm.copy(fontWeight = FontWeight.Medium), color = c.text)
        content()
        if (descriptionContent != null) descriptionContent()
        else if (description != null) Text(description, style = PullsType.sm, color = c.faint)
        if (error != null) Text(error, style = PullsType.sm, color = com.octaviantocan.pulls.ui.home.StatusColors.red(c.dark))
    }
}

/** `LayoutSettingsSection`. */
@Composable
private fun LayoutSettingsSection() {
    val settings = AppSettings.get(LocalContext.current)
    Column {
        SectionTitle(t("settings.layout"))
        ToggleGroup(
            listOf("unified" to t("settings.unified"), "split" to t("settings.split")),
            settings.layout,
            { settings.updateLayout(it) },
            icons = mapOf("unified" to "rows-duotone", "split" to "columns-duotone"),
        )
        // Phone-only: code size (also pinch on the diff) and wrapping.
        Row(Modifier.padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Code size", Modifier.weight(1f), style = PullsType.sm, color = LocalPullsColors.current.text)
            com.octaviantocan.pulls.ui.design.IconButton("magnifying-glass-minus", "Smaller code", { settings.updateCodeZoom(((settings.codeZoom - 0.1f) * 10).roundToInt() / 10f) }, enabled = settings.codeZoom > AppSettings.MIN_ZOOM)
            Text("${(settings.codeZoom * 100).roundToInt()}%", style = PullsType.sm.copy(fontFamily = PullsType.mono), color = LocalPullsColors.current.text)
            com.octaviantocan.pulls.ui.design.IconButton("magnifying-glass-plus", "Larger code", { settings.updateCodeZoom(((settings.codeZoom + 0.1f) * 10).roundToInt() / 10f) }, enabled = settings.codeZoom < AppSettings.MAX_ZOOM)
        }
        FormCheckbox(settings.codeWrap, { settings.updateCodeWrap(it) }, "Wrap long code lines")
    }
}

/** `AutoRefreshSettingsSection`: a `FormCheckbox`. */
@Composable
private fun AutoRefreshSettingsSection() {
    val settings = AppSettings.get(LocalContext.current)
    Column {
        SectionTitle(t("settings.cached"))
        FormCheckbox(settings.autoRefresh, { settings.updateAutoRefresh(it) }, t("settings.autoRefresh"))
    }
}

/** `FormCheckbox`: 16px `border-base rounded bg-raised` box, primary fill and a white check when on. */
@Composable
private fun FormCheckbox(checked: Boolean, onChange: (Boolean) -> Unit, label: String) {
    val c = LocalPullsColors.current
    Row(
        Modifier.defaultMinSize(minHeight = TOUCH).clip(RoundedCornerShape(4.dp)).clickable(role = Role.Checkbox) { onChange(!checked) },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            Modifier.size(16.dp).clip(RoundedCornerShape(4.dp)).background(if (checked) c.primary else c.raised).border(1.dp, if (checked) c.primary else c.border, RoundedCornerShape(4.dp)),
            contentAlignment = Alignment.Center,
        ) { if (checked) PhIcon("check-bold", tint = Color.White, size = 10.dp) }
        Text(label, style = PullsType.sm, color = c.text)
    }
}

/**
 * Stands in for `GithubTokenSettings`: in the local build (and on the phone) the server's
 * `gh` sign-in supplies GitHub access, so there is no token to enter here.
 */
@Composable
private fun GithubAccessNote() {
    val c = LocalPullsColors.current
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.padding(bottom = 4.dp)) {
            DrawableIcon(R.drawable.ic_carbon_logo_github, tint = c.text, size = 18.dp)
            Text("GitHub", style = PullsType.sm.copy(fontWeight = FontWeight.Medium), color = c.text)
        }
        Text("The server uses its own GitHub sign-in (gh) for every request. Reviews and comments are posted as that account.", style = PullsType.sm, color = c.faint)
    }
}

/** `LlmSettingsSection` with the local build's `AiCliSettings`: engine, model, reasoning effort. */
@Composable
private fun LlmSettingsSection() {
    val context = LocalContext.current
    val c = LocalPullsColors.current
    val settings = AppSettings.get(context)
    val repo = remember { Repo(context) }
    val memory = remember { EngineModels(context) }
    val scope = rememberCoroutineScope()
    var config by remember { mutableStateOf(ServerStore.load(context)) }
    var catalog by remember { mutableStateOf<CliCatalog?>(null) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var refreshTick by remember { mutableIntStateOf(0) }

    fun save(next: ServerConfig) {
        config = next
        ServerStore.save(context, next)
    }

    LaunchedEffect(config.engine, refreshTick) {
        loading = true
        error = null
        try {
            catalog = repo.models(config.engine, refresh = refreshTick > 0).asCatalog()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            catalog = null
            error = e.message ?: e.toString()
        } finally {
            loading = false
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.padding(bottom = 4.dp)) {
                PhIcon("sparkle-duotone", tint = c.text, size = 20.dp)
                Text(t("settings.llm.title"), style = PullsType.sm.copy(fontWeight = FontWeight.Medium), color = c.text)
            }
            Text(t("settings.llm.localDescription"), style = PullsType.sm, color = c.faint)
        }

        FormField(t("settings.llm.engine")) {
            EngineToggle(config.engine) { engine ->
                if (engine == config.engine) return@EngineToggle
                memory.setModel(config.engine, config.model)
                save(config.copy(engine = engine, model = memory.model(engine).orEmpty()))
                catalog = null
                settings.updateEffort("")
            }
        }

        FormField(t("settings.llm.model"), descriptionContent = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                catalog?.line?.takeIf { it.isNotEmpty() }?.let { Text(it, style = PullsType.sm, color = c.faint) }
                IconButton("arrow-clockwise", t("settings.llm.refreshModels"), { refreshTick++ }, compact = true, enabled = !loading)
            }
        }) {
            AiModelPicker(config.model, catalog, loading, error) { id ->
                val kept = AiSettingsLogic.keptEffort(catalog, id, settings.effort)
                memory.setModel(config.engine, id)
                save(config.copy(model = id))
                settings.updateEffort(kept)
            }
        }

        val choice = AiSettingsLogic.current(catalog, config.model)
        if (choice != null && choice.efforts.isNotEmpty()) {
            FormField(t("settings.llm.effort"), description = t("settings.llm.effortHint")) {
                AiEffortPicker(settings.effort, choice.efforts, choice.defaultEffort) { settings.updateEffort(it) }
            }
        }
    }
}

/** The engine `ActionToggleGroup` with the Claude and OpenAI marks. */
@Composable
private fun EngineToggle(value: String, onChange: (String) -> Unit) {
    val c = LocalPullsColors.current
    Row(Modifier.clip(RoundedCornerShape(8.dp)).background(c.sunken).padding(4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        listOf(Triple("claude-code", "Claude Code", R.drawable.ic_si_claude), Triple("codex", "Codex", R.drawable.ic_si_openai)).forEach { (id, label, icon) ->
            val on = id == value
            Row(
                Modifier
                    .defaultMinSize(minHeight = 36.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(if (on) c.raised else Color.Transparent)
                    .clickable(role = Role.Tab) { onChange(id) }
                    .padding(horizontal = 12.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                DrawableIcon(icon, tint = if (on) c.text else c.muted, size = 16.dp)
                Text(label, style = PullsType.sm, color = if (on) c.text else c.muted, maxLines = 1)
            }
        }
    }
}

/** `AiModelPicker`: an in-flow expandable list with search, default badge, ids and descriptions, and a custom id. */
@Composable
private fun AiModelPicker(modelId: String, catalog: CliCatalog?, loading: Boolean, error: String?, onPick: (String) -> Unit) {
    val c = LocalPullsColors.current
    if (error != null) {
        var draft by remember(modelId) { mutableStateOf(modelId) }
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                TextInput(draft, { draft = it }, t("settings.models.placeholder"), Modifier.weight(1f), keyboardOptions = KeyboardOptions(autoCorrectEnabled = false))
                ActionButton({ onPick(draft.trim()) }, enabled = draft.trim() != modelId) { Text(t("common.save")) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                PhIcon("warning", Modifier.padding(top = 2.dp), tint = com.octaviantocan.pulls.ui.home.StatusColors.amberText(c.dark), size = 14.dp)
                Text(t("settings.llm.catalogFailed", "message" to error), style = PullsType.xs, color = com.octaviantocan.pulls.ui.home.StatusColors.amberText(c.dark))
            }
        }
        return
    }
    var open by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    val current = AiSettingsLogic.current(catalog, modelId)
    val shape = RoundedCornerShape(4.dp)
    Column(Modifier.fillMaxWidth().clip(shape).background(c.raised).border(1.dp, c.border, shape)) {
        Row(
            Modifier.fillMaxWidth().defaultMinSize(minHeight = TOUCH).clickable(role = Role.DropdownList) { open = !open; query = "" }.padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (loading && catalog == null) {
                Spinner(14.dp)
                Text(t("settings.models.loading"), Modifier.weight(1f), style = PullsType.sm, color = c.fade)
            } else {
                Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(current?.name ?: modelId, style = PullsType.sm, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    if (current?.isDefault == true) DefaultBadge()
                    current?.description?.let { Text(it, Modifier.weight(1f, fill = false), style = PullsType.xs, color = c.mute, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                }
            }
            PhIcon(if (open) "caret-up" else "caret-down", tint = c.fade, size = 16.dp)
        }
        if (open) {
            Box(Modifier.fillMaxWidth().height(1.dp).background(c.border))
            Box(Modifier.padding(6.dp)) {
                TextInput(query, { query = it }, t("settings.models.search"), Modifier.fillMaxWidth(), icon = "magnifying-glass", keyboardOptions = KeyboardOptions(autoCorrectEnabled = false))
            }
            val q = query.trim().lowercase()
            val all = catalog?.models.orEmpty()
            val filtered = if (q.isEmpty()) all else all.filter { m -> listOf(m.name, m.id, m.description.orEmpty()).any { it.lowercase().contains(q) } }
            val custom = query.trim().takeIf { it.isNotEmpty() && all.none { m -> m.id == it } }
            Column(Modifier.heightIn(max = 256.dp).verticalScroll(rememberScrollState()).padding(bottom = 4.dp)) {
                when {
                    loading && catalog == null -> Empty(t("settings.models.loading"))
                    filtered.isEmpty() && custom == null -> Empty(t("settings.models.noMatch"))
                }
                filtered.forEach { model ->
                    val selected = model.id == (current?.id ?: modelId)
                    Row(
                        Modifier.fillMaxWidth().defaultMinSize(minHeight = TOUCH).background(if (selected) c.active else Color.Transparent).pressTint({ onPick(model.id); open = false }, role = Role.RadioButton).padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.Top,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Column(Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(model.name, style = PullsType.sm, color = if (selected) c.activeText else c.text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                                if (model.isDefault) DefaultBadge()
                                if (model.name != model.id) Text(model.id, Modifier.weight(1f, fill = false), style = PullsType.xs.copy(fontFamily = PullsType.mono), color = c.mute, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            model.description?.let { Text(it, style = PullsType.xs, color = c.mute) }
                        }
                        if (selected) PhIcon("check", Modifier.padding(top = 4.dp), tint = c.activeText, size = 13.dp)
                    }
                }
                if (custom != null) {
                    Row(
                        Modifier.fillMaxWidth().defaultMinSize(minHeight = TOUCH).pressTint({ onPick(custom); open = false }).padding(horizontal = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        PhIcon("plus", tint = c.fade, size = 13.dp)
                        Text(t("settings.models.useCustom", "id" to custom), style = PullsType.sm, color = c.fade, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

@Composable
private fun DefaultBadge() {
    val c = LocalPullsColors.current
    Text(t("settings.models.default"), Modifier.border(1.dp, c.border, RoundedCornerShape(4.dp)).padding(horizontal = 4.dp), style = PullsType.xs, color = c.mute, maxLines = 1)
}

@Composable
private fun Empty(text: String) {
    Box(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 16.dp), contentAlignment = Alignment.Center) {
        Text(text, style = PullsType.sm, color = LocalPullsColors.current.mute)
    }
}

/** `AiEffortPicker`: CLI default (named when the model says which) and each effort, wrapping. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AiEffortPicker(effort: String, efforts: List<String>, defaultEffort: String?, onChange: (String) -> Unit) {
    val c = LocalPullsColors.current
    val messages = com.octaviantocan.pulls.ui.design.LocalMessages.current
    val label = { id: String -> AiSettingsLogic.effortLabel(id) { messages.t(it) } }
    val options = listOf("" to (defaultEffort?.let { t("settings.llm.effortDefaultNamed", "effort" to label(it)) } ?: t("settings.llm.effortDefault"))) + efforts.map { it to label(it) }
    FlowRow(Modifier.clip(RoundedCornerShape(8.dp)).background(c.sunken).padding(4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        options.forEach { (value, text) ->
            val on = value == effort
            Box(
                Modifier
                    .defaultMinSize(minHeight = 36.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(if (on) c.raised else Color.Transparent)
                    .clickable(role = Role.Tab) { onChange(value) }
                    .padding(horizontal = 12.dp, vertical = 4.dp),
                contentAlignment = Alignment.Center,
            ) { Text(text, style = PullsType.sm, color = if (on) c.text else c.muted, maxLines = 1) }
        }
    }
}

/** Phone only: which server to talk to, and the Cloudflare Access service token in front of it. */
@Composable
private fun ServerSection() {
    val context = LocalContext.current
    val c = LocalPullsColors.current
    val scope = rememberCoroutineScope()
    val saved = remember { ServerStore.load(context) }
    var url by remember { mutableStateOf(saved.url) }
    var accessId by remember { mutableStateOf(saved.accessId) }
    var accessSecret by remember { mutableStateOf(saved.accessSecret) }
    var status by remember { mutableStateOf<String?>(null) }
    var checking by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.padding(bottom = 4.dp)) {
                PhIcon("plugs-connected-duotone", tint = c.text, size = 20.dp)
                Text("Server", style = PullsType.sm.copy(fontWeight = FontWeight.Medium), color = c.text)
            }
            Text("The pulls.review server this phone reads from. A Cloudflare Access service token lets it through the Access login.", style = PullsType.sm, color = c.faint)
        }
        FormField("Server URL") {
            TextInput(url, { url = it }, "https://prs.example.com", Modifier.fillMaxWidth(), icon = "globe-duotone", keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false))
        }
        FormField("Access client ID") {
            TextInput(accessId, { accessId = it }, "….access", Modifier.fillMaxWidth(), icon = "key-duotone", keyboardOptions = KeyboardOptions(autoCorrectEnabled = false))
        }
        FormField("Access client secret", description = "Stored only on this phone.") {
            SecretInput(accessSecret) { accessSecret = it }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            ActionButton({
                val current = ServerStore.load(context)
                ServerStore.save(context, current.copy(url = url, accessId = accessId, accessSecret = accessSecret))
                checking = true
                status = null
                scope.launch {
                    status = try {
                        Repo(context).repoInfo()
                        "Saved. The server answered."
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        "Saved, but the server did not answer: ${e.message ?: e}"
                    }
                    checking = false
                }
            }, variant = ButtonVariant.Primary, loading = checking, enabled = url.isNotBlank()) { Text(t("common.save")) }
            status?.let { Text(it, Modifier.weight(1f), style = PullsType.sm, color = c.fade) }
        }
    }
}

/** A masked `FormTextInput type="password"` with a show/hide toggle. */
@Composable
private fun SecretInput(value: String, onChange: (String) -> Unit) {
    val c = LocalPullsColors.current
    var shown by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(4.dp)
    androidx.compose.foundation.text.BasicTextField(
        value = value,
        onValueChange = onChange,
        singleLine = true,
        textStyle = PullsType.base.copy(color = c.text),
        cursorBrush = androidx.compose.ui.graphics.SolidColor(c.primary),
        visualTransformation = if (shown) androidx.compose.ui.text.input.VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
        modifier = Modifier.fillMaxWidth().defaultMinSize(minHeight = TOUCH),
        decorationBox = { inner ->
            Row(
                Modifier.clip(shape).background(c.raised).border(1.dp, c.border, shape).padding(start = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                PhIcon("key-duotone", tint = c.fade, size = 18.dp)
                Box(Modifier.weight(1f).padding(vertical = 10.dp)) {
                    if (value.isEmpty()) Text("••••", style = PullsType.base, color = c.mute)
                    inner()
                }
                IconButton(if (shown) "eye-slash-duotone" else "eye-duotone", if (shown) "Hide" else "Show", { shown = !shown }, compact = true)
            }
        },
    )
}
