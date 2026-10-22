package com.octaviantocan.pulls.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.octaviantocan.pulls.ui.explore.NativeEntryMode
import com.octaviantocan.pulls.ui.explore.NativeEntryState

/** Entry changes retire application services; ordinary route/foreground changes do not. */
interface NativeWorkspace {
    /** Finish the previous private time interval while its source can still own the event. */
    fun beforeLeave() {}
    fun close()
}

/** UI-thread entry owner. A fresh public entry never invokes the existing-workspace factory. */
class NativeWorkspaceOwner(
    val entry: NativeEntryState,
    val navigation: AppNavigation,
    private val createGuest: (NativeEntryState.GuestSource, AppNavigation, () -> Boolean) -> NativeWorkspace,
    private val createExisting: (AppNavigation, () -> Boolean) -> NativeWorkspace,
    private val selected: (NativeEntryMode) -> Unit,
) {
    private var closed = false
    var generation by mutableLongStateOf(0L)
        private set
    var workspace by mutableStateOf<NativeWorkspace?>(null)
        private set

    init { workspace = create() }

    private fun create(): NativeWorkspace {
        val opening = generation
        val mode = entry.mode
        val current = { !closed && generation == opening && entry.mode == mode }
        return if (mode == NativeEntryMode.Explore) createGuest(requireNotNull(entry.guestSource()), navigation, current)
        else createExisting(navigation, current)
    }

    fun select(mode: NativeEntryMode) {
        if (closed || entry.mode == mode) return
        retire()
        entry.select(mode)
        navigation.reset(if (mode == NativeEntryMode.Explore) Screen.Explore else Screen.Home)
        selected(mode)
        workspace = create()
    }

    /** A personal identity changes the public source, never the separately saved private entry. */
    fun renewExplore() {
        if (closed || entry.mode != NativeEntryMode.Explore) return
        retire()
        entry.renewExplore()
        navigation.reset(Screen.Explore)
        workspace = create()
    }

    private fun retire() {
        val previous = workspace
        try { previous?.beforeLeave() }
        finally {
            generation++
            workspace = null
            previous?.close()
        }
    }

    fun close() {
        if (closed) return
        try { retire() } finally { closed = true }
    }
}
