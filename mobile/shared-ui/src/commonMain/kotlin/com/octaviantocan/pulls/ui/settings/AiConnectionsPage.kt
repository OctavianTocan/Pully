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
import com.octaviantocan.pulls.ui.design.SheetCard
import com.octaviantocan.pulls.ui.design.SheetField
import com.octaviantocan.pulls.ui.design.SheetRow

/** The host adds this body to its existing Settings pages and routes each kind through that same stack. */
@Composable
fun ColumnScope.AiConnectionsPage(state: AiConnectionsState, onOpen: (AiConnectionKind) -> Unit) {
    val c = LocalPullsColors.current
    val actions = rememberAiConnectionsActions(state, null)
    Group("Your connections") {
        SheetCard {
            AiConnectionKind.entries.forEachIndexed { index, kind ->
                SheetRow(kind.title, if (actions.current) ({ actions.open(kind, onOpen) }) else null, first = index == 0, iconless = true) {
                    Text(state.view(kind)?.status?.label ?: if (state.loading) "Loading…" else "Unavailable", style = PullsType.sm, color = c.muted)
                    LuIcon(Lu.chev, tint = c.faint, size = 18.dp)
                }
            }
        }
    }
    when (state.sharedDefault) {
        AiSharedDefaultStatus.Unavailable -> Unit
        AiSharedDefaultStatus.Available, AiSharedDefaultStatus.Selected -> Group("Default") {
            SheetCard {
                SheetRow("Shared server default", null, first = true, iconless = true) {
                    Text(if (state.sharedDefault == AiSharedDefaultStatus.Selected) "In use" else "Available", style = PullsType.sm, color = c.muted)
                }
            }
        }
    }
    state.loadError?.let { problem ->
        Text(problem.description, Modifier.padding(horizontal = 16.dp, vertical = 12.dp), style = PullsType.sm, color = c.error)
        CtaButton("Retry", actions::refresh, Modifier.fillMaxWidth().padding(top = 8.dp), enabled = actions.current && !state.loading)
    }
}

/** The same detail renderer serves native hosts. Only explicitly installed real operations produce controls. */
@Composable
fun ColumnScope.AiConnectionPage(state: AiConnectionsState, kind: AiConnectionKind) {
    val c = LocalPullsColors.current
    val actions = rememberAiConnectionsActions(state, kind)
    val view = state.view(kind)
    if (view == null) {
        Text(state.loadError?.description ?: if (state.loading) "Loading connection…" else "The connection is unavailable.",
            Modifier.padding(horizontal = 16.dp, vertical = 12.dp), style = PullsType.sm, color = if (state.loadError == null) c.muted else c.error)
        CtaButton("Retry", actions::refresh, Modifier.fillMaxWidth().padding(top = 12.dp), enabled = actions.current && !state.loading)
        return
    }
    SheetCard {
        SheetRow("Status", null, first = true, iconless = true) { Text(view.status.label, style = PullsType.sm, color = c.muted) }
        view.endpointOrigin?.let { origin ->
            SheetRow("Endpoint", null, iconless = true) { Text(origin.value, style = PullsType.sm, color = c.muted) }
        }
        if (kind != AiConnectionKind.PiEndpoint || AiConnectionOperation.ConfigurePi !in view.operations) {
            SheetRow("Default model", null, iconless = true) { Text(view.preferredModel ?: "Provider default", style = PullsType.sm, color = c.muted) }
        }
    }
    view.explanation?.let { Text(it, Modifier.padding(horizontal = 16.dp, vertical = 12.dp), style = PullsType.sm, color = c.muted) }
    if (view.capabilities.models || view.capabilities.execute) Group("Capabilities") {
        SheetCard {
            if (view.capabilities.models) SheetRow("Model catalog", null, first = true, iconless = true) {
                Text("Available", style = PullsType.sm, color = c.muted)
            }
            if (view.capabilities.execute) SheetRow("AI tasks", null, first = !view.capabilities.models, iconless = true) {
                Text("Available", style = PullsType.sm, color = c.muted)
            }
        }
    }
    if (AiConnectionOperation.Connect in view.operations) {
        val label = when {
            state.busy(kind) -> "Connecting…"
            kind == AiConnectionKind.PiEndpoint -> "Check connection"
            view.status == AiConnectionStatus.RefreshNeeded || view.status == AiConnectionStatus.Connected -> "Reconnect"
            view.status == AiConnectionStatus.Pending -> "Continue connecting"
            else -> "Connect"
        }
        CtaButton(label, actions::connect, Modifier.fillMaxWidth().padding(top = 20.dp), enabled = actions.can(AiConnectionOperation.Connect))
    }
    if (kind == AiConnectionKind.PiEndpoint && AiConnectionOperation.ConfigurePi in view.operations) {
        PiConnectionForm(state, view, actions)
    }
    state.error(kind)?.let { problem ->
        Text(problem.description, Modifier.padding(horizontal = 16.dp, vertical = 12.dp), style = PullsType.sm, color = c.error)
        if (kind == AiConnectionKind.PiEndpoint && state.credentialMode == AiPiCredentialMode.Replace && state.credentialInput.isEmpty()) {
            Text("Re-enter the endpoint key to retry.", Modifier.padding(horizontal = 16.dp), style = PullsType.sm, color = c.muted)
        }
    }
    if (AiConnectionOperation.Remove in view.operations) {
        CtaButton(if (state.busy(kind)) "Updating…" else "Remove connection", actions::remove,
            Modifier.fillMaxWidth().padding(top = 20.dp), container = c.error, enabled = actions.can(AiConnectionOperation.Remove))
    }
}

