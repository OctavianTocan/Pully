package com.octaviantocan.pulls.settings

/** Native storage for the existing nonsecret `pulls-ui` preferences. */
interface SettingsPreferences {
    fun getString(name: String, default: String?): String?
    fun getBoolean(name: String, default: Boolean): Boolean
    fun getFloat(name: String, default: Float): Float
    fun getStringSet(name: String, default: Set<String>?): Set<String>?
    fun contains(name: String): Boolean
    fun edit(): Editor

    interface Editor {
        fun putString(name: String, value: String?): Editor
        fun putBoolean(name: String, value: Boolean): Editor
        fun putFloat(name: String, value: Float): Editor
        fun putStringSet(name: String, value: Set<String>): Editor
        fun apply()
    }
}

/** The host keeps ownership of synchronization timestamps and its first-party analytics queue. */
interface SettingsEffects {
    fun touched(name: String)
    fun settingsChanged(key: String, value: String)
    fun usageStatsEnabled(enabled: Boolean)
    fun experimentChanged(feature: String, enabled: Boolean) {}
}
