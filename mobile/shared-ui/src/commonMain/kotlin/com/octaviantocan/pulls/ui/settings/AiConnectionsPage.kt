package com.octaviantocan.pulls.ui.settings

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.octaviantocan.pulls.ui.design.CtaButton
import com.octaviantocan.pulls.ui.design.LocalPullsColors
import com.octaviantocan.pulls.ui.design.Lu
import com.octaviantocan.pulls.ui.design.LuIcon
import com.octaviantocan.pulls.ui.design.PullsType
import com.octaviantocan.pulls.ui.design.RowsSkeleton
import com.octaviantocan.pulls.ui.design.SheetCard
import com.octaviantocan.pulls.ui.design.SheetField
import com.octaviantocan.pulls.ui.design.SheetRow
import com.octaviantocan.pulls.ui.design.t

/** The host adds this body to its existing Settings pages and routes each kind through that same stack. */
@Composable
fun ColumnScope.AiConnectionsPage(state: AiConnectionsState, onOpen: (AiConnectionKind) -> Unit) {
    val c = LocalPullsColors.current
    val actions = rememberAiConnectionsActions(state, null)
    Group(t("aiConnections.title")) {
        if (AiConnectionKind.entries.all { state.view(it) == null } && state.loadError == null) {
            RowsSkeleton(rows = 3)
        } else SheetCard {
            AiConnectionKind.entries.forEachIndexed { index, kind ->
                SheetRow(t(kind.titleKey), if (actions.current) ({ actions.open(kind, onOpen) }) else null, first = index == 0, iconless = true) {
                    Text(t((state.view(kind)?.status ?: AiConnectionStatus.Unavailable).labelKey), style = PullsType.sm, color = c.muted)
                    LuIcon(Lu.chev, tint = c.faint, size = 18.dp)
                }
            }
        }
    }
    when (state.sharedDefault) {
        AiSharedDefaultStatus.Unavailable -> Unit
        AiSharedDefaultStatus.Available, AiSharedDefaultStatus.Selected -> Group(t("aiConnections.default")) {
            SheetCard {
                SheetRow(t("aiConnections.sharedDefault"), null, first = true, iconless = true) {
                    Text(t(if (state.sharedDefault == AiSharedDefaultStatus.Selected) "aiConnections.inUse" else "aiConnections.available"), style = PullsType.sm, color = c.muted)
                }
            }
        }
    }
    state.loadError?.let { problem ->
        Text(t(problem.descriptionKey), Modifier.padding(horizontal = 16.dp, vertical = 12.dp), style = PullsType.sm, color = c.error)
        CtaButton(t("common.retry"), actions::refresh, Modifier.fillMaxWidth().padding(top = 8.dp), enabled = actions.current && !state.loading)
    }
}

/** The same detail renderer serves native hosts. Only explicitly installed real operations produce controls. */
@Composable
fun ColumnScope.AiConnectionPage(state: AiConnectionsState, kind: AiConnectionKind) {
    val c = LocalPullsColors.current
    val actions = rememberAiConnectionsActions(state, kind)
    val view = state.view(kind)
    if (view == null) {
        val problem = state.loadError
        if (problem == null) RowsSkeleton(rows = 2)
        else {
            Text(t(problem.descriptionKey), Modifier.padding(horizontal = 16.dp, vertical = 12.dp), style = PullsType.sm, color = c.error)
            CtaButton(t("common.retry"), actions::refresh, Modifier.fillMaxWidth().padding(top = 12.dp), enabled = actions.current && !state.loading)
        }
        return
    }
    SheetCard {
        SheetRow(t("aiConnections.statusLabel"), null, first = true, iconless = true) { Text(t(view.status.labelKey), style = PullsType.sm, color = c.muted) }
        view.endpointOrigin?.let { origin ->
            SheetRow(t("aiConnections.endpoint"), null, iconless = true) { Text(origin.value, style = PullsType.sm, color = c.muted) }
        }
        if (kind != AiConnectionKind.PiEndpoint || AiConnectionOperation.ConfigurePi !in view.operations) {
            SheetRow(t("aiConnections.defaultModel"), null, iconless = true) { Text(view.preferredModel ?: t("aiConnections.providerDefault"), style = PullsType.sm, color = c.muted) }
        }
    }
    view.explanationKey?.let { Text(t(it), Modifier.padding(horizontal = 16.dp, vertical = 12.dp), style = PullsType.sm, color = c.muted) }
    if (view.capabilities.models || view.capabilities.execute) Group(t("aiConnections.capabilities")) {
        SheetCard {
            if (view.capabilities.models) SheetRow(t("aiConnections.modelCatalog"), null, first = true, iconless = true) {
                Text(t("aiConnections.available"), style = PullsType.sm, color = c.muted)
            }
            if (view.capabilities.execute) SheetRow(t("aiConnections.aiTasks"), null, first = !view.capabilities.models, iconless = true) {
                Text(t("aiConnections.available"), style = PullsType.sm, color = c.muted)
            }
        }
    }
    if (AiConnectionOperation.Connect in view.operations) {
        val label = when {
            state.busy(kind) -> "aiConnections.connecting"
            kind == AiConnectionKind.PiEndpoint -> "aiConnections.checkConnection"
            view.status == AiConnectionStatus.RefreshNeeded || view.status == AiConnectionStatus.Connected -> "aiConnections.reconnect"
            view.status == AiConnectionStatus.Pending -> "aiConnections.continueConnecting"
            else -> "aiConnections.connect"
        }
        CtaButton(t(label), actions::connect, Modifier.fillMaxWidth().padding(top = 20.dp), enabled = actions.can(AiConnectionOperation.Connect))
    }
    if (kind == AiConnectionKind.PiEndpoint && AiConnectionOperation.ConfigurePi in view.operations) {
        PiConnectionForm(state, view, actions)
    }
    state.error(kind)?.let { problem ->
        Text(t(problem.descriptionKey), Modifier.padding(horizontal = 16.dp, vertical = 12.dp), style = PullsType.sm, color = c.error)
        if (kind == AiConnectionKind.PiEndpoint && state.credentialMode == AiPiCredentialMode.Replace && state.credentialInput.isEmpty()) {
            Text(t("aiConnections.reenterKey"), Modifier.padding(horizontal = 16.dp), style = PullsType.sm, color = c.muted)
        }
    }
    if (AiConnectionOperation.Remove in view.operations) {
        CtaButton(t(if (state.busy(kind)) "aiConnections.updating" else "aiConnections.remove"), actions::remove,
            Modifier.fillMaxWidth().padding(top = 20.dp), container = c.error, enabled = actions.can(AiConnectionOperation.Remove))
    }
}

