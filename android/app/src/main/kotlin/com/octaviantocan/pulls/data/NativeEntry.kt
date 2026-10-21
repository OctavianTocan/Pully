package com.octaviantocan.pulls.data

import android.content.Context
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
        val choice = context.getSharedPreferences("pully-entry", Context.MODE_PRIVATE).getString("mode", null)
        return NativeEntryState(Account.connectionVerified, NativeEntryMode.entries.firstOrNull { it.name == choice }).also { state = it }
    }

    fun privateConnection(context: Context): Boolean = get(context).mode == NativeEntryMode.ExistingServer

    fun select(context: Context, mode: NativeEntryMode) {
        val entry = get(context)
        if (entry.mode == mode) return
        // Revoke retained page/request authority before any new branch is composed.
        entry.select(mode)
        RepoIdentity.invalidate()
        context.getSharedPreferences("pully-entry", Context.MODE_PRIVATE).edit().putString("mode", mode.name).apply()
        if (mode == NativeEntryMode.Explore) AiWork.clear(context)
        Analytics.setPrivateConnection(mode == NativeEntryMode.ExistingServer)
        UpdateChecks.sync(context)
        DeviceNotifications.sync(context)
    }
}
