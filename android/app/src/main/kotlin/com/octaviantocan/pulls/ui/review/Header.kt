package com.octaviantocan.pulls.ui.review

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.octaviantocan.pulls.ui.design.LocalPullsColors
import com.octaviantocan.pulls.ui.design.PhIcon
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
            result.onSuccess { polls = 0; session.refresh(); onOk() }
                .onFailure { actionError = it.message ?: it.toString() }
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

/** A Phosphor icon and how the web tints it: a color, `op-fade` or `op-mute`. */
internal data class Glyph(val name: String, val tint: @Composable () -> Color)

@Composable private fun fadeTint() = LocalPullsColors.current.fade
@Composable private fun muteTint() = LocalPullsColors.current.text.copy(alpha = 0.5f)

/** `CHECKS_ICON` in `status-icons.ts`, keyed by `ChecksStatus`. */
internal fun checksGlyph(state: String?): Glyph? = when (state) {
    "success" -> Glyph("check-bold") { WebColors.green(LocalPullsColors.current.dark) }
    "failure" -> Glyph("x-bold") { WebColors.red(LocalPullsColors.current.dark) }
    "pending" -> Glyph("circle-fill") { WebColors.amber500 }
    else -> null
}

/** `CHECK_BUCKET_ICON`. */
internal fun bucketGlyph(bucket: String): Glyph = when (bucket) {
    "failing" -> checksGlyph("failure")!!
    "pending" -> checksGlyph("pending")!!
    "passing" -> checksGlyph("success")!!
    else -> Glyph("prohibit") { muteTint() }
}

/** `REVIEW_DECISION_ICON`. */
internal fun decisionGlyph(decision: String?): Glyph? = when (decision) {
    "approved" -> Glyph("check-circle-duotone") { WebColors.green(LocalPullsColors.current.dark) }
    "changes_requested" -> Glyph("x-circle-duotone") { WebColors.red(LocalPullsColors.current.dark) }
    "review_required" -> Glyph("eye-duotone") { fadeTint() }
    else -> null
}

/** `REVIEW_DISPLAY` / `STATE_DISPLAY` / `REVIEW_ICON`: one review's state. */
internal fun reviewGlyph(state: String): Glyph = when (state) {
    "approved" -> decisionGlyph("approved")!!
    "changes_requested" -> decisionGlyph("changes_requested")!!
    "dismissed" -> Glyph("prohibit") { muteTint() }
    "pending" -> Glyph("hourglass-duotone") { muteTint() }
    else -> Glyph("chat-circle-dots-duotone") { fadeTint() } // ban-ok: a review that only left comments; the fact is "commented", not an AI mark
}

@Composable
internal fun GlyphIcon(glyph: Glyph, size: Dp = 16.dp, modifier: Modifier = Modifier) = PhIcon(glyph.name, modifier, tint = glyph.tint(), size = size)
