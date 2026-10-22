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

    fun runtime(context: Context, scope: CoroutineScope, isCurrent: () -> Boolean, onIdentityChanged: () -> Unit): PersonalAccountRuntime {
        val store = vault ?: PersonalSessionVault(PersonalSecureStorage(context.applicationContext)).also { vault = it }
        val origin = BuildConfig.PERSONAL_AUTH_ORIGIN.takeIf { it.isNotEmpty() }?.let { PersonalAuthOrigin.parse(it) }
        return PersonalAccountRuntime(origin, store, scope, cleanup, { Instant.now().epochSecond }, isCurrent, onIdentityChanged)
    }
}
