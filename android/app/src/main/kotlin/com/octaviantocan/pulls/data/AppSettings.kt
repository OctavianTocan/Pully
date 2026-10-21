package com.octaviantocan.pulls.data

import android.content.Context
import com.octaviantocan.pulls.settings.SettingsEffects
import com.octaviantocan.pulls.settings.SharedPreferencesSettings

/** Android factory retained while screens move to the shared reactive settings implementation. */
class AppSettings private constructor(context: Context) : com.octaviantocan.pulls.settings.AppSettings(
    SharedPreferencesSettings(context.getSharedPreferences("pulls-ui", Context.MODE_PRIVATE)),
    object : SettingsEffects {
        override fun touched(name: String) = SettingsSync.touched(context, name)
        override fun settingsChanged(key: String, value: String) = Analytics.track("settings.change", "key" to key, "value" to value)
        override fun usageStatsEnabled(enabled: Boolean) = Analytics.setEnabled(enabled)
    },
) {
    companion object {
        const val MIN_ZOOM = com.octaviantocan.pulls.settings.AppSettings.MIN_ZOOM
        const val MAX_ZOOM = com.octaviantocan.pulls.settings.AppSettings.MAX_ZOOM
        @Volatile private var instance: AppSettings? = null
        fun get(context: Context): AppSettings = instance ?: synchronized(this) {
            instance ?: AppSettings(context.applicationContext).also { instance = it }
        }
    }
}
