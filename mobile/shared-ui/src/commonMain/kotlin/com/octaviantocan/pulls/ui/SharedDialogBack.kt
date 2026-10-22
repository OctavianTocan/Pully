package com.octaviantocan.pulls.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.navigationevent.NavigationEvent
import androidx.navigationevent.NavigationEventDispatcher
import androidx.navigationevent.NavigationEventHandler
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.LocalNavigationEventDispatcherOwner
import com.octaviantocan.pulls.ui.home.LocalNativeHomeActions
import com.octaviantocan.pulls.ui.home.NativeBackReadiness

/** A Compose dialog's native input dispatches to the same captured sheet/edit owner. */
@Composable
internal fun SharedDialogBack(onDismissRequest: () -> Unit) {
    SharedBackHandler(onDismissRequest)
    val shared = LocalSharedBackDispatcher.current
    val native = LocalNativeHomeActions.current as? NativeBackReadiness
    val framework = requireNotNull(LocalNavigationEventDispatcherOwner.current).navigationEventDispatcher
    val handler = remember(shared, framework, native) { SharedDialogNavigationHandler(shared) { native?.isSharedBackActive() == true } }
    DisposableEffect(framework, handler) {
        // Compose's default-priority dialog handler deliberately ignores progress completion.
        framework.addHandler(handler, NavigationEventDispatcher.PRIORITY_OVERLAY)
        onDispose { handler.close(); handler.remove() }
    }
}

internal class SharedDialogNavigationHandler(private val shared: SharedBackDispatcher, private val eligible: () -> Boolean) :
    NavigationEventHandler<NavigationEventInfo>(NavigationEventInfo.None, isBackEnabled = true) {
    private var closed = false
    private var started = false
    private var captured: SharedBackConsumer? = null

    override fun onBackStarted(event: NavigationEvent) {
        if (closed) return
        captured?.let(shared::cancelled)
        started = true
        captured = if (eligible()) shared.current() else null
        captured?.let(shared::began)
    }

    override fun onBackProgressed(event: NavigationEvent) {
        if (closed || !started) return
        if (!eligible()) {
            val previous = captured
            captured = null // The same gesture cannot reacquire a consumer after native authority returns.
            previous?.let(shared::cancelled)
        } else if (event.progress.isFinite()) captured?.let { shared.changed(it, event.progress.coerceIn(0f, 1f)) }
    }

    override fun onBackCompleted() {
        if (closed) return
        val consumer = if (started) captured else if (eligible()) shared.current() else null
        started = false
        captured = null
        if (consumer != null && (!eligible() || !shared.consume(consumer))) shared.cancelled(consumer)
    }

    override fun onBackCancelled() {
        val previous = captured
        started = false
        captured = null
        if (!closed && previous != null) shared.cancelled(previous)
    }

    fun close() {
        if (closed) return
        onBackCancelled()
        closed = true
        isBackEnabled = false
    }
}
