package com.octaviantocan.pulls.data

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The settings the account keeps for every phone signed in to it (plan 13; "start saving settings, both on the mobile
 * app and also sync it": Tavi, 16 Oct 12:47). Each one carries when it was last changed, and the newer change wins, one
 * setting at a time, so two phones never undo each other's other choices. What belongs to this phone stays here: the
 * server and its Access secrets, the session, Update notifications (Android's permission is per phone) and Usage stats.
 */
object SettingsSync {
    /** The settings that travel, by their name in [AppSettings]. */
    val SYNCED = listOf(
        "theme", "colorTheme", "locale", "layout", "autoRefresh", "showThreads", "effort", "analyzeChoice",
        "codeZoom", "codeWrap", "appFont", "codeFont", "sounds", "haptics", "soundMaterial", "hiddenRepos",
    )

    private const val PREFS = "pully-settings-sync"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val running = Mutex()
    private var pending: Job? = null

    /** Notes that [name] changed on this phone now, and sends it a moment later, so a run of changes goes as one call. */
    fun touched(context: Context, name: String, now: Long = System.currentTimeMillis()) {
        if (name !in SYNCED) return
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putLong(name, now).apply()
        if (Account.email == null) return
        pending?.cancel()
        pending = scope.launch {
            delay(1_500)
            sync(context)
        }
    }

    /**
     * Sends this phone's settings and takes the account's: each side keeps the newer of every setting. A failure leaves
     * both as they were, to try again on the next change or the next start.
     */
    suspend fun sync(context: Context) {
        if (Account.email == null) return
        running.withLock {
            val settings = AppSettings.get(context)
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val local = SYNCED.associateWith { prefs.getLong(it, 0L) }
            val outgoing = local.filterValues { it > 0 }.mapNotNull { (name, at) -> settings.syncedValue(name)?.let { name to SyncedSetting(it, at) } }.toMap()
            val merged = try {
                Repo(context).mergeAccountSettings(outgoing)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                return
            }
            val edit = prefs.edit()
            for (name in newer(local, merged)) {
                val setting = merged.getValue(name)
                if (settings.applySynced(name, setting.value)) edit.putLong(name, setting.at)
            }
            edit.apply()
        }
    }

    /** The settings the account changed after this phone did, by name: the ones to take. */
    fun newer(local: Map<String, Long>, remote: Map<String, SyncedSetting>): List<String> =
        remote.filter { (name, setting) -> name in SYNCED && setting.at > (local[name] ?: 0L) }.keys.sorted()
}
