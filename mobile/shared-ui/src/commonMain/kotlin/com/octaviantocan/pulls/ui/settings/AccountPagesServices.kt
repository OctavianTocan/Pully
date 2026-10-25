package com.octaviantocan.pulls.ui.settings

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.octaviantocan.pulls.data.AgentKey
import com.octaviantocan.pulls.data.Repo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** The one-time key never enters preferences or saved route state. */
open class AccountPageState {
    var keys by mutableStateOf<List<AgentKey>?>(null)
    var opened by mutableStateOf<AgentKey?>(null)
    var made by mutableStateOf<Pair<String, AgentKey>?>(null)
    fun clear() { made = null; opened = null; keys = null }
}

interface AccountPagesServices {
    val email: String?
    val ui: AccountPageState
    /** Null retains the existing-server key flow; otherwise both key pages explain this capability limit. */
    val agentKeysUnavailableReason: String? get() = null
    fun current(): Boolean
    suspend fun keys(): List<AgentKey>
    suspend fun create(name: String): Pair<String, AgentKey>
    suspend fun revoke(id: String)
    suspend fun signOut()
    fun copy(key: String): Boolean
    /** The root invokes this synchronously before replacement credentials become usable. */
    fun close()
}

/** All key RPCs use the existing shared client, with no native account API duplicate. */
class SourceAccountPagesServices(
    private val repo: Repo,
    private val readEmail: () -> String?,
    private val owner: () -> Boolean,
    private val signOutAccount: suspend () -> Unit,
    private val clipboard: (String) -> Unit,
    override val ui: AccountPageState = AccountPageState(),
    override val agentKeysUnavailableReason: String? = null,
) : AccountPagesServices {
    private val opening = runCatching { repo.source() }
    private var closed = false
    override fun current() = !closed && owner() && opening.getOrNull()?.let(repo::isCurrent) == true
    override val email: String? get() = if (current()) readEmail() else null
    private fun requireCurrent() { if (!current()) throw CancellationException("The account settings source changed.") }
    private suspend fun check() { currentCoroutineContext().ensureActive(); requireCurrent() }
    private suspend fun checkKeys() { check(); agentKeysUnavailableReason?.let { throw IllegalStateException(it) } }
    override suspend fun keys(): List<AgentKey> { checkKeys(); return repo.agentKeys().also { checkKeys() } }
    override suspend fun create(name: String): Pair<String, AgentKey> { checkKeys(); return repo.createAgentKey(name).also { checkKeys() } }
    override suspend fun revoke(id: String) { checkKeys(); repo.revokeAgentKey(id); checkKeys() }
    override suspend fun signOut() { check(); signOutAccount() } // Successful sign-out deliberately invalidates this owner.
    override fun copy(key: String): Boolean {
        if (!current() || agentKeysUnavailableReason != null) return false
        clipboard(key)
        return true
    }
    override fun close() {
        if (closed) return
        closed = true
        ui.clear()
    }
}
