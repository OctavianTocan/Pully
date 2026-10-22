package com.octaviantocan.pulls.ui.settings

import android.content.Context
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.octaviantocan.pulls.R
import com.octaviantocan.pulls.data.Account
import com.octaviantocan.pulls.data.AgentKey
import com.octaviantocan.pulls.data.GoogleSignIn
import com.octaviantocan.pulls.data.Repo
import com.octaviantocan.pulls.data.ServerError
import com.octaviantocan.pulls.data.ServerStore
import com.octaviantocan.pulls.data.RepoIdentity
import com.octaviantocan.pulls.data.ServerEndpoint
import com.octaviantocan.pulls.data.carriesSecrets
import com.octaviantocan.pulls.data.checkAccountConnection
import com.octaviantocan.pulls.data.connectionProblem
import com.octaviantocan.pulls.data.ConnectionProblem
import com.octaviantocan.pulls.data.SignInController
import com.octaviantocan.pulls.data.SettingsSync
import com.octaviantocan.pulls.sound.Cue
import com.octaviantocan.pulls.sound.cueClickable
import com.octaviantocan.pulls.ui.design.CtaButton
import com.octaviantocan.pulls.ui.design.LocalPullsColors
import com.octaviantocan.pulls.ui.design.LocalToaster
import com.octaviantocan.pulls.ui.design.Lu
import com.octaviantocan.pulls.ui.design.LuIcon
import com.octaviantocan.pulls.ui.design.ModalReveal
import com.octaviantocan.pulls.ui.design.PullsMotion
import com.octaviantocan.pulls.ui.design.PullsType
import com.octaviantocan.pulls.ui.design.PullsSpace
import com.octaviantocan.pulls.ui.design.PillButton
import com.octaviantocan.pulls.ui.design.SheetCard
import com.octaviantocan.pulls.ui.design.SheetField
import com.octaviantocan.pulls.ui.design.SheetRow
import com.octaviantocan.pulls.ui.design.headerTopPadding
import com.octaviantocan.pulls.ui.design.rememberMotionEnabled
import com.octaviantocan.pulls.ui.home.DrawableIcon
import com.octaviantocan.pulls.ui.review.copyText
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * Asks the server about the account on start and whenever the app comes back to the front: whether it has one, and
 * whether this phone's session still works. A session the server no longer knows is forgotten, so the sign-in page
 * comes back; a server that cannot be reached changes nothing, so a phone offline is never locked out.
 */
@Composable
internal fun AccountCheck() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var epoch by remember { androidx.compose.runtime.mutableLongStateOf(RepoIdentity.generation) }
    DisposableEffect(Unit) {
        val unsubscribe = RepoIdentity.observeInvalidation { scope.launch(kotlinx.coroutines.Dispatchers.Main.immediate) { epoch = RepoIdentity.generation } }
        epoch = RepoIdentity.generation
        onDispose { unsubscribe() }
    }
    LaunchedEffect(epoch) { checkAccount(context) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { scope.launch { checkAccount(context) } }
}

internal suspend fun checkAccount(context: Context): ConnectionCheckResult {
    Account.load(context)
    val revision = Account.beginConnectionCheck()
    val repo = Repo(context)
    try {
        val config = ServerStore.load(context)
        if (runCatching { ServerEndpoint.parse(config.url) }.isFailure ||
            config.accessId.isBlank() != config.accessSecret.isBlank() ||
            config.headers().isNotEmpty() && !carriesSecrets(config.url)) {
            Account.connectionFailed(revision, ConnectionProblem.Configuration)
            return connectionCheckResult(revision, ConnectionProblem.Configuration)
        }
        val connection = checkAccountConnection(repo, Account.email)
        if (!Account.ownsConnectionCheck(revision)) return ConnectionCheckResult.Superseded
        connection.status?.let { Account.noteServer(context, it) }
        Account.connectionAnswered(context, revision)
        if (connection.sessionExpired) Account.signedOut(context)
        else if (connection.status?.hasAccount == true && Account.email != null) {
            try { SettingsSync.sync(context) } catch (e: CancellationException) { throw e } catch (_: Exception) { }
        }
        return connectionCheckResult(revision)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        val outcome = connectionCheckResult(revision, connectionProblem(e))
        if (outcome is ConnectionCheckResult.Failed) Account.connectionFailed(revision, outcome.problem)
        return outcome
    } finally {
        Account.finishConnectionCheck(revision)
    }
}

