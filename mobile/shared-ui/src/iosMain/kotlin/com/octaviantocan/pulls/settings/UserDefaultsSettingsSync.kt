package com.octaviantocan.pulls.settings

import platform.Foundation.NSUserDefaults

/** Nonsecret sync times only; credentials remain in the scene's Keychain adapter. */
class UserDefaultsSettingsSync(
    private val defaults: NSUserDefaults = NSUserDefaults.standardUserDefaults,
) : SettingsSyncStore {
    private fun key(name: String) = "pully-settings-sync.$name"
    override val seeded: Boolean get() = defaults.boolForKey(key("seeded"))
    override val owner: String? get() = defaults.stringForKey(key("owner"))
    override fun times(names: List<String>): Map<String, Long> = names.associateWith { defaults.integerForKey(key(it)) }
    override fun write(times: Map<String, Long>, seeded: Boolean?, owner: String?) {
        times.forEach { (name, value) -> defaults.setInteger(value, forKey = key(name)) }
        seeded?.let { defaults.setBool(it, forKey = key("seeded")) }
        owner?.let { defaults.setObject(it, forKey = key("owner")) }
    }
}
