package com.octaviantocan.pulls.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue

/** The host closes this UI-thread owner before publishing replacement credentials. */
class CommandPaletteOwner(private val isCurrent: () -> Boolean) {
    private var closed = false
    private val retirements = mutableListOf<() -> Unit>()
    fun current() = !closed && isCurrent()
    internal fun observeRetirement(clear: () -> Unit): () -> Unit {
        if (closed) { clear(); return {} }
        retirements += clear
        return { retirements.remove(clear) }
    }
    fun close() {
        if (closed) return
        closed = true
        retirements.toList().forEach { it() }
        retirements.clear()
        Commands.close()
        Commands.scope = null
    }
}

internal class CommandPageState(private val owner: CommandPaletteOwner) {
    private var active = true
    var query by mutableStateOf("")
        private set
    val expanded = mutableStateListOf<String>()
    fun changeQuery(value: String) { if (active && owner.current()) query = value }
    fun expand(group: String) { if (active && owner.current() && group !in expanded) expanded += group }
    fun clearScope(clear: () -> Unit) { if (active && owner.current()) clear() }
    fun run(command: Command, onRun: (Command) -> Unit) { if (active && owner.current()) onRun(command) }
    fun clear() { query = ""; expanded.clear() }
    fun close() { active = false; clear() }
}

@Composable
internal fun rememberCommandPageState(owner: CommandPaletteOwner): CommandPageState {
    val state = remember(owner) { CommandPageState(owner) }
    DisposableEffect(owner, state) {
        val stop = owner.observeRetirement(state::clear)
        onDispose { stop(); state.close() }
    }
    return state
}

@Composable
internal expect fun rememberHardwareKeyboardPresent(): Boolean
