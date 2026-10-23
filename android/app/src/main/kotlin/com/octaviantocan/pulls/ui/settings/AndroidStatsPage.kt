package com.octaviantocan.pulls.ui.settings

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.runtime.Composable
import com.octaviantocan.pulls.data.AnalyticsSummary
import com.octaviantocan.pulls.data.AppSettings
import java.util.Locale

/** Android keeps its platform factories while rendering the one actual shared Stats page. */
@Composable
internal fun ColumnScope.StatsPage(settings: AppSettings) = StatsPage(settings, rememberAndroidStatsServices())

/** Existing Android renderer entry point, retaining callers that supply java Locale. */
@Composable
internal fun AiUsage(summary: AnalyticsSummary, locale: Locale) = AiUsage(summary, locale.toLanguageTag())