@Composable
private fun ColumnScope.PiConnectionForm(state: AiConnectionsState, view: AiConnectionView, actions: AiConnectionsActions) {
    val c = LocalPullsColors.current
    Group("Pi endpoint") {
        SheetCard {
            SheetField("Endpoint URL", state.endpointInput, actions::endpoint, first = true,
                placeholder = if (view.version == null) "https://ai.example.com/v1" else "Leave blank to keep the saved endpoint",
                enabled = actions.current, keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next)
            SheetField("Default model", state.modelInput, actions::model, placeholder = "Provider default",
                enabled = actions.current, keyboardType = KeyboardType.Ascii, imeAction = ImeAction.Next)
        }
    }
    Group("Endpoint key") {
        SheetCard {
            val modes = buildList {
                if (view.hasCredential && view.version != null) add(AiPiCredentialMode.Keep to "Keep saved key")
                add(AiPiCredentialMode.Replace to "Use a new key")
                add(AiPiCredentialMode.Clear to "No key")
            }
            modes.forEachIndexed { index, (mode, label) ->
                SheetRow(label, if (actions.current) ({ actions.credentialMode(mode) }) else null, first = index == 0, iconless = true) {
                    if (state.credentialMode == mode) LuIcon(Lu.check, tint = c.activeText, size = 20.dp)
                }
            }
            if (state.credentialMode == AiPiCredentialMode.Replace) {
                SheetField("Endpoint key", state.credentialInput, actions::credential, secret = true, enabled = actions.current,
                    keyboardType = KeyboardType.Password, imeAction = ImeAction.Done, onGo = actions::savePi)
            }
        }
        if (state.endpointInput.isNotBlank() && state.credentialMode == AiPiCredentialMode.Keep) {
            Text("Choose a new key or No key when changing the endpoint.", Modifier.padding(horizontal = 16.dp, vertical = 10.dp), style = PullsType.sm, color = c.muted)
        }
    }
    CtaButton(if (state.busy(AiConnectionKind.PiEndpoint)) "Saving…" else "Save connection", actions::savePi,
        Modifier.fillMaxWidth().padding(top = 20.dp), enabled = actions.canSavePi)
}

private val AiConnectionStatus.label: String get() = when (this) {
    AiConnectionStatus.Disconnected -> "Not connected"
    AiConnectionStatus.Pending -> "Connecting"
    AiConnectionStatus.Unverified -> "Not checked"
    AiConnectionStatus.Connected -> "Connected"
    AiConnectionStatus.RefreshNeeded -> "Reconnect needed"
    AiConnectionStatus.Unavailable -> "Temporarily unavailable"
    AiConnectionStatus.Unsupported -> "Not available"
}

private val AiConnectionView.explanation: String? get() = when (notice) {
    AiConnectionNotice.ClaudeApprovalRequired -> "Connecting a Claude subscription requires approval for this service."
    AiConnectionNotice.ChatGPTRegistrationRequired -> "Connecting a ChatGPT subscription requires this service to be registered with OpenAI."
    AiConnectionNotice.PiUnverified -> "Configured, but this endpoint has not been checked."
    AiConnectionNotice.DeploymentUnsupported -> "This connection is not available from this server."
    AiConnectionNotice.ProviderUnavailable -> "The provider is temporarily unavailable. Try again later."
    AiConnectionNotice.RefreshRequired -> "Reconnect to continue using this connection."
    null -> when {
        approvalRequired && kind == AiConnectionKind.ClaudeSubscription -> "Connecting a Claude subscription requires approval for this service."
        approvalRequired && kind == AiConnectionKind.ChatGPTSubscription -> "Connecting a ChatGPT subscription requires this service to be registered with OpenAI."
        status == AiConnectionStatus.Unverified -> "Configured, but this endpoint has not been checked."
        status == AiConnectionStatus.Unsupported -> "This connection is not available from this server."
        else -> null
    }
}

private val AiConnectionProblem.description: String get() = when (this) {
    AiConnectionProblem.Unavailable -> "Pully could not update this connection. Try again."
    AiConnectionProblem.ConnectionChanged -> "This connection changed. Refresh and try again."
    AiConnectionProblem.AuthorizationExpired -> "The connection needs to be authorized again."
    AiConnectionProblem.InvalidEndpoint -> "Use a valid HTTPS endpoint."
    AiConnectionProblem.CredentialRejected -> "The endpoint did not accept that key."
    AiConnectionProblem.Unsupported -> "This connection is not available from this server."
}