/**
 * The sign-in page: while the server takes a sign-in and this phone is not signed in, it covers the app, as the update
 * screen does ("it actually should get a sign in screen, but once you sign in it's just signed in": Tavi, 16 Oct
 * 12:47). Back leaves Pully.
 */
@Composable
internal fun SignInGate(onLeave: () -> Unit, onExplore: (() -> Unit)? = null) {
    val scope = rememberCoroutineScope()
    var epoch by remember { androidx.compose.runtime.mutableLongStateOf(RepoIdentity.generation) }
    DisposableEffect(Unit) {
        val unsubscribe = RepoIdentity.observeInvalidation { scope.launch(kotlinx.coroutines.Dispatchers.Main.immediate) { epoch = RepoIdentity.generation } }
        epoch = RepoIdentity.generation
        onDispose { unsubscribe() }
    }
    ModalReveal(
        Account.mustSignIn, onLeave,
        DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false, dismissOnClickOutside = false),
    ) { key(epoch) { SignInPage(epoch, onExplore) } }
}

/**
 * The heading at the top and everything to do at the bottom, under the thumb, as Zillow and Detail lay theirs out
 * (Appllama, 16 Oct): Continue with Google first, then "or" and the email field alone; Continue shows the password
 * under it, for the test account (Tavi, 16 Oct 16:52). Without Google on the server the email is the page's one action.
 */
