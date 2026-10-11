package com.octaviantocan.pulls.data

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.octaviantocan.pulls.ui.design.Messages

/**
 * The web app's local preferences (`state/settings.ts`, `state/layout.ts`, `state/dark.ts`,
 * `state/auto-refresh.ts`, `diffs:show-review-comments`), kept in SharedPreferences and
 * exposed as Compose state so the theme, language and diff layout react at once.
 */
class AppSettings private constructor(private val context: Context) {
    private val prefs = context.getSharedPreferences("pulls-ui", Context.MODE_PRIVATE)

    /** `system`, `dark` or `light` (the web `DarkToggle` cycles dark/light; `system` until first toggled). */
    var theme by mutableStateOf(prefs.getString("theme", "system") ?: "system")
        private set

    /** A code from [Messages.LOCALES]. */
    var locale by mutableStateOf(prefs.getString("locale", null) ?: Messages.detect())
        private set

    /** `split` or `unified`; the web defaults to split. */
    var layout by mutableStateOf(prefs.getString("layout", null)?.lowercase() ?: "split")
        private set

    var autoRefresh by mutableStateOf(prefs.getBoolean("autoRefresh", false))
        private set

    var showThreads by mutableStateOf(prefs.getBoolean("showThreads", true))
        private set

    /** AI effort for the selected model; empty uses the CLI default. */
    var effort by mutableStateOf(prefs.getString("effort", "") ?: "")
        private set

    fun updateTheme(value: String) { theme = value; prefs.edit().putString("theme", value).apply() }
    fun updateLocale(value: String) { locale = value; prefs.edit().putString("locale", value).apply() }
    fun updateLayout(value: String) { layout = value; prefs.edit().putString("layout", value.uppercase()).apply() }
    fun updateAutoRefresh(value: Boolean) { autoRefresh = value; prefs.edit().putBoolean("autoRefresh", value).apply() }
    fun updateShowThreads(value: Boolean) { showThreads = value; prefs.edit().putBoolean("showThreads", value).apply() }
    fun updateEffort(value: String) { effort = value; prefs.edit().putString("effort", value).apply() }

    companion object {
        @Volatile private var instance: AppSettings? = null
        fun get(context: Context): AppSettings = instance ?: synchronized(this) {
            instance ?: AppSettings(context.applicationContext).also { instance = it }
        }
    }
}
