package com.octaviantocan.pulls.ui.ai

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import com.octaviantocan.pulls.core.AnalyzeChoice
import com.octaviantocan.pulls.ui.design.Orb
import com.octaviantocan.pulls.ui.review.ReviewSession

/**
 * Starts the analysis (groups, file notes, summary). [choice] says what follows it: the server starts the tour and
 * the brief itself when the analysis succeeds, so they come even if the phone is off by then, and an AI review
 * starts next to it. The Analyze button runs the last choice; the sheet under its chevron picks another.
 */
internal fun ReviewSession.runAnalyze(context: Context, choice: AnalyzeChoice = settings.analyzeChoice) {
    runAnalyze(com.octaviantocan.pulls.ui.settings.androidModelSettingsServices(context), AiCapabilities.Full, choice)
}

/** AI work in progress keeps its Orb (rule 18); the colour argument stays for the call sites that still pass one. */
@Composable
internal fun Spinner(@Suppress("UNUSED_PARAMETER") color: androidx.compose.ui.graphics.Color, size: androidx.compose.ui.unit.Dp = 16.dp) {
    Orb(size = size)
}
