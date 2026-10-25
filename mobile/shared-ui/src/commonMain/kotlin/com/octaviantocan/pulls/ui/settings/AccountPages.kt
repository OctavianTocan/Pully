package com.octaviantocan.pulls.ui.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.octaviantocan.pulls.data.AgentKey
import com.octaviantocan.pulls.data.Repo
import com.octaviantocan.pulls.data.ServerError
import com.octaviantocan.pulls.sound.Cue
import com.octaviantocan.pulls.sound.cueClickable
import com.octaviantocan.pulls.ui.design.CtaButton
import com.octaviantocan.pulls.ui.design.LocalPullsColors
import com.octaviantocan.pulls.ui.design.LocalToaster
import com.octaviantocan.pulls.ui.design.Lu
import com.octaviantocan.pulls.ui.design.LuIcon
import com.octaviantocan.pulls.ui.design.PullsMotion
import com.octaviantocan.pulls.ui.design.PullsType
import com.octaviantocan.pulls.ui.design.SheetCard
import com.octaviantocan.pulls.ui.design.SheetField
import com.octaviantocan.pulls.ui.design.SheetRow
import com.octaviantocan.pulls.ui.design.headerTopPadding
import com.octaviantocan.pulls.ui.design.rememberMotionEnabled
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** The account on the main Settings page: who is signed in, and the keys agents post diffs with. Only on a server with an account. */
@Composable
fun AccountGroup(services: AccountPagesServices, open: (SettingsPage) -> Unit) {
    val c = LocalPullsColors.current
    val email = services.email ?: return
    Group("Account") {
        SheetCard {
            SheetRow("Signed in as", { open(SettingsPage.Account) }, first = true, iconless = true) {
                Text(email, Modifier.weight(1f, fill = false), style = PullsType.sm, color = c.muted, maxLines = 1)
                LuIcon(Lu.chev, tint = c.faint, size = 18.dp)
            }
            SheetRow("Agent keys", { open(SettingsPage.AgentKeys) }, iconless = true) {
                services.ui.keys?.let { Text("${it.size}", style = PullsType.sm, color = c.muted) }
                LuIcon(Lu.chev, tint = c.faint, size = 18.dp)
            }
        }
    }
}

/** The signed-in email and Sign out, which forgets the session and brings the sign-in page back. */
@Composable
fun ColumnScope.AccountPage(services: AccountPagesServices) {
    key(services) { AccountPageBody(services) }
}

@Composable
private fun ColumnScope.AccountPageBody(services: AccountPagesServices) {
    val c = LocalPullsColors.current
    val scope = rememberCoroutineScope()
    val signOut = rememberAccountSignOutState(services)
    SheetCard {
        SheetRow("Email", null, first = true, iconless = true) {
            Text(services.email.orEmpty(), Modifier.weight(1f, fill = false), style = PullsType.sm, color = c.muted, maxLines = 1)
        }
    }
    SheetCard(Modifier.padding(top = 12.dp)) {
        SheetRow(if (signOut.busy) "Signing out…" else "Sign out", if (signOut.busy) null else ({
            scope.launch { signOut.signOut() }
            Unit
        }), first = true, danger = true, iconless = true)
    }
    signOut.problem?.let { Text(it, Modifier.padding(top = com.octaviantocan.pulls.ui.design.PullsSpace.x3), style = PullsType.sm, color = c.error) }
}

/**
 * The account's API keys, which agents post diffs with (plan 13): each by its name and first letters, a new one made
 * from a name, and the new key itself shown once, to copy and hand to the agent. The server keeps only a hash of it.
 */
@Composable
fun ColumnScope.AgentKeysPage(services: AccountPagesServices, open: (SettingsPage) -> Unit) {
    key(services) {
        val unavailable = services.agentKeysUnavailableReason
        if (unavailable != null) AgentKeysUnavailable(unavailable) else AgentKeysPageBody(services, open)
    }
}

@Composable
private fun AgentKeysUnavailable(reason: String) {
    Text(reason, Modifier.padding(horizontal = 16.dp, vertical = 12.dp), style = PullsType.read, color = LocalPullsColors.current.muted)
}

