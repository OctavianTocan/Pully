package com.octaviantocan.pulls.ui.review

import androidx.compose.runtime.Composable
import com.octaviantocan.pulls.core.OpenTarget
import com.octaviantocan.pulls.ui.home.NativeHomeActions
import com.octaviantocan.pulls.ui.settings.ModelSettingsServices

/** The same model owner as Settings/Analyze and the host's actual clipboard and browser service. */
class PrSheetServices(
    val models: ModelSettingsServices,
    val native: NativeHomeActions,
    /** An absent platform notification workflow has no visible control. */
    val checks: (@Composable (OpenTarget.Pull?, Boolean) -> Unit)?,
)
