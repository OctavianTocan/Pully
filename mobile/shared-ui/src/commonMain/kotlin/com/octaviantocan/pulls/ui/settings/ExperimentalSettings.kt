package com.octaviantocan.pulls.ui.settings

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.runtime.Composable
import com.octaviantocan.pulls.settings.AppSettings
import com.octaviantocan.pulls.ui.design.PullsSwitch
import com.octaviantocan.pulls.ui.design.SheetCard
import com.octaviantocan.pulls.ui.design.SheetRow
import com.octaviantocan.pulls.ui.design.t

/** The optional features share the main Settings page's heading, rows and controls. */
@Composable
internal fun ExperimentalSettings(settings: AppSettings, current: () -> Boolean, platformRows: @Composable ColumnScope.() -> Unit) {
    val features = settings.experiments
    val mergeRisk = t("settings.experiments.mergeRisk")
    fun update(enabled: Boolean) { if (current()) features.updateMergeRisk(enabled) }
    Group(t("settings.experiments.title")) {
        SheetCard {
            SheetRow(mergeRisk, { update(!features.mergeRisk) }, first = true, iconless = true) {
                PullsSwitch(features.mergeRisk, ::update, mergeRisk)
            }
            platformRows()
        }
    }
}
