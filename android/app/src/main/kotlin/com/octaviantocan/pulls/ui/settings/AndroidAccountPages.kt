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
import com.octaviantocan.pulls.data.ConnectionProblem
import com.octaviantocan.pulls.data.connectionProblem
import com.octaviantocan.pulls.data.checkAccountConnection
import com.octaviantocan.pulls.data.carriesSecrets
import com.octaviantocan.pulls.data.ServerEndpoint
import com.octaviantocan.pulls.data.RepoIdentity
import com.octaviantocan.pulls.data.ServerStore
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
    ModalReveal(
        Account.mustSignIn, onLeave,
        DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false, dismissOnClickOutside = false),
    ) { SignInPage(onExplore) }
}

/**
 * The heading at the top and everything to do at the bottom, under the thumb, as Zillow and Detail lay theirs out
 * (Appllama, 16 Oct): Continue with Google first, then "or" and the email field alone; Continue shows the password
 * under it, for the test account (Tavi, 16 Oct 16:52). Without Google on the server the email is the page's one action.
 */
@Composable
internal fun SignInPage(onExplore: (() -> Unit)? = null) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var epoch by remember { mutableStateOf(com.octaviantocan.pulls.data.RepoIdentity.generation) }
    DisposableEffect(context) {
        val remove = com.octaviantocan.pulls.data.RepoIdentity.observeInvalidation {
            scope.launch { epoch = com.octaviantocan.pulls.data.RepoIdentity.generation }
        }
        epoch = com.octaviantocan.pulls.data.RepoIdentity.generation
        onDispose { remove() }
    }
    val services = remember(context, epoch) { AndroidSignInServices(context, epoch) }
    var serverSettings by remember(services) { mutableStateOf(false) }
    SignInPage(services, artwork = { mark, tint, size ->
        DrawableIcon(if (mark == SignInMark.Pully) R.drawable.ic_stat_pulls else R.drawable.ic_si_google, tint = tint, size = size)
    }, onServerSettings = { if (services.current()) serverSettings = true },
        onExplore = onExplore?.let { explore -> { if (services.current()) explore() } })
    if (serverSettings) ServerConnectionSheet(onClose = { serverSettings = false })
}

private class AndroidSignInServices(private val context: Context, private val epoch: Long) : SignInServices {
    private val repo = Repo(context)
    private val opening = runCatching { repo.source() }
    private var active = true
    private val controller by lazy {
        com.octaviantocan.pulls.data.SignInController(
            repo, opening.getOrThrow(),
            { active && epoch == com.octaviantocan.pulls.data.RepoIdentity.generation },
        ) { session, email -> Account.signedIn(context, session, email) }
    }
    fun current() = active && epoch == RepoIdentity.generation && opening.getOrNull()?.let(repo::isCurrent) == true
    override val serverLabel = ServerStore.load(context).url.removePrefix("https://").removePrefix("http://").trimEnd('/')
    override val googleClientId get() = Account.googleClientId
    override suspend fun password(email: String, password: String) {
        controller.password(email, password)
        SettingsSync.sync(context)
    }
    override suspend fun google(clientId: String) {
        if (controller.google { GoogleSignIn.idToken(context, clientId) }) SettingsSync.sync(context)
    }
    override fun close() { active = false }
}

/** Existing Android account-page entry points. */
internal object AccountUi : AccountPageState()

@Composable
internal fun AccountGroup(open: (SettingsPage) -> Unit) = AccountGroup(rememberAndroidAccountPagesServices(), open)

@Composable
internal fun ColumnScope.AccountPage() = AccountPage(rememberAndroidAccountPagesServices())

@Composable
internal fun ColumnScope.AgentKeysPage(open: (SettingsPage) -> Unit) = AgentKeysPage(rememberAndroidAccountPagesServices(), open)

@Composable
internal fun ColumnScope.AgentKeyPage(onDone: () -> Unit) = AgentKeyPage(rememberAndroidAccountPagesServices(), onDone)
