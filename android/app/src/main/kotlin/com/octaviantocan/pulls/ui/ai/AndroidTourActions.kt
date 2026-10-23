package com.octaviantocan.pulls.ui.ai

import android.content.Context
import androidx.compose.runtime.Composable
import com.octaviantocan.pulls.ui.review.ReviewSession
import kotlinx.serialization.json.put

/** Starts a tour in the reader's language, unless one is already running. */
fun startTour(session: ReviewSession) { startTour(session, AiCapabilities.Full) }

/**
 * Writes a tour: with no analysis yet it runs the last choice with the tour in it, and after one it writes the tour
 * alone. While an analysis runs, the tour waits for it: one that writes a tour needs nothing more, and one that does
 * not queues the tour, which [followQueuedTour] starts when the analysis is done.
 */
internal fun generateTour(session: ReviewSession, context: Context) {
    generateTour(session, com.octaviantocan.pulls.ui.settings.androidModelSettingsServices(context), AiCapabilities.Full)
}

/** Android supplies its actual paid workflow; the shared observer owns the queue policy. */
@Composable
internal fun followQueuedTour(session: ReviewSession) = followQueuedTour(session) { startTour(session) }
