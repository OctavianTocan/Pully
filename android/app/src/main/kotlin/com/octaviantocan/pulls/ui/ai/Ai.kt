package com.octaviantocan.pulls.ui.ai

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.octaviantocan.pulls.data.Activity
import com.octaviantocan.pulls.data.AskThread
import com.octaviantocan.pulls.ui.review.ReviewSession

/*
 * Owner: AI worker. Every AI surface of the review page, ported from `components/ai`,
 * `components/critique`, `components/chat`, `components/ask`, `DiffShareButton`,
 * `ShareResultModal`, `SharedAnalysisBanner`.
 * Callers outside ui/ai use only the functions below; their signatures are the contract.
 *
 * Files: `Analyze.kt` (running an analysis, share, shared banner), `Critique.kt`
 * (running a review, lens picker, panel, finding cards), `Ask.kt` (selection actions, threads),
 * `Chat.kt` (the Ask sheet), `AiParts.kt` (activity log, usage line, sheet, shimmer),
 * `AiState.kt` (page-local AI state), and the pure ports `AiFormat.kt`, `AiActivity.kt`,
 * `AskSelection.kt`.
 */

/** `DiffShareButton` + `ShareResultModal`. Renders nothing when there is no own AI result. */
@Composable
fun ShareButton(session: ReviewSession, modifier: Modifier = Modifier) = ShareButtonImpl(session, modifier)

/** `CritiquePanel` (with `FindingCard`s). Renders nothing until a critique runs or exists. */
@Composable
fun CritiquePanel(session: ReviewSession, modifier: Modifier = Modifier) = CritiquePanelImpl(session, modifier)

/** `SharedAnalysisBanner` when the shown analysis was shared by someone. */
@Composable
fun SharedAnalysisBanner(session: ReviewSession, modifier: Modifier = Modifier) = SharedAnalysisBannerImpl(session, modifier)

/**
 * The prototype's Ask sheet (the old `ChatWidget`, opened from the Ask button instead of a floating one):
 * a full-height chat about this pull request or its tour. [suggestions] are the starter chips of an empty
 * chat and [initial] is a question to send as it opens.
 */
@Composable
fun AskSheet(session: ReviewSession, title: String, suggestions: List<String>, initial: String?, onDismiss: () -> Unit) =
    AskSheetImpl(session, title, suggestions, initial, onDismiss)

/** `AskThread`: a saved question thread about some lines, shown under its last line. */
@Composable
fun AskThreadCard(session: ReviewSession, thread: AskThread, modifier: Modifier = Modifier) = AskThreadCardImpl(session, thread, modifier)

/**
 * `SelectionActions` + a new `AskThread`: for selected lines, Ask AI / Copy file and lines /
 * Copy GitHub link. [onDone] closes it.
 */
@Composable
fun SelectionActions(session: ReviewSession, path: String, side: String, startLine: Int, endLine: Int, onDone: () -> Unit, modifier: Modifier = Modifier) =
    SelectionActionsImpl(session, path, side, startLine, endLine, onDone, modifier)

/** `AiActivityLog`: the work log of a task, grouped and timed like the web. */
@Composable
fun AiActivityLog(activities: List<Activity>, running: Boolean, modifier: Modifier = Modifier) = AiActivityLogFull(activities, running, modifier)
