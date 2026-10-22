package com.octaviantocan.pulls.ui.settings

import androidx.compose.runtime.Composable
import com.octaviantocan.pulls.data.AppSettings
import com.octaviantocan.pulls.ui.design.PullsSwitch
import com.octaviantocan.pulls.ui.design.SheetCard
import com.octaviantocan.pulls.ui.design.SheetRow
import com.octaviantocan.pulls.ui.design.t

/** The optional features share the main Settings page's heading, rows and controls. */
@Composable
internal fun ExperimentalSettings(settings: AppSettings, openBrowserHelper: () -> Unit) {
    val features = settings.experiments
    val mergeRisk = t("settings.experiments.mergeRisk")
    Group(t("settings.experiments.title")) {
        SheetCard {
            SheetRow(mergeRisk, { features.updateMergeRisk(!features.mergeRisk) }, first = true, iconless = true) {
                PullsSwitch(features.mergeRisk, features::updateMergeRisk, mergeRisk)
            }
            BrowserHelperExperimentRow(openBrowserHelper)
        }
    }
}
