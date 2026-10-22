package com.octaviantocan.pulls.ui.review

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf

/** Host renderer/action readiness. The session's server permissions still apply independently. */
@Immutable
data class ReviewCapabilities(
    val canReadDiff: Boolean,
    val canWriteComments: Boolean,
    val canSubmitReview: Boolean,
    val canEditPullRequest: Boolean,
    /** Viewer progress has its own workflow readiness; it does not require GitHub write permission. */
    val canMarkReviewed: Boolean,
) {
    companion object {
        val None = ReviewCapabilities(false, false, false, false, false)
        val ReadOnly = ReviewCapabilities(true, false, false, false, false)
        val Full = ReviewCapabilities(true, true, true, true, true)
    }
}

/** Uninstalled hosts cannot expose mutations; actual hosts install one readiness policy above their routes. */
val LocalReviewCapabilities = staticCompositionLocalOf { ReviewCapabilities.None }
