package com.octaviantocan.pulls.ui.review

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.runtime.Composable
import com.octaviantocan.pulls.settings.AppSettings
import com.octaviantocan.pulls.ui.settings.rememberAndroidModelSettingsServices

@Composable
internal fun ColumnScope.ModelPage(settings: AppSettings, onDone: () -> Unit) =
    ModelPage(settings, rememberAndroidModelSettingsServices(), onDone)

@Composable
internal fun ColumnScope.EffortPage(settings: AppSettings, onBack: () -> Unit) =
    EffortPage(settings, rememberAndroidModelSettingsServices(), onBack)