@Composable
private fun ColumnScope.PiConnectionForm(state: AiConnectionsState, view: AiConnectionView, actions: AiConnectionsActions) {
    val c = LocalPullsColors.current
    Group(t(AiConnectionKind.PiEndpoint.titleKey)) {
        SheetCard {
            SheetField(t("aiConnections.endpointUrl"), state.endpointInput, actions::endpoint, first = true,
                placeholder = if (view.version == null) "https://ai.example.com/v1" else t("aiConnections.keepEndpoint"),
                enabled = actions.current, keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next)
            SheetField(t("aiConnections.defaultModel"), state.modelInput, actions::model, placeholder = t("aiConnections.providerDefault"),
                enabled = actions.current, keyboardType = KeyboardType.Ascii, imeAction = ImeAction.Next)
        }
    }
    Group(t("aiConnections.endpointKey")) {
        SheetCard {
            val modes = buildList {
                if (view.hasCredential && view.version != null) add(AiPiCredentialMode.Keep to "aiConnections.keepKey")
                add(AiPiCredentialMode.Replace to "aiConnections.replaceKey")
                add(AiPiCredentialMode.Clear to "aiConnections.noKey")
            }
            modes.forEachIndexed { index, (mode, label) ->
                SheetRow(t(label), if (actions.current) ({ actions.credentialMode(mode) }) else null, first = index == 0, iconless = true) {
                    if (state.credentialMode == mode) LuIcon(Lu.check, tint = c.activeText, size = 20.dp)
                }
            }
            if (state.credentialMode == AiPiCredentialMode.Replace) {
                SheetField(t("aiConnections.endpointKey"), state.credentialInput, actions::credential, secret = true, enabled = actions.current,
                    keyboardType = KeyboardType.Password, imeAction = ImeAction.Done, onGo = actions::savePi)
            }
        }
        if (state.endpointInput.isNotBlank() && state.credentialMode == AiPiCredentialMode.Keep) {
            Text(t("aiConnections.changedEndpointKey"), Modifier.padding(horizontal = 16.dp, vertical = 10.dp), style = PullsType.sm, color = c.muted)
        }
    }
    CtaButton(t(if (state.busy(AiConnectionKind.PiEndpoint)) "aiConnections.saving" else "aiConnections.save"), actions::savePi,
        Modifier.fillMaxWidth().padding(top = 20.dp), enabled = actions.canSavePi)
}

private val AiConnectionStatus.labelKey: String get() = "aiConnections.status.$name"

private val AiConnectionView.explanationKey: String? get() = when (notice) {
    AiConnectionNotice.ClaudeApprovalRequired -> "aiConnections.notices.ClaudeApprovalRequired"
    AiConnectionNotice.ChatGPTRegistrationRequired -> "aiConnections.notices.ChatGPTRegistrationRequired"
    AiConnectionNotice.PiUnverified -> "aiConnections.notices.PiUnverified"
    AiConnectionNotice.DeploymentUnsupported -> "aiConnections.notices.DeploymentUnsupported"
    AiConnectionNotice.ProviderUnavailable -> "aiConnections.notices.ProviderUnavailable"
    AiConnectionNotice.RefreshRequired -> "aiConnections.notices.RefreshRequired"
    null -> when {
        approvalRequired && kind == AiConnectionKind.ClaudeSubscription -> "aiConnections.notices.ClaudeApprovalRequired"
        approvalRequired && kind == AiConnectionKind.ChatGPTSubscription -> "aiConnections.notices.ChatGPTRegistrationRequired"
        status == AiConnectionStatus.Unverified -> "aiConnections.notices.PiUnverified"
        status == AiConnectionStatus.Unsupported -> "aiConnections.notices.DeploymentUnsupported"
        else -> null
    }
}

private val AiConnectionProblem.descriptionKey: String get() = "aiConnections.problems.$name"
