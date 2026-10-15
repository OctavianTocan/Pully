package com.octaviantocan.pulls.ui.review

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import com.octaviantocan.pulls.sound.Cue
import com.octaviantocan.pulls.sound.Emphasis
import com.octaviantocan.pulls.sound.Sounds
import kotlinx.coroutines.launch

/**
 * Page-level UI state the web keeps in `PrOverviewStore`: the action in flight and its error, the
 * overview poll count, and which of the review sheets (progress, submit, merge) are open.
 */
@Stable
class ReviewTopUi {
    /** `merge`, `update-branch`, `close`, `reopen`, `ready` or `draft` while it runs. */
    var busy by mutableStateOf<String?>(null)
    var actionError by mutableStateOf<String?>(null)

    var progressOpen by mutableStateOf(false)
    var reviewOpen by mutableStateOf(false)
    var mergeOpen by mutableStateOf(false)

    /** The verdict the submit sheet opens on: `COMMENT`, `APPROVE` or `REQUEST_CHANGES`. */
    var reviewVerdict by mutableStateOf("COMMENT")

    /**
     * The AI's summary the person switched away from to read the description in its place. Any other summary, such as
     * the next analysis, shows again (`showsDescription`).
     */
    var descriptionOver by mutableStateOf<String?>(null)

    /** Overview polls since the last refresh (`MAX_POLLS` 20 in `pr-overview-store.ts`). */
    var polls by mutableIntStateOf(0)

    /**
     * `PrOverviewStore.run`: one action at a time; on success the PR page reloads (diff,
     * overview, reviews) and [onOk] runs; on failure the message stays until dismissed.
     */
    fun run(session: ReviewSession, action: String, onOk: () -> Unit = {}, task: suspend () -> Unit) {
        if (busy != null) return
        busy = action
        actionError = null
        session.scope.launch {
            val result = runCatching { task() }
            busy = null
            // A merge is the one outcome that sounds at full weight; the rest are quiet confirmations.
            result.onSuccess { polls = 0; session.refresh(); Sounds.play(Cue.Success, if (action == "merge") Emphasis.Strong else Emphasis.Subtle); onOk() }
                .onFailure { actionError = it.message ?: it.toString(); Sounds.play(Cue.Error, Emphasis.Subtle) }
        }
    }
}

// --- Shared bits of the page top ---

/** UnoCSS palette stops the page-top templates name (`text-green-600 dark:text-green-400`...). */
internal object WebColors {
    fun green(dark: Boolean) = Color(if (dark) 0xFF3CC98F else 0xFF1C8A5A)
    fun red(dark: Boolean) = Color(if (dark) 0xFFE8777C else 0xFFC94F55)
    val amber500 = Color(0xFFF59E0B)
    /** `text-amber-600 dark:text-amber-400`. */
    fun amberIcon(dark: Boolean) = Color(if (dark) 0xFFFBBF24 else 0xFFD97706)
    /** `text-amber-700 dark:text-amber-400`. */
    fun amberText(dark: Boolean) = Color(if (dark) 0xFFFBBF24 else 0xFFB45309)
    /** `text-orange-700 dark:text-orange-400`. */
    fun orange(dark: Boolean) = Color(if (dark) 0xFFFB923C else 0xFFC2410C)
    /** The accent text: `--link` (a lighter teal in the dark theme). */
    fun primary(dark: Boolean) = Color(if (dark) 0xFF6CC3CB else 0xFF1E6F78)
    val purple500 = Color(0xFFA855F7)
}

