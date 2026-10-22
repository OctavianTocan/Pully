package com.octaviantocan.pulls.ui.ai

import androidx.compose.runtime.Composable
import com.octaviantocan.pulls.ui.home.rememberAndroidNativeHomeActions
import com.octaviantocan.pulls.ui.review.ReviewSession
import com.octaviantocan.pulls.ui.settings.rememberAndroidModelSettingsServices

/** Android supplies its actual external-link and persisted model adapters to the shared sheet. */
@Composable
internal fun TourSheet(session: ReviewSession, onClose: () -> Unit, onAsk: () -> Unit, onDiff: () -> Unit, onPrActions: () -> Unit, start: TourSheetPage = TourSheetPage.Main) =
    TourSheet(session, rememberAndroidModelSettingsServices(), rememberAndroidNativeHomeActions(), onClose, onAsk, onDiff, onPrActions, start)
