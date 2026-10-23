package com.octaviantocan.pulls.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** A failed native credential deletion keeps the account page available for a visible retry. */
internal class AccountSignOutState(private val services: AccountPagesServices) {
    private var closed = false
    var busy by mutableStateOf(false)
        private set
    var problem by mutableStateOf<String?>(null)
        private set

    suspend fun signOut() {
        if (closed || busy || !services.current()) return
        busy = true
        problem = null
        try {
            services.signOut()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            currentCoroutineContext().ensureActive()
            if (!closed && services.current()) problem = "Pully could not sign out. Try again."
        } finally {
            if (!closed) busy = false
        }
    }

    fun close() { closed = true; busy = false; problem = null }
}

@Composable
internal fun rememberAccountSignOutState(services: AccountPagesServices): AccountSignOutState {
    val state = remember(services) { AccountSignOutState(services) }
    DisposableEffect(state) { onDispose { state.close() } }
    return state
}
