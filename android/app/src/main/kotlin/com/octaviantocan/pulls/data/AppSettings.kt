package com.octaviantocan.pulls.data

import android.content.Context
import com.octaviantocan.pulls.settings.SettingsEffects
import com.octaviantocan.pulls.settings.SharedPreferencesSettings

/** Android factory retained while screens move to the shared reactive settings implementation. */
class AppSettings private constructor(context: Context, namespace: String?) : com.octaviantocan.pulls.settings.AppSettings(
    SharedPreferencesSettings(context.getSharedPreferences(namespace?.let { "pulls-ui-workspace-$it" } ?: "pulls-ui", Context.MODE_PRIVATE)),
    object : SettingsEffects {
        override fun touched(name: String) {
            if (namespace == NativeWorkspaceBinding.active?.namespace) SettingsSync.touched(context, name)
        }
        override fun settingsChanged(key: String, value: String) = Analytics.track("settings.change", "key" to key, "value" to value)
        override fun usageStatsEnabled(enabled: Boolean) = Analytics.setEnabled(enabled)
        override fun experimentChanged(feature: String, enabled: Boolean) {
            Analytics.track("experiment.toggle", "experiment" to feature, "enabled" to enabled)
            if (feature == "browser-helper" && !enabled) {
                com.octaviantocan.pulls.browser.BrowserHelperSettings.setAllowed(context, false)
            }
        }
    },
) {
    companion object {
        const val MIN_ZOOM = com.octaviantocan.pulls.settings.AppSettings.MIN_ZOOM
        const val MAX_ZOOM = com.octaviantocan.pulls.settings.AppSettings.MAX_ZOOM
        private val instances = mutableMapOf<String?, AppSettings>()
        fun get(context: Context): AppSettings = synchronized(this) {
            val namespace = NativeWorkspaceBinding.active?.namespace ?: if (NativeEntry.privateConnection(context)) null
                else com.octaviantocan.pulls.data.personal.PersonalAccounts.settingsNamespace(context) ?: "explore"
            instances.getOrPut(namespace) { AppSettings(context.applicationContext, namespace) }
        }
    }
}
