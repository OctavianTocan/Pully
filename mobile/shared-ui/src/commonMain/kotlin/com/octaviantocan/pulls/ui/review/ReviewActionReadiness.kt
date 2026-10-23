package com.octaviantocan.pulls.ui.review

import androidx.compose.runtime.staticCompositionLocalOf
import com.octaviantocan.pulls.ui.ai.AiCapabilities

/**
 * A retained control reads the host's live source/foreground gate before starting another request.
 * Native accepted-read continuations observe these closures through Compose snapshots. Hosts must expose
 * their changing eligibility/policy as snapshot state and cancel the session lifetime on source retirement.
 */
class ReviewActionReadiness(
    private val current: () -> Boolean,
    private val aiPolicy: () -> AiCapabilities = { AiCapabilities.Full },
    private val reviewPolicy: () -> ReviewCapabilities = { ReviewCapabilities.Full },
) {
    val eligible: Boolean get() = current()

    fun ai(drawn: AiCapabilities): AiCapabilities {
        if (!current()) return AiCapabilities.None
        val installed = installedAi(drawn)
        if (!current()) return AiCapabilities.None
        return installed
    }

    /** Installation policy can retire a workflow; a foreground pause cannot erase its mounted input. */
    internal fun installedAi(drawn: AiCapabilities): AiCapabilities {
        val live = aiPolicy()
        return AiCapabilities(
            drawn.canReadResults && live.canReadResults,
            drawn.canAsk && live.canAsk,
            drawn.canAnalyze && live.canAnalyze,
            drawn.canCritique && live.canCritique,
            drawn.canTour && live.canTour,
            drawn.canBrief && live.canBrief,
        )
    }

    fun review(drawn: ReviewCapabilities): ReviewCapabilities {
        if (!current()) return ReviewCapabilities.None
        val installed = installedReview(drawn)
        if (!current()) return ReviewCapabilities.None
        return installed
    }

    /** An accepted reply still belongs to its original installed workflow while fresh work is paused. */
    internal fun installedReview(drawn: ReviewCapabilities): ReviewCapabilities {
        val live = reviewPolicy()
        return ReviewCapabilities(
            drawn.canReadDiff && live.canReadDiff,
            drawn.canWriteComments && live.canWriteComments,
            drawn.canSubmitReview && live.canSubmitReview,
            drawn.canEditPullRequest && live.canEditPullRequest,
            drawn.canMarkReviewed && live.canMarkReviewed,
        )
    }

    companion object {
        // Existing Android owners still require their drawn policies and source/view guards.
        internal val Default = ReviewActionReadiness({ true })
    }
}

/** Hosts with synchronous eligibility changes provide one retained port above their shared routes. */
val LocalReviewActionReadiness = staticCompositionLocalOf { ReviewActionReadiness.Default }
