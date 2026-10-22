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
 * Files: `Analyze.kt` (running an analysis, shared banner), `Critique.kt`
 * (running a review, lens picker, panel, finding cards), `Ask.kt` (the saved threads on lines),
 * `Chat.kt` (the Ask sheet), `AiParts.kt` (activity log, usage line, sheet, shimmer),
 * `AiState.kt` (page-local AI state), and the pure ports `AiFormat.kt`, `AiActivity.kt`,
 * `AskSelection.kt`.
 */

/** `SharedAnalysisBanner` when the shown analysis was shared by someone. */
@Composable
fun SharedAnalysisBanner(session: ReviewSession, modifier: Modifier = Modifier) = SharedAnalysisBannerImpl(session, modifier)

/** `AiActivityLog`: the work log of a task, grouped and timed like the web. */
@Composable
fun AiActivityLog(activities: List<Activity>, running: Boolean, modifier: Modifier = Modifier) = AiActivityLogFull(activities, running, modifier)
