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

    fun register(eligible: () -> Boolean, gesture: SharedBackGesture? = null, action: () -> Unit): SharedBackConsumer =
        SharedBackConsumer(eligible, gesture, action).also { entries += it }

    fun remove(consumer: SharedBackConsumer) { entries.remove(consumer) }
    fun owns(consumer: SharedBackConsumer): Boolean = consumer in entries && consumer.eligible()
    fun began(consumer: SharedBackConsumer) { if (owns(consumer) && current() === consumer) consumer.gesture?.began() }
    fun changed(consumer: SharedBackConsumer, progress: Float) {
        if (owns(consumer) && current() === consumer) consumer.gesture?.changed(progress)
    }
    fun cancelled(consumer: SharedBackConsumer) { consumer.gesture?.cancelled() }
    fun consume(consumer: SharedBackConsumer): Boolean {
        if (!owns(consumer) || current() !== consumer) return false
        consumer.action()
        return true
    }
}

/** A nested shared page follows the same native gesture without registering another recognizer. */
interface SharedBackGesture {
    fun began()
    fun changed(progress: Float)
    fun cancelled()
}

class SharedBackConsumer internal constructor(
    internal val eligible: () -> Boolean,
    internal val gesture: SharedBackGesture?,
    internal val action: () -> Unit,
)

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
