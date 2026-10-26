package com.octaviantocan.pulls.ui.settings

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.octaviantocan.pulls.data.AiCli
import com.octaviantocan.pulls.data.AiSignInStart
import com.octaviantocan.pulls.data.AiSignIns
import com.octaviantocan.pulls.data.Repo
import com.octaviantocan.pulls.data.RpcSource
import com.octaviantocan.pulls.sound.Cue
import com.octaviantocan.pulls.ui.design.CtaButton
import com.octaviantocan.pulls.ui.design.EngineMark
import com.octaviantocan.pulls.ui.design.LocalPullsColors
import com.octaviantocan.pulls.ui.design.LocalToaster
import com.octaviantocan.pulls.ui.design.Lu
import com.octaviantocan.pulls.ui.design.LuIcon
import com.octaviantocan.pulls.ui.design.PullsType
import com.octaviantocan.pulls.ui.design.RowsSkeleton
import com.octaviantocan.pulls.ui.design.SheetCard
import com.octaviantocan.pulls.ui.design.SheetField
import com.octaviantocan.pulls.ui.design.SheetRow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Each person's own Claude Code and Codex sign-in on the server (Tavi, 23 Oct 13:39: "Claude Code and Codex
 * SUBSCRIPTION login. Not any other kind of thing."). The sign-in is the CLI's own, so the page only opens the
 * provider's page and passes Claude Code's code back; there is no API key anywhere.
 */
interface AiSignInServices {
    val dispatchReady: Boolean get() = current()
    fun current(): Boolean
    fun onRetired(clear: () -> Unit): () -> Unit = {}
    suspend fun status(): AiSignIns
    suspend fun start(cli: AiCli): AiSignInStart
    suspend fun finish(cli: AiCli, code: String?): Boolean
    suspend fun cancel(cli: AiCli)
    suspend fun signOut(cli: AiCli)
    fun openPage(url: String)
    fun copy(text: String)
    /** The last sign-in state this source answered, so a row or page shows it at once while it is asked again. */
    fun cached(): AiSignIns? = null
    /** Text on the clipboard, read when Pully comes back from the provider's page, to take Claude's code from it. */
    fun copied(): String? = null
}

/**
 * The sign-ins each source last answered, in memory for this run of the app. The rows and pages show them at once
 * instead of blank values while the server is asked again ("that took so long to load", recording 26 Oct 2026, 04:12).
 * Kept per source, so another server or account never sees them, and only the latest source is kept.
 */
internal object AiSignInMemory {
    private var source: RpcSource? = null
    private var known: AiSignIns? = null
    fun get(of: RpcSource): AiSignIns? = known.takeIf { source == of }
    fun put(of: RpcSource, value: AiSignIns) { source = of; known = value }
}

/**
 * Whether [engine]'s CLI is signed in on [repo]'s server, from the last answer Pully had there.
 *
 * @param repo the source-owned repository whose server answered.
 * @param engine an engine id from `ENGINES`.
 * @returns false or true for Claude Code and Codex once known; null when not known yet or for an engine with no sign-in (Pi).
 */
fun knownAiSignIn(repo: Repo, engine: String): Boolean? {
    val cli = AiCli.entries.firstOrNull { it.engine == engine || "${it.engine}-code" == engine } ?: return null
    return runCatching { repo.source() }.getOrNull()?.let(AiSignInMemory::get)?.get(cli)?.signedIn
}

/**
 * Asks [repo]'s server which CLIs are signed in and keeps the answer for [knownAiSignIn] and the Settings rows.
 *
 * @param repo the source-owned repository to ask.
 */
suspend fun checkAiSignIns(repo: Repo) {
    val source = runCatching { repo.source() }.getOrNull() ?: return
    runCatching { repo.aiSignIns() }.getOrNull()?.let { if (repo.isCurrent(source)) AiSignInMemory.put(source, it) }
}

