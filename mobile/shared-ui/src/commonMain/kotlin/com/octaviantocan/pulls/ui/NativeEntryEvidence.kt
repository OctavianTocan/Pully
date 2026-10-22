package com.octaviantocan.pulls.ui

import com.octaviantocan.pulls.data.sourceStorageNamespace
import com.octaviantocan.pulls.settings.SettingsPreferences
import com.octaviantocan.pulls.ui.explore.NativeEntryMode
import com.octaviantocan.pulls.ui.explore.NativeEntryState
import com.octaviantocan.pulls.ui.settings.AccountPhase
import com.octaviantocan.pulls.ui.settings.AccountRuntime
import com.octaviantocan.pulls.ui.settings.AccountStorage

/** Nonsecret local evidence, separate from credentials restored by Keychain after a reinstall. */
class NativeEntryEvidence(private val preferences: SettingsPreferences, private val storage: AccountStorage) {
    fun entry(): NativeEntryState {
        val choice = NativeEntryMode.entries.firstOrNull { it.name == preferences.getString("mode", null) }
        val account = storage.account()
        val namespace = endpoint()
        val marker = preferences.getString("verified", null)?.takeIf { it.matches(Regex("[0-9a-f]{64}")) }
        val matching = namespace != null && namespace == marker
        val requiresSession = preferences.getBoolean("requiresSession", true)
        val storageRecovery = namespace == null && marker != null && account.problem != null
        val verified = storageRecovery || namespace != null && (account.email != null || matching &&
            (!requiresSession || account.verificationRequired || account.problem != null))
        return NativeEntryState(verified, choice)
    }

    fun selected(mode: NativeEntryMode) { preferences.edit().putString("mode", mode.name).apply() }

    fun verified(runtime: AccountRuntime) {
        val state = runtime.state
        if (state.phase != AccountPhase.Ready || state.problem != null) return
        val namespace = endpoint() ?: return
        preferences.edit().putString("verified", namespace)
            .putBoolean("requiresSession", runtime.serverHasAccount == true).apply()
    }

    private fun endpoint(): String? = storage.connection(false)?.let {
        sourceStorageNamespace(it.server, null, false, it.headers)
    }
}