@Composable
internal fun SignInPage(openingEpoch: Long, onExplore: (() -> Unit)? = null) {
    val c = LocalPullsColors.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val motion = rememberMotionEnabled()
    val repo = remember { Repo(context) }
    val source = remember { repo.source() }
    val owner = remember { object { var active = true } }
    DisposableEffect(Unit) { onDispose { owner.active = false } }
    val controller = remember {
        SignInController(repo, source, { owner.active && RepoIdentity.generation == openingEpoch }) { session, signedAs ->
            Account.signedIn(context, session, signedAs)
        }
    }
    val server = remember { ServerStore.load(context).url.removePrefix("https://").removePrefix("http://").trimEnd('/') }
    val googleClientId = Account.googleClientId
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var asked by remember { mutableStateOf(false) }
    var shown by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf<SignInWay?>(null) }
    var problem by remember { mutableStateOf<String?>(null) }
    var serverSettings by remember { mutableStateOf(false) }
    val ready = busy == null && email.isNotBlank() && (!asked || password.isNotEmpty())
    fun signIn(way: SignInWay, attempt: suspend () -> Boolean) {
        if (busy != null) return
        busy = way
        problem = null
        scope.launch {
            try {
                if (attempt()) SettingsSync.sync(context)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                problem = (e as? ServerError)?.message ?: "Pully could not sign in. Try again or check Server settings."
            } finally { busy = null }
        }
    }
    fun next() {
        if (!ready) return
        if (!asked) {
            asked = true
            return
        }
        signIn(SignInWay.Password) { controller.password(email.trim(), password); true }
    }
    fun google(clientId: String) = signIn(SignInWay.Google) {
        // Closing Google's account sheet is not a failure: the page stays as it was.
        controller.google { GoogleSignIn.idToken(context, clientId) }
    }
    Column(
        Modifier.fillMaxSize().background(c.base).padding(top = headerTopPadding()).imePadding().navigationBarsPadding()
            .padding(start = 16.dp, end = 16.dp, bottom = 24.dp),
    ) {
        // Pully's own mark over the heading, as Zillow's sits over its welcome.
        DrawableIcon(R.drawable.ic_stat_pulls, tint = c.text, size = 40.dp)
        Text("Sign in to Pully", Modifier.padding(top = 24.dp), style = PullsType.xxl, color = c.text)
        if (server.isNotBlank()) Text("Your account on $server.", Modifier.padding(top = 8.dp), style = PullsType.read, color = c.muted)
        Spacer(Modifier.weight(1f))
        if (googleClientId != null) {
            CtaButton(
                if (busy == SignInWay.Google) "Signing in…" else "Continue with Google", { google(googleClientId) },
                Modifier.fillMaxWidth(), enabled = busy == null,
                leading = { DrawableIcon(R.drawable.ic_si_google, tint = c.onCta, size = 18.dp) },
            )
            Row(Modifier.fillMaxWidth().padding(vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f).height(1.dp).background(c.borderMute))
                Text("or", Modifier.padding(horizontal = 12.dp), style = PullsType.sm, color = c.faint)
                Box(Modifier.weight(1f).height(1.dp).background(c.borderMute))
            }
        }
        SheetCard {
            SheetField(
                "Email", email, { email = it }, placeholder = "you@example.com", first = true, keyboardType = KeyboardType.Email,
                imeAction = if (asked) ImeAction.Next else ImeAction.Go, onGo = ::next,
            )
            AnimatedVisibility(
                asked,
                enter = if (motion) fadeIn(tween(PullsMotion.FAST, easing = PullsMotion.SmoothOut)) + expandVertically(tween(PullsMotion.FAST, easing = PullsMotion.SmoothOut)) else EnterTransition.None,
                exit = ExitTransition.None,
            ) {
                SheetField(
                    "Password", password, { password = it }, secret = !shown, keyboardType = KeyboardType.Password, imeAction = ImeAction.Go, onGo = ::next,
                    trailing = {
                        Text(
                            if (shown) "Hide" else "Show",
                            Modifier.cueClickable(role = Role.Button) { shown = !shown }.padding(horizontal = 8.dp, vertical = 10.dp),
                            style = PullsType.sm.copy(fontWeight = FontWeight.Medium), color = c.activeText,
                        )
                    },
                )
            }
        }
        problem?.let { Text(it, Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp), style = PullsType.sm, color = c.error) }
        val label = when {
            busy == SignInWay.Password -> "Signing in…"
            asked -> "Sign in"
            else -> "Continue"
        }
        // With Google on the page, Google is the one main action and the email's button is the quiet one.
        if (googleClientId != null) {
            CtaButton(label, ::next, Modifier.fillMaxWidth().padding(top = 12.dp), container = c.paper2, contentColor = c.text, enabled = ready)
        } else {
            CtaButton(label, ::next, Modifier.fillMaxWidth().padding(top = 12.dp), enabled = ready)
        }
        CtaButton(com.octaviantocan.pulls.ui.design.t("connection.serverSettings"), { serverSettings = true }, Modifier.fillMaxWidth().padding(top = PullsSpace.x3), container = c.paper2, contentColor = c.text, enabled = busy == null)
        if (onExplore != null) PillButton(com.octaviantocan.pulls.ui.design.t("explore.browse"), {
            if (owner.active && RepoIdentity.generation == openingEpoch) onExplore()
        }, Modifier.fillMaxWidth().padding(top = PullsSpace.x3))
    }
    if (serverSettings) ServerConnectionSheet(onClose = { serverSettings = false })
}

/** Which way the page is signing in, so only that button says "Signing in…". */
private enum class SignInWay { Password, Google }

/** What the account pages share while Settings is open: the keys as last listed, the one opened, and a key just made. */
internal object AccountUi {
    var keys by mutableStateOf<List<AgentKey>?>(null)
    var opened by mutableStateOf<AgentKey?>(null)
    /** A key just made: the only time it is shown, until the Agent keys page closes. */
    var made by mutableStateOf<Pair<String, AgentKey>?>(null)
}

/** The account on the main Settings page: who is signed in, and the keys agents post diffs with. Only on a server with an account. */
@Composable
internal fun AccountGroup(open: (SettingsPage) -> Unit) {
    val c = LocalPullsColors.current
    val email = Account.email ?: return
    Group("Account") {
        SheetCard {
            SheetRow("Signed in as", { open(SettingsPage.Account) }, first = true, iconless = true) {
                Text(email, Modifier.weight(1f, fill = false), style = PullsType.sm, color = c.muted, maxLines = 1)
                LuIcon(Lu.chev, tint = c.faint, size = 18.dp)
            }
            SheetRow("Agent keys", { open(SettingsPage.AgentKeys) }, iconless = true) {
                AccountUi.keys?.let { Text("${it.size}", style = PullsType.sm, color = c.muted) }
                LuIcon(Lu.chev, tint = c.faint, size = 18.dp)
            }
        }
    }
}

