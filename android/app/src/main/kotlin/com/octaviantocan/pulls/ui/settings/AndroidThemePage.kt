package com.octaviantocan.pulls.ui.settings

import com.octaviantocan.pulls.data.RepoIdentity
import com.octaviantocan.pulls.settings.AppSettings
import com.octaviantocan.pulls.ui.explore.NativeEntryState

internal fun captureAndroidColorThemePicker(settings: AppSettings, entry: NativeEntryState): ColorThemePicker {
    val openingEntry = entry.generation
    val openingSource = RepoIdentity.generation
    return ColorThemePicker(settings) { entry.generation == openingEntry && RepoIdentity.generation == openingSource }
}
