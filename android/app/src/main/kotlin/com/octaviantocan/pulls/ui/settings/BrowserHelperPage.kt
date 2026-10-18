package com.octaviantocan.pulls.ui.settings

import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.octaviantocan.pulls.browser.BrowserHelperSettings
import com.octaviantocan.pulls.ui.design.CtaButton
import com.octaviantocan.pulls.ui.design.LocalMessages
import com.octaviantocan.pulls.ui.design.LocalPullsColors
import com.octaviantocan.pulls.ui.design.LocalToaster
import com.octaviantocan.pulls.ui.design.Lu
import com.octaviantocan.pulls.ui.design.LuIcon
import com.octaviantocan.pulls.ui.design.PullsSpace
import com.octaviantocan.pulls.ui.design.PullsType
import com.octaviantocan.pulls.ui.design.SheetCard
import com.octaviantocan.pulls.ui.design.SheetRow
import com.octaviantocan.pulls.ui.design.t

/** Pully's consent and Android's permission are separate; neither alone turns the helper on. */
private data class BrowserHelperState(val allowed: Boolean, val serviceEnabled: Boolean) {
    val statusKey: String get() = when {
        !allowed -> "off"
        !serviceEnabled -> "needsPermission"
        else -> "on"
    }
}

private fun browserHelperState(context: Context) = BrowserHelperState(
    BrowserHelperSettings.allowed(context),
    BrowserHelperSettings.serviceEnabled(context),
)

/** Read Android's current service state on return, rather than assuming a visit enabled it. */
@Composable
private fun rememberBrowserHelperState(): BrowserHelperState {
    val context = LocalContext.current
    var state by remember(context) { mutableStateOf(browserHelperState(context)) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { state = browserHelperState(context) }
    return state
}

/** A page row, never a switch that grants accessibility access before its disclosure. */
@Composable
internal fun BrowserHelperGroup(open: () -> Unit) {
    val c = LocalPullsColors.current
    val state = rememberBrowserHelperState()
    Group(t("settings.browserHelper.group")) {
        SheetCard {
            SheetRow(t("settings.browserHelper.title"), open, first = true, iconless = true) {
                Text(t("settings.browserHelper.${state.statusKey}"), style = PullsType.sm, color = c.muted)
                LuIcon(Lu.chev, tint = c.faint)
            }
        }
    }
}

/** Prominent consent disclosure before the sole action that allows browser inspection. */
@Composable
internal fun ColumnScope.BrowserHelperPage() {
    val c = LocalPullsColors.current
    val context = LocalContext.current
    val messages = LocalMessages.current
    val toaster = LocalToaster.current
    var state by remember(context) { mutableStateOf(browserHelperState(context)) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { state = browserHelperState(context) }

    fun openAndroidSettings() {
        runCatching { context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
            .onFailure { toaster.show(messages.t("settings.browserHelper.settingsUnavailable")) }
    }

    SheetCard {
        Column(
            Modifier.fillMaxWidth().padding(PullsSpace.x4),
            verticalArrangement = Arrangement.spacedBy(PullsSpace.x3),
        ) {
            Text(t("settings.browserHelper.disclosureTitle"), style = PullsType.row.copy(fontWeight = PullsType.medium), color = c.text)
            Text(t("settings.browserHelper.disclosureAccess"), style = PullsType.read, color = c.text)
            Text(t("settings.browserHelper.disclosureUse"), style = PullsType.read, color = c.text)
            Text(t("settings.browserHelper.disclosurePrivacy"), style = PullsType.read, color = c.text)
            Text(t("settings.browserHelper.disclosureLimits"), style = PullsType.read, color = c.text)
        }
    }
    if (!state.allowed) {
        CtaButton(
            t("settings.browserHelper.continue"),
            {
                BrowserHelperSettings.setAllowed(context, true)
                state = browserHelperState(context)
                openAndroidSettings()
            },
            Modifier.fillMaxWidth().padding(top = PullsSpace.x4),
        )
    } else {
        SheetCard {
            SheetRow(t("settings.browserHelper.permission"), null, first = true, iconless = true) {
                Text(t("settings.browserHelper.${if (state.serviceEnabled) "on" else "off"}"), style = PullsType.sm, color = c.muted)
            }
            SheetRow(t("settings.browserHelper.androidSettings"), ::openAndroidSettings, iconless = true) {
                LuIcon(Lu.chev, tint = c.faint)
            }
            SheetRow(t("settings.browserHelper.disable"), {
                BrowserHelperSettings.setAllowed(context, false)
                state = browserHelperState(context)
            }, iconless = true)
        }
    }
}
