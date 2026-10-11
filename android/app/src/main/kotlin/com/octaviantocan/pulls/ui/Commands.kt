package com.octaviantocan.pulls.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue

/** Port of `state/commands.ts`: something the palette can run. [group] is already translated. */
data class Command(
    val id: String,
    val title: String,
    val group: String? = null,
    val keywords: String? = null,
    /** Phosphor name, e.g. `gear-duotone`. */
    val icon: String? = null,
    val hidden: Boolean = false,
    val run: () -> Unit,
)

/** A palette scope (`paletteScope`): only commands whose id starts with [prefix], shown as a chip. */
data class PaletteScope(val prefix: String, val label: String)

/**
 * The app-wide command registry and palette state (`state/commands.ts`, `state/palette.ts`).
 * Screens register getters with [UseCommands]; a getter is re-read on every palette open, so it
 * always lists the current state.
 */
object Commands {
    private val sources = mutableStateListOf<() -> List<Command>>()
    var paletteOpen by mutableStateOf(false)
    var scope by mutableStateOf<PaletteScope?>(null)

    /** Every registered command in registration order; later registrations of an id win. */
    fun all(): List<Command> {
        val byId = LinkedHashMap<String, Command>()
        sources.toList().forEach { source -> source().forEach { command -> byId.remove(command.id); byId[command.id] = command } }
        return byId.values.toList()
    }

    fun open(scope: PaletteScope? = null) {
        this.scope = scope
        paletteOpen = true
    }

    fun close() {
        paletteOpen = false
    }

    fun toggle() {
        if (paletteOpen) close() else open()
    }

    internal fun register(source: () -> List<Command>): () -> Unit {
        sources.add(source)
        return { sources.remove(source) }
    }
}

/** `useCommand` for the lifetime of the calling composable. */
@Composable
fun UseCommands(source: () -> List<Command>) {
    val latest by rememberUpdatedState(source)
    DisposableEffect(Unit) {
        val unregister = Commands.register { latest() }
        onDispose { unregister() }
    }
}

/**
 * Port of `state/command-rank.ts`: a match at the very start beats one at a word start, which
 * beats one inside a word, then word initials, then scattered characters. Ties keep the input
 * order; an empty query returns every item.
 */
object CommandRank {
    const val SCATTERED = 1_000_000
    private const val WORD = 1_000
    private const val INSIDE = 10_000
    private const val INITIALS = 100_000
    private val BREAK = Regex("""[\s\-_/.:]""")

    fun <T> rankItems(items: List<T>, query: String, text: (T) -> String): List<T> {
        val needle = query.trim().lowercase()
        if (needle.isEmpty()) return items
        return items.mapIndexedNotNull { at, item -> rank(text(item).lowercase(), needle)?.let { Triple(item, it, at) } }
            .sortedWith(compareBy<Triple<T, Int, Int>> { it.second }.thenBy { it.third })
            .map { it.first }
    }

    fun rank(hay: String, needle: String): Int? {
        val terms = needle.split(Regex("""\s+""")).filter { it.isNotEmpty() }
        if (terms.size > 1) {
            var worst = 0
            for (term in terms) worst = maxOf(worst, rank(hay, term) ?: return null)
            return worst
        }
        val whole = hay.indexOf(needle)
        if (whole == 0) return 0
        if (whole > 0) return (if (isBreak(hay[whole - 1])) WORD else INSIDE) + whole
        onWordStarts(hay, needle)?.let { return INITIALS + it }
        return scatteredSpan(hay, needle)?.let { SCATTERED + it }
    }

    private fun isBreak(ch: Char) = BREAK.matches(ch.toString())

    private fun scatteredSpan(hay: String, needle: String): Int? {
        var from = 0
        var first = -1
        var last = -1
        for (ch in needle) {
            if (ch == ' ') continue
            val found = hay.indexOf(ch, from)
            if (found == -1) return null
            if (first == -1) first = found
            last = found
            from = found + 1
        }
        return last - first
    }

    private fun onWordStarts(hay: String, needle: String): Int? {
        var at = -1
        for (ch in needle.replace(Regex("""\s"""), "")) {
            var found = -1
            for (i in at + 1 until hay.length) {
                if (hay[i] != ch) continue
                if (i == at + 1 || i == 0 || isBreak(hay[i - 1])) { found = i; break }
            }
            if (found == -1) return null
            at = found
        }
        return at
    }
}
