package com.octaviantocan.pulls.settings

import android.content.SharedPreferences

/** Uses the existing `pulls-ui` file; no settings migration or alternate keys are introduced. */
class SharedPreferencesSettings(private val preferences: SharedPreferences) : SettingsPreferences {
    override fun getString(name: String, default: String?): String? = preferences.getString(name, default)
    override fun getBoolean(name: String, default: Boolean): Boolean = preferences.getBoolean(name, default)
    override fun getFloat(name: String, default: Float): Float = preferences.getFloat(name, default)
    override fun getStringSet(name: String, default: Set<String>?): Set<String>? = preferences.getStringSet(name, default)?.toSet()
    override fun contains(name: String): Boolean = preferences.contains(name)
    override fun edit(): SettingsPreferences.Editor = Editor(preferences.edit())

    private class Editor(private val editor: SharedPreferences.Editor) : SettingsPreferences.Editor {
        override fun putString(name: String, value: String?) = apply { editor.putString(name, value) }
        override fun putBoolean(name: String, value: Boolean) = apply { editor.putBoolean(name, value) }
        override fun putFloat(name: String, value: Float) = apply { editor.putFloat(name, value) }
        override fun putStringSet(name: String, value: Set<String>) = apply { editor.putStringSet(name, value.toSet()) }
        override fun apply() = editor.apply()
    }
}
