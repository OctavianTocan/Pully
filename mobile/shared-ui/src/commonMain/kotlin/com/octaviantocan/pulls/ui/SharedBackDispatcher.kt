package com.octaviantocan.pulls.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf

/** One shared priority order, consumed by the native gesture observer before the route stack. */
class SharedBackDispatcher {
    private val entries = mutableStateListOf<SharedBackConsumer>()
    val canConsume: Boolean get() = current() != null
    fun current(): SharedBackConsumer? = entries.lastOrNull { it.eligible() }

    fun register(eligible: () -> Boolean, action: () -> Unit): SharedBackConsumer =
        SharedBackConsumer(eligible, action).also { entries += it }

    fun remove(consumer: SharedBackConsumer) { entries.remove(consumer) }
    fun owns(consumer: SharedBackConsumer): Boolean = consumer in entries && consumer.eligible()
    fun consume(consumer: SharedBackConsumer): Boolean {
        if (!owns(consumer) || current() !== consumer) return false
        consumer.action()
        return true
    }
}

class SharedBackConsumer internal constructor(internal val eligible: () -> Boolean, internal val action: () -> Unit)

val LocalSharedBackDispatcher = staticCompositionLocalOf<SharedBackDispatcher> {
    error("Install the one shared back dispatcher before registering native edit cancellation.")
}
internal val LocalBackConsumerEligible = staticCompositionLocalOf<() -> Boolean> { { true } }

/** Native EditInPlace uses this handler; Android retains its existing platform BackHandler. */
@Composable
fun SharedBackHandler(onBack: () -> Unit) {
    val dispatcher = LocalSharedBackDispatcher.current
    val eligible = LocalBackConsumerEligible.current
    val latest by rememberUpdatedState(onBack)
    DisposableEffect(dispatcher, eligible) {
        val consumer = dispatcher.register(eligible) { latest() }
        onDispose { dispatcher.remove(consumer) }
    }
}
