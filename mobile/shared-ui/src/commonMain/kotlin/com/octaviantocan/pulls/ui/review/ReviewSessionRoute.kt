package com.octaviantocan.pulls.ui.review

import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import com.octaviantocan.pulls.core.OpenTarget

/** The whole private route belongs to one target and source, including remembered sheets and drafts. */
@Composable
fun ReviewSessionRoute(target: OpenTarget, cache: ReviewSessionCache, content: @Composable () -> Unit) {
    key(target, cache.routeGeneration) { content() }
}
