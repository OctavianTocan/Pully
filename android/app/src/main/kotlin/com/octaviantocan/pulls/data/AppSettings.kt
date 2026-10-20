package com.octaviantocan.pulls.data

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.octaviantocan.pulls.core.AnalyzeChoice
import com.octaviantocan.pulls.ui.design.Messages
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.floatOrNull

/**
 * The web app's local preferences (`state/settings.ts`, `state/layout.ts`, `state/dark.ts`,
 * `state/auto-refresh.ts`, `diffs:show-review-comments`), kept in SharedPreferences and
 * exposed as Compose state so the theme, language and diff layout react at once.
 */
class AppSettings private constructor(private val context: Context) {
    private val prefs = context.getSharedPreferences("pulls-ui", Context.MODE_PRIVATE)

    /** `system`, `dark` or `light`; `system` (follow the phone) until Settings picks one. */
    var theme by mutableStateOf(prefs.getString("theme", "system") ?: "system")
        private set

    /** The colour theme by its `ColorTheme.id` (K18): `pulls`, the app's own palette, until Settings picks another. */
    var colorTheme by mutableStateOf(prefs.getString("colorTheme", null) ?: "pulls")
        private set

    /** A code from [Messages.LOCALES]. */
    var locale by mutableStateOf(prefs.getString("locale", null) ?: Messages.detect())
        private set

    /** `split` or `unified`; the web defaults to split. */
    var layout by mutableStateOf(prefs.getString("layout", null)?.lowercase() ?: "split")
        private set

    /**
     * The Inbox chip last picked, `waiting`, `authored` or `all`, so the app opens on it again ("whichever tab I clicked
     * on last, when I open the app again, it uses that one", recording of 17 Oct 13:26, 00:03 to 00:17, P1).
     */
    var inboxFilter by mutableStateOf(prefs.getString("inboxFilter", null)?.takeIf { it in Triage.inboxFilters } ?: "waiting")
        private set

    /** A pull request page takes new commits by itself, quietly; switched off, it says they are there and waits for a tap. */
    var autoRefresh by mutableStateOf(prefs.getBoolean("autoRefresh", true))
        private set

    var showThreads by mutableStateOf(prefs.getBoolean("showThreads", true))
        private set

    /** AI effort for the selected model; empty uses the CLI default. */
    var effort by mutableStateOf(prefs.getString("effort", "") ?: "")
        private set

    /** What the Analyze button runs: the preset last picked, or the passes picked under Customize. */
    var analyzeChoice by mutableStateOf(AnalyzeChoice.decode(prefs.getString("analyzeChoice", null)))
        private set

    /** Phone-only: diff code size multiplier (pinch on the diff, or Settings), 0.6x to 2x. */
    var codeZoom by mutableStateOf(prefs.getFloat("codeZoom", 1f))
        private set

    /** Phone-only: wrap long code lines instead of scrolling them sideways. */
    var codeWrap by mutableStateOf(prefs.getBoolean("codeWrap", false))

    /** The face the app's words are set in, by its `AppFont.id`: Geist until the reader picks another (N10). */
    var appFont by mutableStateOf(prefs.getString("appFont", null) ?: "geist")
        private set

    /** The face code is set in, by its `CodeFont.id`: Geist Mono until the reader picks another. */
    var codeFont by mutableStateOf(prefs.getString("codeFont", null) ?: "geist-mono")
        private set

    /** Whether taps and outcomes make a sound (the Cuelume cues); on until Settings turns it off. */
    var sounds by mutableStateOf(prefs.getBoolean("sounds", true))
        private set

    /** Whether taps, swipes and outcomes vibrate (H18); on until Settings turns it off. */
    var haptics by mutableStateOf(prefs.getBoolean("haptics", true))
        private set

    /** The material the cues are played in, by its `Material.id`: `default` until the reader picks another. */
    var soundMaterial by mutableStateOf(prefs.getString("soundMaterial", null) ?: "default")
        private set

    /** Whether the background check sends a notification when a new version of the app is out; on until Settings turns it off. */
    var updateNotifications by mutableStateOf(prefs.getBoolean("updateNotifications", true))
        private set

    /**
     * Whether the app counts what is done in it and sends the counts to our own server (Settings, Usage stats); on until
     * Settings turns it off. Off drops what is queued and sends nothing more (root `CONTRACTS`, analytics-can-be-turned-off).
     */
    var usageStats by mutableStateOf(prefs.getBoolean("usageStats", true))
        private set

    /** Repositories kept out of the Inbox and its counts, as `owner/repo`; none until Profile switches one off. */
    var hiddenRepos by mutableStateOf(prefs.getStringSet("hiddenRepos", null)?.toSet() ?: emptySet())
        private set

