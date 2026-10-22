package com.octaviantocan.pulls.data

import android.content.Context
import android.content.SharedPreferences
import com.octaviantocan.pulls.settings.AccountSettingsSync
import com.octaviantocan.pulls.settings.SettingsSyncStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.withContext

/** Android's original preferences and background owner around the shared newer-wins implementation. */
object SettingsSync {
    val SYNCED = AccountSettingsSync.SYNCED
    private val scope by lazy { CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate) }
    private var shared: AccountSettingsSync? = null

    @Synchronized
    private fun engine(context: Context): AccountSettingsSync {
        shared?.let { return it }
        val app = context.applicationContext
        return AccountSettingsSync(
            AppSettings.get(app), Repo(app),
            AndroidSettingsSyncStore(app.getSharedPreferences("pully-settings-sync", Context.MODE_PRIVATE)),
            { Account.email }, scope, System::currentTimeMillis,
        ).also { shared = it }
    }

    fun touched(context: Context, name: String, now: Long = System.currentTimeMillis()) = engine(context).touched(name, now)
    suspend fun sync(context: Context) = withContext(Dispatchers.Main.immediate) { engine(context).sync() }
    fun disowned(owner: String?, times: Map<String, Long>): Map<String, Long> = AccountSettingsSync.disowned(owner, times)
    fun newer(local: Map<String, Long>, remote: Map<String, SyncedSetting>): List<String> = AccountSettingsSync.newer(local, remote)
}

private class AndroidSettingsSyncStore(private val prefs: SharedPreferences) : SettingsSyncStore {
    override val seeded: Boolean get() = prefs.getBoolean("seeded", false)
    override val owner: String? get() = prefs.getString("owner", null)
    override fun times(names: List<String>): Map<String, Long> = names.associateWith { prefs.getLong(it, 0L) }
    override fun write(times: Map<String, Long>, seeded: Boolean?, owner: String?) {
        val edit = prefs.edit()
        times.forEach { (name, value) -> edit.putLong(name, value) }
        seeded?.let { edit.putBoolean("seeded", it) }
        owner?.let { edit.putString("owner", it) }
        edit.apply()
    }
}
