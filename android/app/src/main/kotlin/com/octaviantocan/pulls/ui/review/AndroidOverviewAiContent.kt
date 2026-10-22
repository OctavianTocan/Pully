package com.octaviantocan.pulls.ui.review

import androidx.compose.ui.platform.LocalContext
import com.octaviantocan.pulls.ui.ai.cliEngine
import com.octaviantocan.pulls.ui.ai.AiCapabilities
import com.octaviantocan.pulls.ui.ai.startBrief

/** Existing Android account/engine and paid-work behavior around the same shared result renderers. */
internal object AndroidOverviewAiContent : SharedOverviewAiContent(
    capabilities = AiCapabilities.Full,
    engineAvailable = { cliEngine(LocalContext.current) },
    regenerateBrief = ::startBrief,
)
