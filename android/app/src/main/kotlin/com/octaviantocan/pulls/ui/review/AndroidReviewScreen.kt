package com.octaviantocan.pulls.ui.review

import androidx.compose.runtime.Composable
import com.octaviantocan.pulls.core.OpenTarget
import com.octaviantocan.pulls.ui.Nav

@Composable
fun ReviewScreen(target: OpenTarget, nav: Nav, startOnTour: Boolean = false, openedFrom: String = "other", place: com.octaviantocan.pulls.ui.PlaceRequest? = null, initialAIResult: String? = null) {
    ReviewScreen(target, nav, rememberAndroidReviewFrameServices(), startOnTour, openedFrom, place, initialAIResult)
}
