package com.octaviantocan.pulls.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.octaviantocan.pulls.sound.Cue
import com.octaviantocan.pulls.sound.Emphasis
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

enum class AiPiCredentialMode { Keep, Replace, Clear }

/** Retained by the real source, not by a sheet. Page disposal stops reads and fresh actions, not accepted writes. */
class AiConnectionsState(
    private val services: AiConnectionsServices,
    parentScope: CoroutineScope,
    private val feedback: AiConnectionsFeedback,
) {
    private val account = services.accountId
    private val lifetime = SupervisorJob(parentScope.coroutineContext[Job])
    private val scope = CoroutineScope(parentScope.coroutineContext + lifetime)
    private var closed = false
    private var readSequence = 0L
    private var writeSequence = 0L
    private var loader: Job? = null
    private var observation: AiConnectionsSubscription? = null
    private val mounts = mutableSetOf<AiConnectionsMount>()
    private val writes = mutableMapOf<AiConnectionKind, Long>()

    private var views by mutableStateOf<Map<AiConnectionKind, AiConnectionView>>(emptyMap())
    private var working by mutableStateOf<Set<AiConnectionKind>>(emptySet())
    private var readProblem by mutableStateOf<AiConnectionProblem?>(null)
    private var writeProblems by mutableStateOf<Map<AiConnectionKind, AiConnectionProblem>>(emptyMap())
    private var reading by mutableStateOf(false)
    private var pi by mutableStateOf(PiDraft())
    private var piInitialized = false
    private var draftSequence = 0L
    private var draftDirty = false

    init {
        val registration = services.reads.observe { ready ->
            if (!closed) {
                if (!ready) stopReading()
                else if (mounts.isNotEmpty()) refresh()
            }
        }
        if (closed) registration.cancel() else observation = registration
    }

    internal val owned: Boolean get() {
        if (!closed && (!lifetime.isActive || !runCatching(services::current).getOrDefault(false))) close()
        return !closed
    }
    internal val ready: Boolean get() = owned && services.reads.ready
    val loading: Boolean get() = owned && reading
    val loadError: AiConnectionProblem? get() = if (owned) readProblem else null
    val sharedDefault: AiSharedDefaultStatus get() = if (owned) services.sharedDefault else AiSharedDefaultStatus.Unavailable
    fun view(kind: AiConnectionKind): AiConnectionView? = if (owned) views[kind] else null
    fun busy(kind: AiConnectionKind): Boolean = owned && kind in working
    fun error(kind: AiConnectionKind): AiConnectionProblem? = if (owned) writeProblems[kind] else null
    val endpointInput: String get() = if (owned) pi.endpoint else ""
    val modelInput: String get() = if (owned) pi.model else ""
    val credentialInput: String get() = if (owned) pi.credential else ""
    val credentialMode: AiPiCredentialMode get() = if (owned) pi.mode else AiPiCredentialMode.Replace

    /** Source retirement is synchronous, including when a pending transport ignores cancellation. */
    fun close() {
        if (closed) return
        closed = true
        stopReading()
        observation?.cancel(); observation = null
        views = emptyMap(); working = emptySet(); readProblem = null; writeProblems = emptyMap()
        pi = PiDraft(); piInitialized = false; draftDirty = false; draftSequence++
        mounts.toList().forEach { it.close() }; mounts.clear(); writes.clear()
        scope.cancel()
    }

    internal fun mount(mount: AiConnectionsMount) {
        if (!owned) return
        mounts += mount
        refresh()
    }

    internal fun unmount(mount: AiConnectionsMount) {
        mounts -= mount
        if (mount.kind == AiConnectionKind.PiEndpoint && mounts.none { it.kind == AiConnectionKind.PiEndpoint }) clearCredentialInput()
        if (mounts.isEmpty()) stopReading()
    }

    private fun stopReading() {
        readSequence++
        loader?.cancel(); loader = null; reading = false
    }

    internal fun refresh(restart: Boolean = false) {
        if (!ready || mounts.isEmpty() || working.isNotEmpty() || !restart && loader?.isActive == true) return
        stopReading()
        val request = readSequence
        reading = true
        loader = scope.launch {
            try {
                val result = services.reads.read { services.load() }
                currentCoroutineContext().ensureActive()
                if (!ownsRead(request)) return@launch
                require(result.size == AiConnectionKind.entries.size && result.map { it.kind }.toSet() == AiConnectionKind.entries.toSet())
                require(result.all { it.accountId == account })
                views = result.associate { it.kind to it.copy(operations = it.operations.toSet()) }
                readProblem = null
                if (!piInitialized || !draftDirty) seedPi(views[AiConnectionKind.PiEndpoint])
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                currentCoroutineContext().ensureActive()
                if (ownsRead(request)) readProblem = failure.problem()
            } finally {
                if (ownsRead(request)) reading = false
            }
        }
    }

    private fun ownsRead(request: Long) = owned && readSequence == request && ready && mounts.isNotEmpty()

    internal fun canDispatch(kind: AiConnectionKind, operation: AiConnectionOperation, expected: AiConnectionVersion?, permitted: () -> Boolean): Boolean {
        if (!ready || !permitted() || kind in working) return false
        return eligible(kind, operation, expected, permitted)
    }

    private fun eligible(kind: AiConnectionKind, operation: AiConnectionOperation, expected: AiConnectionVersion?, permitted: () -> Boolean): Boolean {
        if (!ready || !permitted()) return false
        val latest = views[kind] ?: return false
        return latest.accountId == account && latest.version == expected && operation in latest.operations && ready && permitted()
    }

    internal fun connect(kind: AiConnectionKind, expected: AiConnectionVersion?, permitted: () -> Boolean): Boolean =
        mutate(kind, AiConnectionOperation.Connect, expected, permitted) { services.connect(kind, expected) }

    internal fun remove(kind: AiConnectionKind, expected: AiConnectionVersion?, permitted: () -> Boolean): Boolean {
        val version = expected ?: return false
        return mutate(kind, AiConnectionOperation.Remove, version, permitted) { services.remove(kind, version) }
    }

    internal fun configurePi(expected: AiConnectionVersion?, permitted: () -> Boolean): Boolean {
        val change = piChange() ?: return false
        return mutate(AiConnectionKind.PiEndpoint, AiConnectionOperation.ConfigurePi, expected, permitted) {
            services.configurePi(change, expected)
        }
    }

    internal val validPiInput: Boolean get() = piChange() != null
    private fun piChange(): AiPiChange? = if (!owned) null else runCatching {
        val current = views[AiConnectionKind.PiEndpoint] ?: return@runCatching null
        val endpoint = pi.endpoint.trim().ifEmpty { null }
        require(endpoint != null || current.version != null)
        require(endpoint == null || pi.mode != AiPiCredentialMode.Keep)
        val credential = when (pi.mode) {
            AiPiCredentialMode.Keep -> { require(current.hasCredential && current.version != null); AiCredentialWrite.Keep }
            AiPiCredentialMode.Clear -> AiCredentialWrite.Clear
            AiPiCredentialMode.Replace -> AiCredentialWrite.Replace(pi.credential)
        }
        AiPiChange(endpoint, pi.model.trim(), credential)
    }.getOrNull()

    private fun mutate(
        kind: AiConnectionKind,
        operation: AiConnectionOperation,
        expected: AiConnectionVersion?,
        permitted: () -> Boolean,
        request: suspend () -> AiConnectionView,
    ): Boolean {
        if (!canDispatch(kind, operation, expected, permitted)) return false
        val ticket = ++writeSequence
        val submittedDraft = draftSequence
        writes[kind] = ticket
        working = working + kind
        writeProblems = writeProblems - kind
        stopReading() // An earlier captured list cannot overwrite this write receipt.
        if (operation == AiConnectionOperation.ConfigurePi) pi = pi.copy(credential = "")
        scope.launch {
            try {
                if (!eligible(kind, operation, expected, permitted)) return@launch
                val result = request()
                currentCoroutineContext().ensureActive()
                if (!ownsWrite(kind, ticket)) return@launch
                require(result.kind == kind && result.accountId == account)
                views = views + (kind to result.copy(operations = result.operations.toSet()))
                if (kind == AiConnectionKind.PiEndpoint && operation != AiConnectionOperation.Connect && draftSequence == submittedDraft) seedPi(result)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                currentCoroutineContext().ensureActive()
                if (ownsWrite(kind, ticket)) {
                    writeProblems = writeProblems + (kind to failure.problem())
                    if (ready && mounts.isNotEmpty()) feedback.play(Cue.Error, Emphasis.Subtle)
                }
            } finally {
                if (ownsWrite(kind, ticket)) {
                    writes -= kind
                    working = working - kind
                    refresh()
                }
            }
        }
        return true
    }

    private fun ownsWrite(kind: AiConnectionKind, ticket: Long) = owned && writes[kind] == ticket

    internal fun editEndpoint(value: String) { if (value.encodeToByteArray().size <= 2048) editPi { copy(endpoint = value) } }
    internal fun editModel(value: String) { if (value.encodeToByteArray().size <= 256) editPi { copy(model = value) } }
    internal fun editCredential(value: String) { if (value.length <= 16_384) editPi { copy(credential = value) } }
    internal fun editCredentialMode(value: AiPiCredentialMode) = editPi { copy(mode = value, credential = if (value == AiPiCredentialMode.Replace) credential else "") }
    private fun editPi(change: PiDraft.() -> PiDraft) {
        if (!ready) return
        pi = pi.change(); draftDirty = true; draftSequence++
    }
    private fun clearCredentialInput() {
        if (pi.credential.isEmpty()) return
        pi = pi.copy(credential = ""); draftSequence++
    }
    private fun seedPi(view: AiConnectionView?) {
        pi = PiDraft(model = view?.preferredModel.orEmpty(), mode = if (view?.hasCredential == true) AiPiCredentialMode.Keep else AiPiCredentialMode.Replace)
        piInitialized = true; draftDirty = false
    }
}

