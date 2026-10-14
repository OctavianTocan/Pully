package com.octaviantocan.pulls.ui.settings

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.octaviantocan.pulls.data.AppSettings
import com.octaviantocan.pulls.data.Repo
import com.octaviantocan.pulls.data.ServerStore
import com.octaviantocan.pulls.ui.Nav
import com.octaviantocan.pulls.ui.book.UiBookRow
import com.octaviantocan.pulls.ui.design.CapsuleTabs
import com.octaviantocan.pulls.ui.design.CtaButton
import com.octaviantocan.pulls.ui.design.EngineMark
import com.octaviantocan.pulls.ui.design.LocalMessages
import com.octaviantocan.pulls.ui.design.LocalPullsColors
import com.octaviantocan.pulls.ui.design.Lu
import com.octaviantocan.pulls.ui.design.LuIcon
import com.octaviantocan.pulls.ui.design.Messages
import com.octaviantocan.pulls.ui.design.PullsSheet
import com.octaviantocan.pulls.ui.design.PullsSwitch
import com.octaviantocan.pulls.ui.design.PullsType
import com.octaviantocan.pulls.ui.design.SheetCard
import com.octaviantocan.pulls.ui.design.SheetField
import com.octaviantocan.pulls.ui.design.SheetRow
import com.octaviantocan.pulls.ui.design.t
import com.octaviantocan.pulls.ui.review.CodeFontSheet
import com.octaviantocan.pulls.ui.review.EffortSheet
import com.octaviantocan.pulls.ui.review.ModelCatalogs
import com.octaviantocan.pulls.ui.review.ModelSheet
import com.octaviantocan.pulls.ui.review.analysisModelLabel
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
internal class EngineModels(context: Context) {
    private val prefs = context.getSharedPreferences("pulls-settings-ui", Context.MODE_PRIVATE)
    fun model(engine: String): String? = prefs.getString("model:$engine", null)
    fun setModel(engine: String, id: String) = prefs.edit().putString("model:$engine", id).apply()
}

/**
 * Settings, as a sheet like the rest of the app: grouped white cards on paper, one row for each choice.
 * Model and effort are the same sheets the More menu opens; the language and the server open their own page.
 */
@Composable
fun SettingsScreen(nav: Nav) {
    var page by remember { mutableStateOf("main") }
    when (page) {
        "language" -> LanguageSheet(onBack = { page = "main" }, onClose = nav.back)
        "server" -> ServerSheet(onBack = { page = "main" }, onClose = nav.back)
        "font" -> CodeFontSheet(AppSettings.get(LocalContext.current), onClose = { page = "main" })
        "model" -> ModelSheet(AppSettings.get(LocalContext.current), onClose = { page = "main" }, onEffort = { page = "effort" })
        "effort" -> EffortSheet(AppSettings.get(LocalContext.current), onBack = { page = "model" }, onClose = { page = "main" })
        else -> MainSheet(nav, open = { page = it })
    }
}

/** A small muted heading above a card. */
@Composable
private fun Group(title: String, content: @Composable () -> Unit) {
    Text(title, Modifier.padding(start = 16.dp, top = 24.dp), style = PullsType.sm.copy(fontWeight = FontWeight.Medium), color = LocalPullsColors.current.muted)
    content()
}

