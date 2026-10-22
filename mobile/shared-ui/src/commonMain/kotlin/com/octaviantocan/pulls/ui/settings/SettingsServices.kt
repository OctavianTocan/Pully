package com.octaviantocan.pulls.ui.settings

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.runtime.Composable
import com.octaviantocan.pulls.settings.AppSettings
import com.octaviantocan.pulls.sound.Direction
import com.octaviantocan.pulls.ui.Nav

/** Native feedback only; the actual setting mutation remains in the shared page. */
interface SettingsFeedback {
    fun vibrationOn()
    fun soundsChanged(enabled: Boolean)
    fun soundSelected(direction: Direction?)
}

/** Android-only capabilities stay in their real host instead of being drawn as ineffective native controls. */
class SettingsPlatformContent(
    val pages: Set<SettingsPage>,
    val beforeStats: @Composable ColumnScope.((SettingsPage) -> Unit) -> Unit,
    val afterModel: @Composable ColumnScope.((SettingsPage) -> Unit) -> Unit,
    val advancedRows: @Composable ColumnScope.((SettingsPage) -> Unit) -> Unit,
    val advancedFooter: @Composable ColumnScope.(Nav) -> Unit,
    val page: @Composable ColumnScope.(SettingsPage, (SettingsPage) -> Unit) -> Unit,
    val experimentalRows: @Composable ColumnScope.((SettingsPage) -> Unit) -> Unit = {},
)

/** One source owner supplies the same choices/catalog instance to Settings, the review frame and Analyze. */
class SettingsServices(
    val settings: AppSettings,
    val models: ModelSettingsServices,
    val account: AccountPagesServices,
    val stats: StatsServices,
    val feedbackPage: FeedbackServices,
    val server: () -> ServerPageServices,
    val feedback: SettingsFeedback,
    val platform: SettingsPlatformContent,
    private val owner: () -> Boolean,
    val showScreen: (String) -> Unit,
) {
    private var closed = false
    fun current() = !closed && owner()
    /** Called by the root before replacement credentials become usable, not by ordinary page navigation. */
    fun close() {
        if (closed) return
        closed = true
        account.close()
        feedbackPage.close()
    }
}
