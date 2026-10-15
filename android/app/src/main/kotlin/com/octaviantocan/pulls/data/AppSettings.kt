package com.octaviantocan.pulls.data

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.octaviantocan.pulls.core.AnalyzeChoice
import com.octaviantocan.pulls.ui.design.Messages

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

    /** A code from [Messages.LOCALES]. */
    var locale by mutableStateOf(prefs.getString("locale", null) ?: Messages.detect())
        private set

    /** `split` or `unified`; the web defaults to split. */
    var layout by mutableStateOf(prefs.getString("layout", null)?.lowercase() ?: "split")
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

    /** The face code is set in, by its `CodeFont.id`: Geist Mono until the reader picks another. */
    var codeFont by mutableStateOf(prefs.getString("codeFont", null) ?: "geist-mono")
        private set

    /** Whether taps and outcomes make a sound (the Cuelume cues); on until Settings turns it off. */
    var sounds by mutableStateOf(prefs.getBoolean("sounds", true))
        private set

    /** The material the cues are played in, by its `Material.id`: `default` until the reader picks another. */
    var soundMaterial by mutableStateOf(prefs.getString("soundMaterial", null) ?: "default")
        private set

    /** Whether the background check sends a notification when a new version of the app is out; on until Settings turns it off. */
    var updateNotifications by mutableStateOf(prefs.getBoolean("updateNotifications", true))
        private set

    /** Repositories kept out of the Inbox and its counts, as `owner/repo`; none until Profile switches one off. */
    var hiddenRepos by mutableStateOf(prefs.getStringSet("hiddenRepos", null)?.toSet() ?: emptySet())
        private set

    fun updateTheme(value: String) { theme = value; prefs.edit().putString("theme", value).apply() }
    fun updateLocale(value: String) { locale = value; prefs.edit().putString("locale", value).apply() }
    fun updateLayout(value: String) { layout = value; prefs.edit().putString("layout", value.uppercase()).apply() }
    fun updateAutoRefresh(value: Boolean) { autoRefresh = value; prefs.edit().putBoolean("autoRefresh", value).apply() }
    fun updateShowThreads(value: Boolean) { showThreads = value; prefs.edit().putBoolean("showThreads", value).apply() }
    fun updateAnalyzeChoice(value: AnalyzeChoice) { analyzeChoice = value; prefs.edit().putString("analyzeChoice", value.encode()).apply() }
    fun updateEffort(value: String) { effort = value; prefs.edit().putString("effort", value).apply() }
    fun updateCodeZoom(value: Float) {
        codeZoom = value.coerceIn(MIN_ZOOM, MAX_ZOOM)
        prefs.edit().putFloat("codeZoom", codeZoom).apply()
    }

    fun updateCodeWrap(value: Boolean) { codeWrap = value; prefs.edit().putBoolean("codeWrap", value).apply() }
    fun updateSounds(value: Boolean) { sounds = value; prefs.edit().putBoolean("sounds", value).apply() }
    fun updateSoundMaterial(value: String) { soundMaterial = value; prefs.edit().putString("soundMaterial", value).apply() }
    fun updateUpdateNotifications(value: Boolean) { updateNotifications = value; prefs.edit().putBoolean("updateNotifications", value).apply() }
    /** Switches a repository on or off for the Inbox. */
    fun setRepoShown(repo: String, shown: Boolean) {
        hiddenRepos = if (shown) hiddenRepos - repo else hiddenRepos + repo
        prefs.edit().putStringSet("hiddenRepos", hiddenRepos).apply()
    }

    fun updateCodeFont(value: String) { codeFont = value; prefs.edit().putString("codeFont", value).apply() }

    companion object {
        const val MIN_ZOOM = 0.6f
        const val MAX_ZOOM = 2f
        @Volatile private var instance: AppSettings? = null
        fun get(context: Context): AppSettings = instance ?: synchronized(this) {
            instance ?: AppSettings(context.applicationContext).also { instance = it }
        }
    }
}
