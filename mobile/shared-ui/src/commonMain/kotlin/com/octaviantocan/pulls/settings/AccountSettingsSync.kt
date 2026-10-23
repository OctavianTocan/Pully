package com.octaviantocan.pulls.settings

import com.octaviantocan.pulls.data.Repo
import com.octaviantocan.pulls.data.SyncedSetting
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** The existing pully-settings-sync timestamps and nonsecret account ownership marker. */
interface SettingsSyncStore {
    val seeded: Boolean
    val owner: String?
    fun times(names: List<String>): Map<String, Long>
    /** Null marker arguments leave the existing marker unchanged. */
    fun write(times: Map<String, Long>, seeded: Boolean? = null, owner: String? = null)
}

/** The original per-setting newer-wins policy; native code only supplies preferences and time. */
class AccountSettingsSync(
    private val settings: AppSettings,
    private val repo: Repo,
    private val store: SettingsSyncStore,
    private val owner: () -> String?,
    private val scope: CoroutineScope,
    private val nowMillis: () -> Long,
) {
    private val running = Mutex()
    private var pending: Job? = null

    fun touched(name: String, now: Long = nowMillis()) {
        if (name !in SYNCED) return
        store.write(mapOf(name to now))
        val openingOwner = owner() ?: return
        val source = runCatching { repo.source() }.getOrNull() ?: return
        pending?.cancel()
        pending = scope.launch {
            delay(1_500)
            if (owner() == openingOwner && repo.isCurrent(source)) sync()
        }
    }

    suspend fun sync() {
        val openingOwner = owner() ?: return
        val source = runCatching { repo.source() }.getOrNull() ?: return
        running.withLock {
            if (owner() != openingOwner || !repo.isCurrent(source)) return
            seed()
            adopt(openingOwner)
            val outgoing = store.times(SYNCED).filterValues { it > 0 }.mapNotNull { (name, at) ->
                settings.syncedValue(name)?.let { name to SyncedSetting(it, at) }
            }.toMap()
            val merged = try {
                repo.mergeAccountSettings(outgoing)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                return
            }
            if (owner() != openingOwner || !repo.isCurrent(source)) return
            // A local edit made while the call was out remains newer than its reply.
            val applied = mutableMapOf<String, Long>()
            for (name in newer(store.times(SYNCED), merged)) {
                val setting = merged.getValue(name)
                if (settings.applySynced(name, setting.value)) applied[name] = setting.at
            }
            store.write(applied)
        }
    }

    fun cancelPending() { pending?.cancel(); pending = null }

    private fun seed() {
        if (store.seeded) return
        val times = store.times(SYNCED)
        store.write(SYNCED.filter { (times[it] ?: 0L) == 0L && settings.chosen(it) }.associateWith { 1L }, seeded = true)
    }

    private fun adopt(next: String) {
        val previous = store.owner
        if (previous == next) return
        store.write(disowned(previous, store.times(SYNCED)), owner = next)
    }

    companion object {
        val SYNCED = listOf(
            "theme", "colorTheme", "locale", "layout", "autoRefresh", "showThreads", "effort", "analyzeChoice",
            "codeZoom", "codeWrap", "appFont", "codeFont", "sounds", "haptics", "soundMaterial", "hiddenRepos", "inboxFilter",
        )

        fun disowned(owner: String?, times: Map<String, Long>): Map<String, Long> =
            if (owner == null) emptyMap() else times.filterValues { it > 0L }.mapValues { 1L }

        fun newer(local: Map<String, Long>, remote: Map<String, SyncedSetting>): List<String> =
            remote.filter { (name, setting) -> name in SYNCED && setting.at > (local[name] ?: 0L) }.keys.sorted()
    }
}