@Composable
private fun ColumnScope.AgentKeysPageBody(services: AccountPagesServices, open: (SettingsPage) -> Unit) {
    val c = LocalPullsColors.current
    val toaster = LocalToaster.current
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        try { services.ui.keys = services.keys() } catch (e: CancellationException) { throw e } catch (e: Exception) { if (services.ui.keys == null) problem = e.message }
    }
    DisposableEffect(Unit) { onDispose { services.ui.made = null } }
    fun create() {
        if (name.isBlank() || busy || !services.current()) return
        busy = true
        problem = null
        scope.launch {
            try {
                val made = services.create(name.trim())
                services.ui.made = made
                services.ui.keys = listOf(made.second) + services.ui.keys.orEmpty()
                name = ""
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                problem = e.message
            }
            busy = false
        }
    }

    services.ui.made?.let { (key, item) ->
        Group("New key: ${item.name}") {
            SheetCard {
                Text(key, Modifier.fillMaxWidth().padding(16.dp), style = PullsType.sm.copy(fontFamily = PullsType.mono), color = c.text)
                SheetRow("Copy key", { if (services.copy(key)) toaster.show("Copied the key", cue = Cue.Success) }, iconless = true) {
                    LuIcon(Lu.copy, tint = c.muted, size = 18.dp)
                }
            }
            Text(
                "This is the only time Pully shows it. Give it to the agent with the Post diff to Pully skill.",
                Modifier.padding(start = 16.dp, end = 16.dp, top = 10.dp), style = PullsType.sm, color = c.muted,
            )
        }
    }
    val keys = services.ui.keys
    if (keys != null) {
        Group("Keys") {
            if (keys.isEmpty()) {
                Text("No keys yet.", Modifier.padding(start = 16.dp, end = 16.dp, top = 10.dp), style = PullsType.sm, color = c.muted)
            } else {
                SheetCard {
                    keys.forEachIndexed { index, key ->
                        SheetRow(key.name, { if (services.current()) { services.ui.opened = key; open(SettingsPage.AgentKey) } }, first = index == 0, iconless = true) {
                            Text("${key.hint}…", style = PullsType.sm.copy(fontFamily = PullsType.mono), color = c.muted, maxLines = 1)
                            LuIcon(Lu.chev, tint = c.faint, size = 18.dp)
                        }
                    }
                }
            }
        }
    }
    Group("Make a key") {
        SheetCard {
            SheetField("Name", name, { name = it }, placeholder = "Claude Code on the laptop", first = true, imeAction = ImeAction.Done, onGo = ::create)
        }
    }
    CtaButton(if (busy) "Making…" else "Make key", ::create, Modifier.fillMaxWidth().padding(top = 20.dp), enabled = name.isNotBlank() && !busy)
    problem?.let { Text(it, Modifier.padding(horizontal = 16.dp, vertical = 12.dp), style = PullsType.sm, color = c.error) }
}

/** One key: when it was made and last used, and Revoke, which stops it at once. What it posted stays. */
@Composable
fun ColumnScope.AgentKeyPage(services: AccountPagesServices, onDone: () -> Unit) {
    key(services) {
        val unavailable = services.agentKeysUnavailableReason
        if (unavailable != null) AgentKeysUnavailable(unavailable) else AgentKeyPageBody(services, onDone)
    }
}

@Composable
private fun ColumnScope.AgentKeyPageBody(services: AccountPagesServices, onDone: () -> Unit) {
    val c = LocalPullsColors.current
    val toaster = LocalToaster.current
    val scope = rememberCoroutineScope()
    val key = services.ui.opened ?: return
    var busy by remember { mutableStateOf(false) }
    SheetCard {
        SheetRow("Starts with", null, first = true, iconless = true) {
            Text("${key.hint}…", style = PullsType.sm.copy(fontFamily = PullsType.mono), color = c.muted, maxLines = 1)
        }
        SheetRow("Made", null, iconless = true) { Text(dayOf(key.createdAt), style = PullsType.sm, color = c.muted) }
        SheetRow("Last used", null, iconless = true) { Text(key.lastUsedAt?.let(::dayOf) ?: "Never", style = PullsType.sm, color = c.muted) }
    }
    Text(
        "Revoking the key stops it at once. An agent using it can no longer post, and what it posted stays.",
        Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp), style = PullsType.read, color = c.muted,
    )
    CtaButton(
        "Revoke key",
        {
            busy = true
            scope.launch {
                try {
                    services.revoke(key.id)
                    services.ui.keys = services.ui.keys?.filter { it.id != key.id }
                    toaster.show("Revoked ${key.name}", cue = Cue.Success)
                    onDone()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    toaster.show(e.message ?: "Pully could not revoke the key", cue = Cue.Error)
                }
                busy = false
            }
        },
        Modifier.fillMaxWidth().padding(top = 20.dp), container = c.error, enabled = !busy,
    )
}