class SourceAiSignInServices(
    private val repo: Repo,
    private val owner: () -> Boolean,
    private val open: (String) -> Unit,
    private val copyText: (String) -> Unit,
    private val foreground: () -> Boolean = { true },
    private val retirement: ((() -> Unit) -> () -> Unit) = { {} },
    private val readCopied: () -> String? = { null },
) : AiSignInServices {
    private val opening = runCatching { repo.source() }
    override fun current(): Boolean = owner() && opening.fold(onSuccess = repo::isCurrent, onFailure = { false })
    override val dispatchReady: Boolean get() = current() && foreground()
    override fun onRetired(clear: () -> Unit): () -> Unit {
        if (!current()) { clear(); return {} }
        return retirement(clear)
    }
    private fun requireCurrent() { if (!dispatchReady) throw CancellationException("The account or server changed.") }
    override suspend fun status(): AiSignIns {
        requireCurrent()
        val source = opening.getOrThrow()
        return repo.aiSignIns().also { if (current()) AiSignInMemory.put(source, it) }
    }
    override fun cached(): AiSignIns? = if (current()) opening.getOrNull()?.let(AiSignInMemory::get) else null
    override fun copied(): String? = if (dispatchReady) readCopied() else null
    override suspend fun start(cli: AiCli): AiSignInStart { requireCurrent(); return repo.startAiSignIn(cli) }
    override suspend fun finish(cli: AiCli, code: String?): Boolean { requireCurrent(); return repo.finishAiSignIn(cli, code) }
    override suspend fun cancel(cli: AiCli) { requireCurrent(); repo.cancelAiSignIn(cli) }
    override suspend fun signOut(cli: AiCli) { requireCurrent(); repo.signOutAi(cli) }
    override fun openPage(url: String) { if (dispatchReady) open(url) }
    override fun copy(text: String) { if (dispatchReady) copyText(text) }
}

/** What Claude's sign-in page shows to paste back: the code, a `#`, then the state, as one word. */
private val CLAUDE_CODE = Regex("""^[A-Za-z0-9_-]{16,}#[A-Za-z0-9_-]{16,}$""")

/** Whether [text] is a whole code from Claude's sign-in page. */
internal fun isClaudeSignInCode(text: String?): Boolean = text != null && CLAUDE_CODE.matches(text.trim())

/**
 * Whether [text] is the code for this sign-in, started at [url]: its part after `#` is the `state` the page was opened
 * with, so a code left on the clipboard by an earlier sign-in is never sent by itself.
 */
internal fun isCodeFor(url: String, text: String?): Boolean {
    if (!isClaudeSignInCode(text)) return false
    val state = Regex("""[?&]state=([^&#]+)""").find(url)?.groupValues?.get(1) ?: return false
    return text!!.trim().substringAfter('#') == state
}

/** The Settings page for a sign-in row. */
val AiCli.page: SettingsPage get() = if (this == AiCli.Claude) SettingsPage.ClaudeSignIn else SettingsPage.CodexSignIn

/**
 * The Providers rows: each CLI with whether it is signed in. They show the last answer at once and ask again in the
 * background, so a row is never blank or wrong while it loads (recording 26 Oct 2026, 04:10 to 04:19).
 */
@Composable
fun AiSignInRows(services: AiSignInServices, open: (SettingsPage) -> Unit) {
    val c = LocalPullsColors.current
    var signIns by remember(services) { mutableStateOf(services.cached()) }
    LaunchedEffect(services) { runCatching { services.status() }.getOrNull()?.let { signIns = it } }
    AiCli.entries.forEachIndexed { index, cli ->
        SheetRow(cli.title, { open(cli.page) }, first = index == 0, icon = { EngineMark(if (cli == AiCli.Claude) "claude-code" else "codex", size = 22.dp) }) {
            signIns?.let { Text(if (it[cli].signedIn) "Signed in" else "Not signed in", style = PullsType.sm, color = c.muted) }
            LuIcon(Lu.chev, tint = c.faint, size = 18.dp)
        }
    }
}