    fun updateTheme(value: String) { theme = value; prefs.edit().putString("theme", value).apply(); changed("theme", value); touched("theme") }
    fun updateColorTheme(value: String) { colorTheme = value; prefs.edit().putString("colorTheme", value).apply(); changed("colorTheme", value); touched("colorTheme") }
    fun updateLocale(value: String) { locale = value; prefs.edit().putString("locale", value).apply(); changed("locale", value); touched("locale") }
    fun updateLayout(value: String) { layout = value; prefs.edit().putLayout(value).apply(); changed("layout", value); touched("layout") }
    fun updateInboxFilter(value: String) {
        if (value == inboxFilter || value !in Triage.inboxFilters) return
        inboxFilter = value; prefs.edit().putString("inboxFilter", value).apply(); touched("inboxFilter")
    }
    fun updateAutoRefresh(value: Boolean) { autoRefresh = value; prefs.edit().putBoolean("autoRefresh", value).apply(); changed("autoRefresh", value); touched("autoRefresh") }
    fun updateShowThreads(value: Boolean) { showThreads = value; prefs.edit().putBoolean("showThreads", value).apply(); changed("showThreads", value); touched("showThreads") }
    fun updateAnalyzeChoice(value: AnalyzeChoice) { analyzeChoice = value; prefs.edit().putString("analyzeChoice", value.encode()).apply(); touched("analyzeChoice") }
    fun updateEffort(value: String) { effort = value; prefs.edit().putString("effort", value).apply(); changed("effort", value.ifBlank { "default" }); touched("effort") }
    /**
     * Changes the code size on screen without saving it: a pinch calls this as the fingers move, so the code is laid
     * out again at each size, and [updateCodeZoom] once when they lift (M9).
     */
    fun previewCodeZoom(value: Float) { codeZoom = value.coerceIn(MIN_ZOOM, MAX_ZOOM) }

    fun updateCodeZoom(value: Float) {
        codeZoom = value.coerceIn(MIN_ZOOM, MAX_ZOOM)
        prefs.edit().putFloat("codeZoom", codeZoom).apply()
        touched("codeZoom")
    }

    fun updateCodeWrap(value: Boolean) { codeWrap = value; prefs.edit().putBoolean("codeWrap", value).apply(); changed("codeWrap", value); touched("codeWrap") }
    fun updateSounds(value: Boolean) { sounds = value; prefs.edit().putBoolean("sounds", value).apply(); changed("sounds", value); touched("sounds") }
    fun updateHaptics(value: Boolean) { haptics = value; prefs.edit().putBoolean("haptics", value).apply(); changed("haptics", value); touched("haptics") }
    fun updateSoundMaterial(value: String) { soundMaterial = value; prefs.edit().putString("soundMaterial", value).apply(); changed("soundMaterial", value); touched("soundMaterial") }
    fun updateUpdateNotifications(value: Boolean) { updateNotifications = value; prefs.edit().putBoolean("updateNotifications", value).apply(); changed("updateNotifications", value) }

    /** Turns usage stats on or off. Off drops the queue at once and is not itself sent; on is the first event sent again. */
    fun updateUsageStats(value: Boolean) {
        usageStats = value
        prefs.edit().putBoolean("usageStats", value).apply()
        Analytics.setEnabled(value)
        if (value) changed("usageStats", true)
    }
    /** Switches a repository on or off for the Inbox. */
    fun setRepoShown(repo: String, shown: Boolean) = setReposShown(listOf(repo), shown)

    /** Applies one visibility change to every given repository, with one saved and synced setting change. */
    fun setReposShown(repos: Collection<String>, shown: Boolean) {
        val next = if (shown) hiddenRepos - repos.toSet() else hiddenRepos + repos
        if (next == hiddenRepos) return
        hiddenRepos = next
        prefs.edit().putStringSet("hiddenRepos", hiddenRepos).apply()
        touched("hiddenRepos")
    }

    fun updateAppFont(value: String) { appFont = value; prefs.edit().putString("appFont", value).apply(); changed("appFont", value); touched("appFont") }

    fun updateCodeFont(value: String) { codeFont = value; prefs.edit().putString("codeFont", value).apply(); changed("codeFont", value); touched("codeFont") }

    /** The layout as the web stores it, in capitals. */
    private fun android.content.SharedPreferences.Editor.putLayout(value: String) = putString("layout", value.uppercase())

    /** A setting the account keeps (plan 13) changed here: [SettingsSync] sends it on. */
    private fun touched(key: String) = SettingsSync.touched(context, key)

