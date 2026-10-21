package com.octaviantocan.pulls.settings

import com.octaviantocan.pulls.ui.design.Messages
import platform.Foundation.*

/** Nonsecret preferences only. Session and Access credentials belong to the host's secure storage. */
class UserDefaultsSettings(
    private val defaults: NSUserDefaults = NSUserDefaults.standardUserDefaults,
    private val namespace: String = "pulls-ui",
) : SettingsPreferences {
    private fun key(name: String) = "$namespace.$name"

    override fun getString(name: String, default: String?): String? = defaults.stringForKey(key(name)) ?: default
    override fun getBoolean(name: String, default: Boolean): Boolean =
        if (contains(name)) defaults.boolForKey(key(name)) else default
    override fun getFloat(name: String, default: Float): Float =
        if (contains(name)) defaults.floatForKey(key(name)) else default
    override fun getStringSet(name: String, default: Set<String>?): Set<String>? =
        defaults.stringArrayForKey(key(name))?.filterIsInstance<String>()?.toSet() ?: default
    override fun contains(name: String): Boolean = defaults.objectForKey(key(name)) != null
    override fun edit(): SettingsPreferences.Editor = Editor()

    private inner class Editor : SettingsPreferences.Editor {
        private val changes = mutableListOf<() -> Unit>()

        override fun putString(name: String, value: String?) = apply {
            changes += {
                if (value == null) defaults.removeObjectForKey(key(name))
                else defaults.setObject(value, forKey = key(name))
            }
        }

        override fun putBoolean(name: String, value: Boolean) = apply { changes += { defaults.setBool(value, forKey = key(name)) } }
        override fun putFloat(name: String, value: Float) = apply { changes += { defaults.setFloat(value, forKey = key(name)) } }
        override fun putStringSet(name: String, value: Set<String>) = apply {
            val copy = value.toList()
            changes += { defaults.setObject(copy, forKey = key(name)) }
        }

        override fun apply() {
            changes.forEach { it() }
            changes.clear()
        }
    }
}

/** Effects are supplied by the real host; constructing settings never starts synchronization or analytics. */
fun createIosSettings(effects: SettingsEffects, defaultLocale: () -> String = Messages::detect): AppSettings =
    AppSettings(UserDefaultsSettings(), effects, defaultLocale)
