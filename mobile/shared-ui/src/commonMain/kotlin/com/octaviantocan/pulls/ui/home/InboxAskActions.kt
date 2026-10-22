package com.octaviantocan.pulls.ui.home

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import com.octaviantocan.pulls.data.AskTurn
import com.octaviantocan.pulls.data.TaskState
import com.octaviantocan.pulls.ui.ai.AiCapabilities
import com.octaviantocan.pulls.ui.ai.LocalAiCapabilities

/** Observation follows the actual mounted sheet and native foreground; writes retain their source owner. */
internal class InboxAskMount(private val ask: InboxAsk, private val policy: () -> AiCapabilities) : HomeLifecycleObserver {
    private var closed = false
    private var subscription: HomeLifecycleSubscription? = null
    private var foreground by mutableStateOf(false)
    val canRead: Boolean get() = !closed && ask.canRead && policy().canReadResults
    val observing: Boolean get() = foreground && ask.observations.ready && canRead
    val canAsk: Boolean get() = observing && ask.canAsk && policy().canAsk

    fun attach() {
        if (closed || !ask.owned || subscription != null) return
        ask.mount(this)
        val registration = ask.native.observeLifecycle(this)
        if (closed) registration.cancel() else subscription = registration
    }
    fun policyChanged() { if (!closed) ask.reconcileObservation() }
    override fun started() = resumed()
    override fun resumed() { if (!closed) { foreground = true; ask.reconcileObservation() } }
    override fun stopped() { if (!closed) { foreground = false; ask.reconcileObservation() } }
    fun close() {
        if (closed) return
        closed = true; foreground = false
        subscription?.cancel(); subscription = null
        ask.unmount(this)
    }
}

/** Every callback belongs to the conversation drawn by this actual sheet. */
internal class InboxAskActions(private val ask: InboxAsk, private val mount: InboxAskMount) {
    private val revision = ask.revision
    private var closed = false
    val current: Boolean get() = !closed && mount.observing && ask.owned && ask.revision == revision
    val canAsk: Boolean get() = current && mount.canAsk
    fun close() { closed = true }
    fun read(action: () -> Unit): Boolean {
        if (!current) return false
        action()
        return true
    }
    fun input(value: String) { if (canAsk) ask.setInput(value) }
    fun send() { if (canAsk) ask.sendInput { mount.canAsk } }
    fun ask(question: String) { if (canAsk) ask.sendOrQueue(question) { mount.canAsk } }
    fun retry(turn: AskTurn) { if (canAsk && !ask.busy) ask.retry(turn) { mount.canAsk } }
    fun clear() { if (canAsk && !ask.busy) ask.clear { mount.canAsk } }
    fun stop(task: TaskState?) { if (canAsk) ask.stop(task) { canAsk } }
    fun reconnect() { if (current) ask.retryObservation() }
    fun copy(text: String): Boolean = read { ask.native.copyText(text) }
}

@Composable
internal fun rememberInboxAskMount(ask: InboxAsk): InboxAskMount {
    val policy by rememberUpdatedState(LocalAiCapabilities.current)
    val mount = remember(ask) { InboxAskMount(ask) { policy } }
    DisposableEffect(mount) { mount.attach(); onDispose { mount.close() } }
    SideEffect { mount.policyChanged() }
    return mount
}

@Composable
internal fun rememberInboxAskActions(ask: InboxAsk, mount: InboxAskMount): InboxAskActions {
    val actions = remember(ask, mount, ask.revision) { InboxAskActions(ask, mount) }
    DisposableEffect(actions) { onDispose { actions.close() } }
    return actions
}
