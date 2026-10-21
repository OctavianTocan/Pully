package com.octaviantocan.pulls.settings

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** Device-local opt-ins. Existing settings, account sync and accessibility grants cannot enable them. */
class ExperimentalFeatures(
    private val prefs: SettingsPreferences,
    private val changed: (String, Boolean) -> Unit,
) {
    var mergeRisk by mutableStateOf(prefs.getBoolean("experiment.merge-risk", false))
        private set
    var browserHelper by mutableStateOf(prefs.getBoolean("experiment.browser-helper", false))
        private set

    fun updateMergeRisk(enabled: Boolean) {
        if (enabled == mergeRisk) return
        mergeRisk = enabled
        prefs.edit().putBoolean("experiment.merge-risk", enabled).apply()
        changed("merge-risk", enabled)
    }

    fun updateBrowserHelper(enabled: Boolean) {
        if (enabled == browserHelper) return
        browserHelper = enabled
        prefs.edit().putBoolean("experiment.browser-helper", enabled).apply()
        changed("browser-helper", enabled)
    }
}
