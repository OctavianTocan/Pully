package com.octaviantocan.pulls.data

import android.content.Context
import com.octaviantocan.pulls.BuildConfig
import com.octaviantocan.pulls.notifications.DeviceNotifications
import com.octaviantocan.pulls.ui.explore.NativeEntryMode
import com.octaviantocan.pulls.ui.explore.NativeEntryState
import com.octaviantocan.pulls.update.UpdateChecks

/** Device-local entry choice. Browsing publicly never signs out or adopts the saved private connection. */
object NativeEntry {
    @Volatile private var state: NativeEntryState? = null

    @Synchronized fun get(context: Context): NativeEntryState {
        state?.let { return it }
        Account.load(context)
        val prefs = context.getSharedPreferences("pully-entry", Context.MODE_PRIVATE)
        var choice = NativeEntryMode.entries.firstOrNull { it.name == prefs.getString("mode", null) }
        if (BuildConfig.UPDATE_CHANNEL == "staging/" && sameAccountServer(ServerStore.load(context).url, BuildConfig.DEFAULT_SERVER)) {
            val introduced = prefs.getBoolean("githubEntryIntroduced", false)
            if (!introduced) {
                context.getSharedPreferences("pully-explore", Context.MODE_PRIVATE).edit().putBoolean("welcomeSeen", false).apply()
            }
            if (!introduced || choice != NativeEntryMode.Explore) {
                prefs.edit().putString("mode", NativeEntryMode.Explore.name).putBoolean("githubEntryIntroduced", true).apply()
            }
            choice = NativeEntryMode.Explore
        }
        return NativeEntryState(Account.connectionVerified, choice).also { state = it }
    }

    fun privateConnection(context: Context): Boolean = get(context).mode == NativeEntryMode.ExistingServer

    fun select(context: Context, mode: NativeEntryMode) {
        val entry = get(context)
        if (entry.mode == mode) return
        entry.select(mode)
        RepoIdentity.invalidate()
        context.getSharedPreferences("pully-entry", Context.MODE_PRIVATE).edit().putString("mode", mode.name).apply()
        if (mode == NativeEntryMode.Explore) AiWork.clear(context)
        Analytics.setPrivateConnection(mode == NativeEntryMode.ExistingServer)
        UpdateChecks.sync(context)
        DeviceNotifications.sync(context)
    }
}