@Composable
private fun MainSheet(nav: Nav, open: (String) -> Unit) {
    val c = LocalPullsColors.current
    val context = LocalContext.current
    val settings = AppSettings.get(context)
    val messages = LocalMessages.current
    val config = remember { ServerStore.load(context) }
    val language = Messages.LOCALES.firstOrNull { it.first == settings.locale }?.second ?: settings.locale
    val zoomChoices = listOf("Smaller" to 0.88f, "Default" to 1f, "Larger" to 1.16f)

    PullsSheet(nav.back, t("common.settings"), full = true) {
        Group("Appearance") {
            Box(Modifier.padding(top = 10.dp)) {
                val themes = listOf("system", "light", "dark")
                CapsuleTabs(
                    listOf("System", "Light", "Dark"),
                    themes.indexOf(settings.theme).coerceAtLeast(0),
                    { settings.updateTheme(themes[it]) },
                    Modifier.fillMaxWidth(), flat = true,
                )
            }
            SheetCard {
                SheetRow(t("settings.language"), { open("language") }, first = true, iconless = true) {
                    Text(language, style = PullsType.sm, color = c.muted)
                    LuIcon(Lu.chev, tint = c.faint, size = 18.dp)
                }
            }
        }
        Group(t("settings.layout")) {
            Box(Modifier.padding(top = 10.dp)) {
                val layouts = listOf("unified", "split")
                CapsuleTabs(
                    listOf(t("settings.unified"), t("settings.split")),
                    layouts.indexOf(settings.layout).coerceAtLeast(0),
                    { settings.updateLayout(layouts[it]) },
                    Modifier.fillMaxWidth(), flat = true,
                )
            }
            SheetCard {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text("Code size", Modifier.weight(1f), style = PullsType.row, color = c.text, maxLines = 1)
                    zoomChoices.forEach { (name, zoom) ->
                        val on = kotlin.math.abs(settings.codeZoom - zoom) < 0.04f
                        Box(
                            Modifier.height(36.dp).background(if (on) c.chipOn else androidx.compose.ui.graphics.Color.Transparent, androidx.compose.foundation.shape.CircleShape)
                                .clickable(role = Role.Tab) { settings.updateCodeZoom(zoom) }.padding(horizontal = 12.dp),
                            contentAlignment = Alignment.Center,
                        ) { Text(name, style = PullsType.sm.copy(fontWeight = FontWeight.Medium), color = c.text, maxLines = 1) }
                    }
                }
                SheetRow("Code font", { open("font") }, iconless = true) {
                    Text(com.octaviantocan.pulls.ui.design.CodeFont.of(settings.codeFont).label, style = PullsType.sm, color = c.muted)
                    LuIcon(Lu.chev, tint = c.faint, size = 18.dp)
                }
                SheetRow("Wrap long lines", { settings.updateCodeWrap(!settings.codeWrap) }, iconless = true) {
                    PullsSwitch(settings.codeWrap, { settings.updateCodeWrap(it) }, "Wrap long lines")
                }
            }
        }
        Group(t("settings.cached")) {
            SheetCard {
                SheetRow(t("settings.autoRefresh"), { settings.updateAutoRefresh(!settings.autoRefresh) }, first = true, iconless = true) {
                    PullsSwitch(settings.autoRefresh, { settings.updateAutoRefresh(it) }, t("settings.autoRefresh"))
                }
            }
        }
        Group(t("settings.llm.title")) {
            SheetCard {
                SheetRow("Model", { open("model") }, first = true, icon = { EngineMark(config.engine, size = 22.dp) }) {
                    Text(analysisModelLabel(config, ModelCatalogs.get(config.engine), settings.effort, messages), Modifier.weight(1f, fill = false), style = PullsType.sm, color = c.muted)
                    LuIcon(Lu.chev, tint = c.faint, size = 18.dp)
                }
            }
            Text(
                t("settings.llm.localDescription"),
                Modifier.padding(start = 16.dp, end = 16.dp, top = 10.dp), style = PullsType.sm.copy(lineHeight = PullsType.sm.fontSize * 1.45f), color = c.muted,
            )
        }
        Group("Server") {
            SheetCard {
                SheetRow("Server", { open("server") }, first = true, iconless = true) {
                    Text(config.url.removePrefix("https://").removePrefix("http://").ifBlank { "Not set" }, Modifier.weight(1f, fill = false), style = PullsType.sm, color = c.muted)
                    LuIcon(Lu.chev, tint = c.faint, size = 18.dp)
                }
            }
            Text(
                "The server uses its own GitHub sign-in for every request. Reviews and comments are posted as that account.",
                Modifier.padding(start = 16.dp, end = 16.dp, top = 10.dp), style = PullsType.sm.copy(lineHeight = PullsType.sm.fontSize * 1.45f), color = c.muted,
            )
        }
        UiBookRow(nav.uiBook)
    }
}

/** The languages the app is written in; picking one changes the app at once. */
@Composable
private fun LanguageSheet(onBack: () -> Unit, onClose: () -> Unit) {
    val c = LocalPullsColors.current
    val settings = AppSettings.get(LocalContext.current)
    PullsSheet(onClose, t("settings.language"), back = onBack, expandable = true) {
        SheetCard {
            Messages.LOCALES.forEachIndexed { index, (code, name) ->
                SheetRow(name, { settings.updateLocale(code); onBack() }, first = index == 0, iconless = true) {
                    if (code == settings.locale) LuIcon(Lu.check, tint = c.accentTeal, size = 20.dp)
                }
            }
        }
    }
}

/** Phone only: which server to talk to, and the Cloudflare Access service token in front of it. */
@Composable
private fun ServerSheet(onBack: () -> Unit, onClose: () -> Unit) {
    val context = LocalContext.current
    val c = LocalPullsColors.current
    val scope = rememberCoroutineScope()
    val saved = remember { ServerStore.load(context) }
    var url by remember { mutableStateOf(saved.url) }
    var accessId by remember { mutableStateOf(saved.accessId) }
    var accessSecret by remember { mutableStateOf(saved.accessSecret) }
    var shown by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var checking by remember { mutableStateOf(false) }
    PullsSheet(onClose, "Server", back = onBack, expandable = true) {
        Text(
            "The pulls.review server this phone reads from. A Cloudflare Access service token lets it through the Access login.",
            Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp), style = PullsType.sm.copy(lineHeight = PullsType.sm.fontSize * 1.45f), color = c.muted,
        )
        SheetCard {
            SheetField("Server URL", url, { url = it }, placeholder = "https://prs.example.com", first = true, keyboardType = KeyboardType.Uri)
            SheetField("Access client ID", accessId, { accessId = it }, placeholder = "….access", keyboardType = KeyboardType.Ascii)
            SheetField(
                "Access client secret", accessSecret, { accessSecret = it }, placeholder = "••••", secret = !shown, keyboardType = KeyboardType.Password,
                trailing = {
                    Text(
                        if (shown) "Hide" else "Show",
                        Modifier.clickable(role = Role.Button) { shown = !shown }.padding(horizontal = 8.dp, vertical = 10.dp),
                        style = PullsType.sm.copy(fontWeight = FontWeight.Medium), color = c.activeText,
                    )
                },
            )
        }
        Text("The secret is stored only on this phone.", Modifier.padding(start = 16.dp, end = 16.dp, top = 10.dp), style = PullsType.sm, color = c.muted)
        CtaButton(
            if (checking) "Checking…" else t("common.save"),
            {
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
            },
            Modifier.fillMaxWidth().padding(top = 20.dp), enabled = url.isNotBlank() && !checking,
        )
        status?.let { Text(it, Modifier.padding(horizontal = 16.dp, vertical = 12.dp), style = PullsType.sm, color = c.muted) }
    }
}