/** The signed-in email and Sign out, which forgets the session and brings the sign-in page back. */
@Composable
internal fun ColumnScope.AccountPage() {
    val c = LocalPullsColors.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    SheetCard {
        SheetRow("Email", null, first = true, iconless = true) {
            Text(Account.email.orEmpty(), Modifier.weight(1f, fill = false), style = PullsType.sm, color = c.muted, maxLines = 1)
        }
    }
    SheetCard(Modifier.padding(top = 12.dp)) {
        SheetRow("Sign out", {
            scope.launch {
                try { Repo(context).signOut() } catch (e: CancellationException) { throw e } catch (_: Exception) {}
                AccountUi.keys = null
                Account.signedOut(context)
            }
        }, first = true, danger = true, iconless = true)
    }
}

/**
 * The account's API keys, which agents post diffs with (plan 13): each by its name and first letters, a new one made
 * from a name, and the new key itself shown once, to copy and hand to the agent. The server keeps only a hash of it.
 */
@Composable
internal fun ColumnScope.AgentKeysPage(open: (SettingsPage) -> Unit) {
    val c = LocalPullsColors.current
    val context = LocalContext.current
    val toaster = LocalToaster.current
    val scope = rememberCoroutineScope()
    val repo = remember { Repo(context) }
    var name by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        try { AccountUi.keys = repo.agentKeys() } catch (e: CancellationException) { throw e } catch (e: Exception) { if (AccountUi.keys == null) problem = e.message }
    }
    DisposableEffect(Unit) { onDispose { AccountUi.made = null } }
    fun create() {
        if (name.isBlank() || busy) return
        busy = true
        problem = null
        scope.launch {
            try {
                val made = repo.createAgentKey(name.trim())
                AccountUi.made = made
                AccountUi.keys = listOf(made.second) + AccountUi.keys.orEmpty()
                name = ""
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                problem = e.message
            }
            busy = false
        }
    }

    AccountUi.made?.let { (key, item) ->
        Group("New key: ${item.name}") {
            SheetCard {
                Text(key, Modifier.fillMaxWidth().padding(16.dp), style = PullsType.sm.copy(fontFamily = PullsType.mono), color = c.text)
                SheetRow("Copy key", { copyText(context, key); toaster.show("Copied the key", cue = Cue.Success) }, iconless = true) {
                    LuIcon(Lu.copy, tint = c.muted, size = 18.dp)
                }
            }
            Text(
                "This is the only time Pully shows it. Give it to the agent with the Post diff to Pully skill.",
                Modifier.padding(start = 16.dp, end = 16.dp, top = 10.dp), style = PullsType.sm, color = c.muted,
            )
        }
    }
    val keys = AccountUi.keys
    if (keys != null) {
        Group("Keys") {
            if (keys.isEmpty()) {
                Text("No keys yet.", Modifier.padding(start = 16.dp, end = 16.dp, top = 10.dp), style = PullsType.sm, color = c.muted)
            } else {
                SheetCard {
                    keys.forEachIndexed { index, key ->
                        SheetRow(key.name, { AccountUi.opened = key; open(SettingsPage.AgentKey) }, first = index == 0, iconless = true) {
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
internal fun ColumnScope.AgentKeyPage(onDone: () -> Unit) {
    val c = LocalPullsColors.current
    val context = LocalContext.current
    val toaster = LocalToaster.current
    val scope = rememberCoroutineScope()
    val key = AccountUi.opened ?: return
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
                    Repo(context).revokeAgentKey(key.id)
                    AccountUi.keys = AccountUi.keys?.filter { it.id != key.id }
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

private val DAY = DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm", Locale.ENGLISH)

/** An ISO time as the day and minute it was, on this phone's clock. */
internal fun dayOf(iso: String): String = runCatching { DAY.format(Instant.parse(iso).atZone(ZoneId.systemDefault())) }.getOrDefault(iso)
