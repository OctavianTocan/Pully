package com.octaviantocan.pulls.ui.review

import com.octaviantocan.pulls.ui.settings.AiSettingsLogic
import com.octaviantocan.pulls.ui.settings.CliCatalog
import com.octaviantocan.pulls.ui.design.Messages

/** `Opus 5.5 · High`: the model the analysis will use and its effort. */
fun analysisModelLabel(config: com.octaviantocan.pulls.ui.settings.ModelConfiguration, catalog: CliCatalog?, effort: String, messages: Messages): String {
    val model = AiSettingsLogic.current(catalog, config.model)
    val name = model?.let(::modelLabel) ?: config.model.ifBlank { null }?.let(::prettyModelId) ?: "Default"
    val shown = effort.ifBlank { model?.defaultEffort.orEmpty() }
    return listOf(name, shown.takeIf { it.isNotBlank() }?.let { AiSettingsLogic.effortLabel(it) { key -> messages.t(key) } }).filterNotNull().joinToString(" · ")
}

