package com.octaviantocan.pulls.ui.ai

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.octaviantocan.pulls.core.AnalyzeChoice
import com.octaviantocan.pulls.ui.review.ReviewSession
import com.octaviantocan.pulls.ui.settings.rememberAndroidModelSettingsServices

@Composable
internal fun AnalyzeSheet(session: ReviewSession, onClose: () -> Unit, start: AnalyzePage = AnalyzePage.Main, onTour: (() -> Unit)? = null) =
    AnalyzeSheet(session, rememberAndroidModelSettingsServices(), onClose, start, onTour)

@Composable
internal fun ColumnScope.AnalyzePageContent(
    session: ReviewSession, current: AnalyzePage, custom: MutableState<AnalyzeChoice>, onPage: (AnalyzePage) -> Unit,
    onModelDone: () -> Unit, onClose: () -> Unit, onTour: (() -> Unit)? = null, onEffortDone: (() -> Unit)? = null,
) = AnalyzePageContent(session, current, custom, rememberAndroidModelSettingsServices(), onPage, onModelDone, onClose, onTour, onEffortDone)

@Composable
internal fun ModelCard(session: ReviewSession, onModel: () -> Unit, modifier: Modifier = Modifier, onEffort: (() -> Unit)? = null, gap: Dp = 12.dp) =
    ModelCard(session, rememberAndroidModelSettingsServices(), onModel, modifier, onEffort, gap)
