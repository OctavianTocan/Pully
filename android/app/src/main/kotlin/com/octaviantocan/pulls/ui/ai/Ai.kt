package com.octaviantocan.pulls.ui.ai

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.octaviantocan.pulls.data.Activity
import com.octaviantocan.pulls.data.AskThread
import com.octaviantocan.pulls.ui.review.ReviewSession

/*
 * Owner: AI worker. Every AI surface of the review page, ported from `components/ai`,
 * `components/critique`, `components/chat`, `components/ask`, `DiffAnalyzeButton`,
 * `AnalyzeStatusModal`, `DiffShareButton`, `ShareResultModal`, `SharedAnalysisBanner`.
 * Callers outside ui/ai use only the functions below; their signatures are the contract.
 */

/** `DiffAnalyzeButton`: Analyze with AI / Analyzing… {time} / Re-analyze, opening the status sheet. */
@Composable
fun AnalyzeButton(session: ReviewSession, modifier: Modifier = Modifier) {}

/** `CritiqueButton` + `LensPicker`: Review with AI, with a lens menu. */
@Composable
fun CritiqueButton(session: ReviewSession, modifier: Modifier = Modifier) {}

/** `DiffShareButton` + `ShareResultModal`. Renders nothing when there is no own AI result. */
@Composable
fun ShareButton(session: ReviewSession, modifier: Modifier = Modifier) {}

/** `CritiquePanel` (with `FindingCard`s). Renders nothing until a critique runs or exists. */
@Composable
fun CritiquePanel(session: ReviewSession, modifier: Modifier = Modifier) {}

/** `SharedAnalysisBanner` when the shown analysis was shared by someone. */
@Composable
fun SharedAnalysisBanner(session: ReviewSession, modifier: Modifier = Modifier) {}

/** `ChatWidget`: floating button bottom-right, opening the PR chat as a bottom sheet. Draw it over the page. */
@Composable
fun ChatWidget(session: ReviewSession, modifier: Modifier = Modifier) {}

/** `AskThread`: a saved question thread about some lines, shown under its last line. */
@Composable
fun AskThreadCard(session: ReviewSession, thread: AskThread, modifier: Modifier = Modifier) {}

/**
 * `SelectionActions` + a new `AskThread`: for selected lines, Ask AI / Copy file and lines /
 * Copy GitHub link. [onDone] closes it.
 */
@Composable
fun SelectionActions(session: ReviewSession, path: String, side: String, startLine: Int, endLine: Int, onDone: () -> Unit, modifier: Modifier = Modifier) {}

/** `AiActivityLog`: the work log of a task, grouped and timed like the web. */
@Composable
fun AiActivityLog(activities: List<Activity>, running: Boolean, modifier: Modifier = Modifier) {}
