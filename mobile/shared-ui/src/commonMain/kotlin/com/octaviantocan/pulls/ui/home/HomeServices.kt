package com.octaviantocan.pulls.ui.home

/** Native adapters preserve the existing first-party analytics events. */
interface HomeEffects {
    fun loadError(message: String?) {}
    fun action(action: String, value: String) {}
}
object NoHomeEffects : HomeEffects

/** Binds an unchanged string-store format to a native source lease. */
class GuardedKeyValueStore(private val storage: KeyValueStore, private val valid: () -> Boolean) : KeyValueStore {
    override val current: Boolean get() = storage.current && valid()
    override fun get(key: String): String? = if (current) storage.get(key) else null
    override fun set(key: String, value: String) { if (current) storage.set(key, value) }
}

/** Explicit route owner: native identity invalidation clears every private reactive Inbox slice. */
class HomeState(val sync: MyPullsSync, val archive: ArchiveStore, val tours: TourIndex, val layout: InboxLayout) {
    fun invalidate() { sync.invalidate(); archive.invalidate(); tours.invalidate(); layout.invalidate() }
}
