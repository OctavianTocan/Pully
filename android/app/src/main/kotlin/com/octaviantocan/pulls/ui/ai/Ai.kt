package com.octaviantocan.pulls.ui.ai

import androidx.compose.foundation.ScrollState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.octaviantocan.pulls.data.Activity
import com.octaviantocan.pulls.data.AskThread
import com.octaviantocan.pulls.ui.design.CodePlace
import com.octaviantocan.pulls.ui.review.ReviewSession

/*
 * Owner: AI worker. Every AI surface of the review page, ported from `components/ai`,
 * `components/critique`, `components/chat`, `components/ask`, `DiffShareButton`,
 * `ShareResultModal`, `SharedAnalysisBanner`.
 * Callers outside ui/ai use only the functions below; their signatures are the contract.
 *
 * Files: `Analyze.kt` (running an analysis, shared banner), `Critique.kt`
 * (running a review, lens picker, panel, finding cards), `Ask.kt` (the saved threads on lines),
 * `Chat.kt` (the Ask sheet), `AiParts.kt` (activity log, usage line, sheet, shimmer),
 * `AiState.kt` (page-local AI state), and the pure ports `AiFormat.kt`, `AiActivity.kt`,
 * `AskSelection.kt`.
 */

/** `CritiquePanel` (with `FindingCard`s). Renders nothing until a critique runs or exists. */
@Composable
fun CritiquePanel(session: ReviewSession, modifier: Modifier = Modifier, onRetry: (String?) -> Unit) = CritiquePanelImpl(session, modifier, onRetry)

/** `SharedAnalysisBanner` when the shown analysis was shared by someone. */
@Composable
fun SharedAnalysisBanner(session: ReviewSession, modifier: Modifier = Modifier) = SharedAnalysisBannerImpl(session, modifier)

/** Something the person asks about, attached to the question: a file note, a file, or selected lines. [label] names it, [text] is what it says. */
data class AskReference(val label: String, val text: String)

/**
 * The prototype's Ask sheet (the old `ChatWidget`, opened from the Ask button instead of a floating one):
 * a full-height chat about this pull request or its tour. [suggestions] are the starter chips of an empty
 * chat and [initial] is a question to send as it opens. [reference] is attached to the next question, shown over the
 * field until it is sent or removed; nothing is sent until the person writes the question. [scroll] is where the
 * conversation is scrolled, kept by the opener; [returning] opens it again where it was rather than at its end, as Back
 * from a place an answer opened does.
 */
@Composable
fun AskSheet(session: ReviewSession, title: String, suggestions: List<String>, initial: String?, reference: AskReference? = null, scroll: ScrollState? = null, returning: Boolean = false, onOpen: (CodePlace) -> Unit = {}, onDismiss: () -> Unit) =
    AskSheetImpl(session, title, suggestions, initial, reference, scroll, returning, onOpen, onDismiss)

/** `AskThread`: a saved question thread about some lines, shown under its last line as a chat is, with no card around it. */
@Composable
fun AskThreadCard(session: ReviewSession, thread: AskThread, modifier: Modifier = Modifier) = AskThreadCardImpl(session, thread, modifier)

/** `AiActivityLog`: the work log of a task, grouped and timed like the web. */
@Composable
fun AiActivityLog(activities: List<Activity>, running: Boolean, modifier: Modifier = Modifier) = AiActivityLogFull(activities, running, modifier)
