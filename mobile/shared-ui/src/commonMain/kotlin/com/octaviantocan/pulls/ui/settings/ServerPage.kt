package com.octaviantocan.pulls.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.octaviantocan.pulls.sound.cueClickable
import com.octaviantocan.pulls.ui.design.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** The existing server and Access page; native code owns secret storage and connection mutation. */
@Composable
fun ColumnScope.ServerPage(services: ServerPageServices) {
    key(services) { ServerPageContent(services) }
}

@Composable
private fun ColumnScope.ServerPageContent(services: ServerPageServices) {
    val c = LocalPullsColors.current
    val messages = LocalMessages.current
    val scope = rememberCoroutineScope()
    val saved = services.configuration
    var url by remember { mutableStateOf(saved.url) }
    var accessId by remember { mutableStateOf(saved.accessId) }
    var accessSecret by remember { mutableStateOf(saved.accessSecret) }
    var shown by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var checking by remember { mutableStateOf(false) }
    Text(
        "The pulls.review server this phone reads from. A Cloudflare Access service token lets it through the Access login.",
        Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp), style = PullsType.sm.copy(lineHeight = PullsType.sm.fontSize * 1.45f), color = c.muted,
    )
    SheetCard {
        SheetField("Server URL", url, { url = it }, placeholder = "https://prs.example.com", first = true, keyboardType = KeyboardType.Uri)
        SheetField("Access client ID", accessId, { accessId = it }, placeholder = "….access", keyboardType = KeyboardType.Ascii)
        SheetField(
            "Access client secret", accessSecret, { accessSecret = it }, placeholder = "••••", secret = !shown, keyboardType = KeyboardType.Password,
            trailing = {
                Text(
                    if (shown) "Hide" else "Show",
                    Modifier.cueClickable(role = Role.Button) { shown = !shown }.padding(horizontal = 8.dp, vertical = 10.dp),
                    style = PullsType.sm.copy(fontWeight = FontWeight.Medium), color = c.activeText,
                )
            },
        )
    }
    Text("The secret is stored only on this phone.", Modifier.padding(start = 16.dp, end = 16.dp, top = 10.dp), style = PullsType.sm, color = c.muted)
    CtaButton(
        if (checking) "Checking…" else t("common.save"),
        save@ {
            if (runCatching { com.octaviantocan.pulls.data.ServerEndpoint.parse(url) }.isFailure ||
                accessId.isBlank() != accessSecret.isBlank() ||
                accessId.isNotBlank() && !com.octaviantocan.pulls.data.carriesSecrets(url)) {
                status = messages.t("connection.problem.Configuration")
                return@save
            }
            val probe = try {
                services.save(ServerPageConfiguration(url, accessId, accessSecret))
            } catch (_: CancellationException) {
                return@save
            } catch (failure: Exception) {
                status = failure.message ?: "Pully could not save the server."
                return@save
            }
            checking = true
            status = null
            scope.launch {
                status = try {
                    when (val outcome = probe.verify()) {
                        ServerConnectionOutcome.Connected -> messages.t("connection.saved")
                        is ServerConnectionOutcome.Failed -> messages.t("connection.problem.${outcome.problem.name}")
                        ServerConnectionOutcome.Superseded -> null
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    messages.t("connection.problem.${com.octaviantocan.pulls.data.connectionProblem(e).name}")
                }
                checking = false
            }
        },
        Modifier.fillMaxWidth().padding(top = 20.dp), enabled = url.isNotBlank() && !checking,
    )
    status?.let { Text(it, Modifier.padding(horizontal = 16.dp, vertical = 12.dp), style = PullsType.sm, color = c.muted) }
}
