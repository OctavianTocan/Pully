package com.octaviantocan.pulls.ui.ai

import com.octaviantocan.pulls.ui.review.ReviewSession

/** Designs a brief in the reader's language, unless one is already being written. */
fun startBrief(session: ReviewSession) {
    startBrief(session, AiCapabilities.Full)
}

