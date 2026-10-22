package com.octaviantocan.pulls.ui.home

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.octaviantocan.pulls.core.OpenTarget
import com.octaviantocan.pulls.data.PrStack
import com.octaviantocan.pulls.data.PullRow
import com.octaviantocan.pulls.data.Repo
import com.octaviantocan.pulls.data.StackEntry
import com.octaviantocan.pulls.data.Triage
import com.octaviantocan.pulls.ui.review.StackSheet

/*
 * Stacks in the Inbox. The layers of one stack that are in a section show as one row, and a tap on it lists them
 * ("since PR stacks are so common nowadays, I would actually group them into one row, and make the stack the row, and
 * when you tap it can show you which PRs are in there": Tavi, 17 Oct 17:40; rule 202).
 */

/** One row of an Inbox section: a pull request on its own, or the layers of one stack. */
sealed interface InboxItem {
    val key: String

    data class Single(val pull: PullRow) : InboxItem {
        override val key: String get() = Triage.key(pull)
    }

    /** Two or more layers of one stack, bottom first. */
    data class Stacked(val layers: List<PullRow>) : InboxItem {
        val bottom: PullRow get() = layers.first()
        override val key: String get() = "stack:${stackOf(bottom)}"
    }
}

/** Which stack a pull request is a layer of, unique across repositories; null when it is in none. */
private fun stackOf(pull: PullRow): String? = pull.stack?.let { "${pull.owner}/${pull.repo}/${it.id}" }

/**
 * A section's rows in its own order, with the layers of a stack folded into one row where the first of them stood.
 * A stack with one layer in the section stays that pull request's own row, with its place in the stack on it.
 */
fun inboxItems(pulls: List<PullRow>): List<InboxItem> {
    val counts = pulls.groupingBy { stackOf(it) }.eachCount()
    val placed = mutableSetOf<String>()
    return pulls.mapNotNull { pull ->
        val stack = stackOf(pull)
        when {
            stack == null || (counts[stack] ?: 0) < 2 -> InboxItem.Single(pull)
            !placed.add(stack) -> null
            else -> InboxItem.Stacked(pulls.filter { stackOf(it) == stack }.sortedWith(compareBy({ it.stack?.position ?: 0 }, { it.number })))
        }
    }
}

/** The layers the Inbox holds as a stack, for the Stack sheet to show at once while the whole stack is asked for. */
fun inboxStack(layers: List<PullRow>): PrStack {
    val bottom = layers.first()
    return PrStack(
        native = bottom.stack?.native == true,
        number = null,
        // The base branch closes the list only when the stack's real bottom is here.
        base = if (bottom.stack?.position == 1) bottom.baseRef else "",
        // The section may hold only some layers; the rows know the whole stack's size for the "2/5" on each.
        size = layers.maxOf { it.stack?.size ?: 0 },
        entries = layers.map { pull ->
            StackEntry(
                number = pull.number,
                title = pull.title,
                state = when (pull.pullState()) {
                    PullState.Merged -> "merged"
                    PullState.Closed -> "closed"
                    PullState.Draft -> "draft"
                    PullState.Open -> "open"
                },
                position = pull.stack?.position ?: 0,
                current = false,
                headRef = pull.headRef,
                baseRef = pull.baseRef,
                checks = pull.checks,
                additions = pull.additions,
                deletions = pull.deletions,
                author = pull.author,
            )
        },
    )
}

/**
 * The Stack sheet for a stack row: the pull request page's own list of layers, top first down to the base branch, so
 * a stack reads the same wherever it opens. It shows the layers the Inbox has at once, then every layer once the
 * server answers; a tap on one opens it.
 */
@Composable
fun InboxStackSheet(layers: List<PullRow>, repo: Repo, onOpen: (PullRow, Int) -> Unit, onClose: () -> Unit) {
    val bottom = layers.first()
    var stack by remember(layers) { mutableStateOf(inboxStack(layers)) }
    LaunchedEffect(Triage.key(bottom)) {
        val whole = try {
            repo.prStack(OpenTarget.Pull(bottom.owner, bottom.repo, bottom.number, "github.com"))
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
        // Asked for from the bottom layer, the answer marks it as this pull request; here none is.
        if (whole != null) stack = whole.copy(entries = whole.entries.map { it.copy(current = false) })
    }
    StackSheet(stack, onOpen = { number -> onOpen(bottom, number) }, onClose = onClose)
}
