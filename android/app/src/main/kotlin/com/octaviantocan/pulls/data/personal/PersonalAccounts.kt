package com.octaviantocan.pulls.data.personal

import android.content.Context
import com.octaviantocan.pulls.BuildConfig
import com.octaviantocan.pulls.data.PersonalAccountRuntime
import com.octaviantocan.pulls.data.PersonalAuthOrigin
import com.octaviantocan.pulls.data.PersonalSessionVault
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.time.Instant

/** One UI-confined secure vault across activities; pending cleanup outlives an individual sheet. */
object PersonalAccounts {
    private var vault: PersonalSessionVault? = null
    private val cleanup = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private fun store(context: Context): PersonalSessionVault = vault ?:
        PersonalSessionVault(PersonalSecureStorage(context.applicationContext)).also { vault = it }

    /** Local preferences only: a saved record never verifies a profile or authorizes a request. */
    fun settingsNamespace(context: Context): String? = try {
        val origin = BuildConfig.PERSONAL_AUTH_ORIGIN.takeIf { it.isNotEmpty() }?.let { PersonalAuthOrigin.parse(it) }
        // Workers must not share the UI-confined mutable runtime vault. Read one independent local snapshot.
        origin?.let { savedAccountSettingsNamespace(it, PersonalSessionVault(PersonalSecureStorage(context.applicationContext))) }
    } catch (_: Exception) { null }

    fun runtime(context: Context, scope: CoroutineScope, isCurrent: () -> Boolean, onIdentityChanged: () -> Unit): PersonalAccountRuntime {
        val store = store(context)
        val origin = BuildConfig.PERSONAL_AUTH_ORIGIN.takeIf { it.isNotEmpty() }?.let { PersonalAuthOrigin.parse(it) }
        return PersonalAccountRuntime(origin, store, scope, cleanup, { Instant.now().epochSecond }, isCurrent, onIdentityChanged,
            workspaceHeaders = { server -> com.octaviantocan.pulls.data.ServerStore.bakedHeaders(server, "/workspace/api/rpc") })
    }
}
