package com.octaviantocan.pulls.ui.home

import platform.Foundation.*

/** Same string keys and JSON as Android, under an opaque stable source namespace in the app's private defaults. */
class UserDefaultsHomeStore(
    private val namespace: String,
    private val valid: () -> Boolean,
    private val defaults: NSUserDefaults = NSUserDefaults.standardUserDefaults,
) : KeyValueStore {
    init { require(namespace.length == 64 && namespace.all { it in '0'..'9' || it in 'a'..'f' }) }
    private fun storedKey(name: String): String = "pully-home.$namespace.$name"
    override val current: Boolean get() = valid()
    override fun get(key: String): String? = if (current) defaults.stringForKey(storedKey(key)) else null
    override fun set(key: String, value: String) { if (current) defaults.setObject(value, forKey = storedKey(key)) }
}