private data class PiDraft(val endpoint: String = "", val model: String = "", val credential: String = "", val mode: AiPiCredentialMode = AiPiCredentialMode.Replace) {
    override fun toString() = "PiDraft([redacted])"
}

private fun Exception.problem(): AiConnectionProblem = (this as? AiConnectionException)?.problem ?: AiConnectionProblem.Unavailable

internal class AiConnectionsMount(private val state: AiConnectionsState, val kind: AiConnectionKind?) {
    private var closed = false
    val current: Boolean get() = !closed && state.ready
    fun attach() { if (!closed) state.mount(this) }
    fun close() { if (!closed) { closed = true; state.unmount(this) } }
}

/** Actual drawn callbacks retain their opening connection revision, not a later replacement's authority. */
internal class AiConnectionsActions(private val state: AiConnectionsState, private val mount: AiConnectionsMount, private val kind: AiConnectionKind?) {
    private val version = kind?.let { state.view(it)?.version }
    private var closed = false
    val current: Boolean get() = !closed && mount.current && (kind == null || state.view(kind)?.version == version)
    fun close() { closed = true }
    fun open(kind: AiConnectionKind, onOpen: (AiConnectionKind) -> Unit) { if (current) onOpen(kind) }
    fun refresh() { if (current) state.refresh(restart = true) }
    fun can(operation: AiConnectionOperation): Boolean = kind?.let { state.canDispatch(it, operation, version) { current } } == true
    val canSavePi: Boolean get() = kind == AiConnectionKind.PiEndpoint && can(AiConnectionOperation.ConfigurePi) && state.validPiInput
    fun connect() { if (kind != null && current) state.connect(kind, version) { current } }
    fun remove() { if (kind != null && current) state.remove(kind, version) { current } }
    fun savePi() { if (canSavePi) state.configurePi(version) { current } }
    private fun edit(action: () -> Unit) { if (kind == AiConnectionKind.PiEndpoint && current && AiConnectionOperation.ConfigurePi in state.view(kind)?.operations.orEmpty()) action() }
    fun endpoint(value: String) = edit { state.editEndpoint(value) }
    fun model(value: String) = edit { state.editModel(value) }
    fun credential(value: String) = edit { state.editCredential(value) }
    fun credentialMode(value: AiPiCredentialMode) = edit { state.editCredentialMode(value) }
}

@Composable
internal fun rememberAiConnectionsActions(state: AiConnectionsState, kind: AiConnectionKind?): AiConnectionsActions {
    val mount = remember(state, kind) { AiConnectionsMount(state, kind) }
    DisposableEffect(mount) { mount.attach(); onDispose { mount.close() } }
    val actions = remember(state, mount, kind?.let { state.view(it)?.version }) { AiConnectionsActions(state, mount, kind) }
    DisposableEffect(actions) { onDispose { actions.close() } }
    return actions
}