    /** Whether [name] was ever chosen on this phone, rather than left at its default. */
    fun chosen(name: String): Boolean = prefs.contains(name)

    /** A synced setting's value as the account keeps it; null for one that does not travel. */
    fun syncedValue(name: String): JsonElement? = when (name) {
        "theme" -> JsonPrimitive(theme)
        "colorTheme" -> JsonPrimitive(colorTheme)
        "locale" -> JsonPrimitive(locale)
        "layout" -> JsonPrimitive(layout)
        "autoRefresh" -> JsonPrimitive(autoRefresh)
        "showThreads" -> JsonPrimitive(showThreads)
        "effort" -> JsonPrimitive(effort)
        "analyzeChoice" -> JsonPrimitive(analyzeChoice.encode())
        "codeZoom" -> JsonPrimitive(codeZoom)
        "codeWrap" -> JsonPrimitive(codeWrap)
        "appFont" -> JsonPrimitive(appFont)
        "codeFont" -> JsonPrimitive(codeFont)
        "sounds" -> JsonPrimitive(sounds)
        "haptics" -> JsonPrimitive(haptics)
        "soundMaterial" -> JsonPrimitive(soundMaterial)
        "hiddenRepos" -> JsonArray(hiddenRepos.sorted().map(::JsonPrimitive))
        "inboxFilter" -> JsonPrimitive(inboxFilter)
        else -> null
    }

    /**
     * Takes a setting another phone changed later than this one: saved and shown at once, without counting it as a change
     * made here or sending it back. False for a name or a value this version does not know, which is left as it is.
     */
    fun applySynced(name: String, value: JsonElement): Boolean {
        val text = (value as? JsonPrimitive)?.takeIf { it.isString }?.content
        val flag = (value as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull
        val number = (value as? JsonPrimitive)?.takeIf { !it.isString }?.floatOrNull
        val edit = prefs.edit()
        when (name) {
            "theme" -> { theme = text?.takeIf { it in listOf("system", "light", "dark") } ?: return false; edit.putString(name, theme) }
            "colorTheme" -> { colorTheme = text ?: return false; edit.putString(name, colorTheme) }
            "locale" -> { locale = text?.takeIf { code -> Messages.LOCALES.any { it.first == code } } ?: return false; edit.putString(name, locale) }
            "layout" -> { layout = text?.takeIf { it == "split" || it == "unified" } ?: return false; edit.putLayout(layout) }
            "autoRefresh" -> { autoRefresh = flag ?: return false; edit.putBoolean(name, autoRefresh) }
            "showThreads" -> { showThreads = flag ?: return false; edit.putBoolean(name, showThreads) }
            "effort" -> { effort = text ?: return false; edit.putString(name, effort) }
            "analyzeChoice" -> { analyzeChoice = AnalyzeChoice.decode(text ?: return false); edit.putString(name, analyzeChoice.encode()) }
            "codeZoom" -> { codeZoom = number?.coerceIn(MIN_ZOOM, MAX_ZOOM) ?: return false; edit.putFloat(name, codeZoom) }
            "codeWrap" -> { codeWrap = flag ?: return false; edit.putBoolean(name, codeWrap) }
            "appFont" -> { appFont = text ?: return false; edit.putString(name, appFont) }
            "codeFont" -> { codeFont = text ?: return false; edit.putString(name, codeFont) }
            "sounds" -> { sounds = flag ?: return false; edit.putBoolean(name, sounds) }
            "haptics" -> { haptics = flag ?: return false; edit.putBoolean(name, haptics) }
            "soundMaterial" -> { soundMaterial = text ?: return false; edit.putString(name, soundMaterial) }
            "inboxFilter" -> { inboxFilter = text?.takeIf { it in Triage.inboxFilters } ?: return false; edit.putString(name, inboxFilter) }
            "hiddenRepos" -> {
                hiddenRepos = (value as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }?.toSet() ?: return false
                edit.putStringSet(name, hiddenRepos)
            }
            else -> return false
        }
        edit.apply()
        return true
    }

    /** `settings.change`: which setting and its new value, only ever a fixed choice or on and off. */
    private fun changed(key: String, value: Any) =
        Analytics.track("settings.change", "key" to key, "value" to (if (value is Boolean) (if (value) "on" else "off") else value))

    companion object {
        const val MIN_ZOOM = 0.6f
        const val MAX_ZOOM = 2f
        @Volatile private var instance: AppSettings? = null
        fun get(context: Context): AppSettings = instance ?: synchronized(this) {
            instance ?: AppSettings(context.applicationContext).also { instance = it }
        }
    }
}
