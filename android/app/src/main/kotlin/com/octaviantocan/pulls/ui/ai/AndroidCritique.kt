package com.octaviantocan.pulls.ui.ai

import androidx.compose.runtime.Composable
import com.octaviantocan.pulls.ui.review.ReviewSession
import com.octaviantocan.pulls.ui.settings.rememberAndroidModelSettingsServices

/** Starts an AI review through [lens] (null: the general review), like `critique.run`. [effort] overrides the Settings effort. */
internal fun ReviewSession.runCritique(context: android.content.Context, lens: String?, effort: String? = null) {
    runCritique(com.octaviantocan.pulls.ui.settings.androidModelSettingsServices(context), AiCapabilities.Full, lens, effort)
}


@Composable
internal fun ReviewSheet(session: ReviewSession, onClose: () -> Unit, onPick: (String?) -> Unit) =
    ReviewSheet(session, rememberAndroidModelSettingsServices(), onClose, onPick)