internal class AiSignInState {
    var signIns by mutableStateOf<AiSignIns?>(null)
    var started by mutableStateOf<AiSignInStart?>(null)
    var code by mutableStateOf("")
    var busy by mutableStateOf(false)
    var problem by mutableStateOf<String?>(null)
    fun clear() { signIns = null; started = null; code = ""; busy = false; problem = null }
}

@Composable
fun ColumnScope.AiSignInPage(services: AiSignInServices, cli: AiCli, open: (SettingsPage) -> Unit) {
    val c = LocalPullsColors.current
    val toaster = LocalToaster.current
    val scope = rememberCoroutineScope()
    val state = remember(services, cli) { AiSignInState().apply { signIns = services.cached() } }
    val dispatchReady = services.dispatchReady
    DisposableEffect(services, state) {
        val stop = services.onRetired(state::clear)
        onDispose { stop(); state.clear() }
    }
    suspend fun refresh() {
        state.signIns = services.status()
    }
    fun run(work: suspend () -> Unit) {
        if (state.busy || !services.dispatchReady) return
        state.busy = true
        state.problem = null
        scope.launch {
            try {
                work()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (services.current()) state.problem = e.message ?: "Something went wrong. Try again."
            } finally { state.busy = false }
        }
    }
    fun signedIn() {
        state.started = null
        state.code = ""
        toaster.show("${cli.title} is signed in", cue = Cue.Success)
    }
    LaunchedEffect(services, cli, dispatchReady) {
        if (!dispatchReady) return@LaunchedEffect
        try { refresh() } catch (e: CancellationException) { throw e } catch (e: Exception) { state.problem = e.message }
    }
    // Codex finishes by itself once its code is entered on OpenAI's page; the server waits up to 25 seconds a call.
    val waiting = state.started?.takeIf { !it.paste }
    LaunchedEffect(waiting, dispatchReady) {
        if (waiting == null || !dispatchReady) return@LaunchedEffect
        while (services.dispatchReady) {
            val done = try {
                services.finish(cli, null)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // A sign-in that finished while Pully was away (the provider's page was in front) has no wait left on
                // the server, which then says it ended. The sign-in itself is what counts ("it did work, but it said it
                // failed", recording 26 Oct 2026, 05:00 to 05:13).
                val now = runCatching { services.status() }.getOrNull()
                state.started = null
                if (now?.get(cli)?.signedIn == true) {
                    state.signIns = now
                    signedIn()
                } else state.problem = e.message
                return@LaunchedEffect
            }
            if (done) {
                refresh()
                signedIn()
                return@LaunchedEffect
            }
            delay(500)
        }
    }

    fun finishPasted() = run { if (services.finish(cli, state.code)) { refresh(); signedIn() } }
    // Back from Claude's page with its code copied, Pully takes the code itself and finishes, so nothing is pasted by
    // hand (recording 26 Oct 2026, 02:59 to 03:13 and 03:45 to 03:55).
    LaunchedEffect(state.started, dispatchReady) {
        val pasting = state.started?.takeIf { it.paste } ?: return@LaunchedEffect
        if (!dispatchReady || state.code.isNotBlank() || state.busy) return@LaunchedEffect
        val copied = services.copied()?.trim()
        if (state.started === pasting && isCodeFor(pasting.url, copied)) {
            state.code = copied!!
            finishPasted()
        }
    }

    val signIns = state.signIns
    val signIn = signIns?.get(cli)
    val started = state.started
    when {
        signIns == null && state.problem == null -> RowsSkeleton(rows = 2)
        signIns == null -> Unit
        !signIns.account -> {
            Explain("Sign in to your Pully account first, so ${cli.title} runs on your own subscription and nobody else's.")
            if (signIn?.signedIn == true) Explain("Until then, AI uses the server's own ${cli.title} sign-in.")
            CtaButton("Sign in to Pully", { open(SettingsPage.Account) }, Modifier.fillMaxWidth().padding(top = 20.dp))
        }
        started != null && started.paste -> {
            Explain("Sign in on the page that opened. When it shows a code, copy it and paste it here.")
            SheetCard {
                SheetField("Code", state.code, { typed ->
                    state.code = typed.trim()
                    // A whole code pasted in finishes at once; there is no button to find under the keyboard.
                    if (isClaudeSignInCode(state.code)) finishPasted()
                }, placeholder = "Paste the code", first = true, enabled = !state.busy,
                    imeAction = ImeAction.Done, onGo = { if (state.code.isNotBlank()) finishPasted() })
                SheetRow("Open the sign-in page again", { services.openPage(started.url) }, iconless = true)
            }
            CtaButton(if (state.busy) "Signing in…" else "Finish signing in", ::finishPasted,
                Modifier.fillMaxWidth().padding(top = 20.dp), enabled = state.code.isNotBlank() && !state.busy)
            Cancel(services, cli, state, ::run)
        }
        started != null -> {
            Explain("Enter this code on the page that opened. Pully finishes by itself once you approve.")
            SheetCard {
                SheetRow(started.code.orEmpty(), { started.code?.let { services.copy(it); toaster.show("Code copied", cue = Cue.Success) } }, first = true, iconless = true) {
                    Text("Copy", style = PullsType.sm, color = c.muted)
                }
                SheetRow("Open the sign-in page again", { services.openPage(started.url) }, iconless = true)
            }
            Explain("If OpenAI says device codes are off, turn on device code sign-in in ChatGPT's security settings, then start again.")
            Cancel(services, cli, state, ::run)
        }
        signIn?.signedIn == true -> {
            SheetCard {
                SheetRow("Signed in", null, first = true, iconless = true) {
                    // The account takes what room is left and ends in "…"; "Signed in" never wraps (recording 26 Oct 2026, 04:03).
                    listOfNotNull(signIn.email, signIn.planName).joinToString(" · ").takeIf { it.isNotEmpty() }?.let {
                        Text(it, Modifier.weight(1f, fill = false), style = PullsType.sm, color = c.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            Explain("Pully's AI work for your account runs on this sign-in and counts against your plan.")
            CtaButton(if (state.busy) "Signing out…" else "Sign out", { run { services.signOut(cli); refresh() } },
                Modifier.fillMaxWidth().padding(top = 20.dp), container = c.error, enabled = !state.busy)
        }
        else -> {
            Explain(if (cli == AiCli.Claude) "Pully runs Claude Code with your own Claude subscription. Sign in once with your Claude account."
                else "Pully runs Codex with your own ChatGPT subscription. Sign in once with your ChatGPT account.")
            CtaButton(if (state.busy) "Starting…" else "Sign in to ${cli.title}", {
                run {
                    val start = services.start(cli)
                    state.code = ""
                    state.started = start
                    // Codex's page asks for the code Pully shows, so it is on the clipboard before the page opens and
                    // only needs pasting there (recording 26 Oct 2026, 04:38 to 04:45).
                    start.code?.takeIf { !start.paste }?.let {
                        services.copy(it)
                        toaster.show("Code copied", cue = Cue.Success)
                    }
                    services.openPage(start.url)
                }
            }, Modifier.fillMaxWidth().padding(top = 20.dp), enabled = !state.busy)
        }
    }
    state.problem?.let { Text(it, Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp), style = PullsType.sm, color = c.error) }
}

@Composable
private fun Explain(text: String) {
    Text(text, Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp), style = PullsType.sm, color = LocalPullsColors.current.muted)
}

@Composable
private fun Cancel(services: AiSignInServices, cli: AiCli, state: AiSignInState, run: (suspend () -> Unit) -> Unit) {
    SheetCard {
        SheetRow("Cancel", { state.started = null; state.code = ""; run { services.cancel(cli) } }, first = true, iconless = true)
    }
}
