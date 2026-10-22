package com.octaviantocan.pulls.ui.explore

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

enum class NativeEntryMode { Explore, ExistingServer }

@Stable
class NativeEntryState(verifiedExistingConnection: Boolean, savedChoice: NativeEntryMode? = null) {
    var mode by mutableStateOf(
        if (verifiedExistingConnection && savedChoice != NativeEntryMode.Explore) NativeEntryMode.ExistingServer else NativeEntryMode.Explore,
    )
        private set
    var generation by mutableLongStateOf(0L)
        private set
    private val retirements = mutableSetOf<() -> Unit>()

    fun select(next: NativeEntryMode) {
        if (mode == next) return
        generation++
        val retiring = retirements.toList()
        retirements.clear()
        retiring.forEach { it() }
        mode = next
    }

    fun guestSource(): GuestSource? = if (mode == NativeEntryMode.Explore) GuestSource(this, generation) else null

    class GuestSource internal constructor(private val entry: NativeEntryState, private val epoch: Long) {
        val current: Boolean get() = entry.mode == NativeEntryMode.Explore && entry.generation == epoch
        internal fun onRetired(action: () -> Unit): () -> Unit {
            if (!current) { action(); return {} }
            entry.retirements.add(action)
            return { entry.retirements.remove(action) }
        }
    }
}
