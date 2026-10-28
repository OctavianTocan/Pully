package com.octaviantocan.aikit

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

/** The two CLIs the apps run AI with. Each person signs each one in with their own subscription; there are no API keys. */
enum class AiCli(val engine: String, val title: String) {
    Claude("claude", "Claude Code"),
    Codex("codex", "Codex"),
}

/** One CLI's sign-in on the server, for the signed-in account (or the server's own without one). */
data class AiSignIn(val signedIn: Boolean, val email: String? = null, val plan: String? = null) {
    /** The plan as a name ("max" reads "Max"). */
    val planName: String? get() = plan?.replaceFirstChar(Char::titlecaseChar)
}

/** The sign-in status: [account] is false when no account is signed in, so the sign-ins are the server's. */
data class AiSignIns(val account: Boolean, val claude: AiSignIn, val codex: AiSignIn) {
    operator fun get(cli: AiCli): AiSignIn = if (cli == AiCli.Claude) claude else codex
}

/** A started sign-in: the provider's own page to open, Codex's one-time [code], and whether a code is pasted back. */
data class AiSignInStart(val url: String, val code: String?, val paste: Boolean)

private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotEmpty() }
private fun JsonObject.flag(key: String): Boolean = (this[key] as? JsonPrimitive)?.booleanOrNull == true

/** The server's status answer, `{ account, claude: { signedIn, email, plan }, codex: { … } }`. */
fun JsonObject.asAiSignIns(): AiSignIns {
    fun one(key: String) = (this[key] as? JsonObject)?.let { AiSignIn(it.flag("signedIn"), it.text("email"), it.text("plan")) } ?: AiSignIn(false)
    return AiSignIns(flag("account"), one("claude"), one("codex"))
}

/** The server's start answer, `{ url, code, paste }`; a page that is not `https` is refused. */
fun JsonObject.asAiSignInStart(): AiSignInStart {
    val url = text("url").orEmpty()
    require(url.startsWith("https://")) { "The server did not return a sign-in page." }
    return AiSignInStart(url, text("code"), flag("paste"))
}

/** The server's finish answer, `{ signedIn }`: false while Codex still waits for its code to be entered. */
fun JsonObject.asAiSignInFinished(): Boolean = flag("signedIn")

/** What Claude's sign-in page shows to paste back: the code, a `#`, then the state, as one word. */
private val CLAUDE_CODE = Regex("""^[A-Za-z0-9_-]{16,}#[A-Za-z0-9_-]{16,}$""")

/** Whether [text] is a whole code from Claude's sign-in page. */
fun isClaudeSignInCode(text: String?): Boolean = text != null && CLAUDE_CODE.matches(text.trim())

/**
 * Whether [text] is the code for this sign-in, started at [url]: its part after `#` is the `state` the page was opened
 * with, so a code left on the clipboard by an earlier sign-in is never sent by itself.
 */
fun isCodeFor(url: String, text: String?): Boolean {
    if (!isClaudeSignInCode(text)) return false
    val state = Regex("""[?&]state=([^&#]+)""").find(url)?.groupValues?.get(1) ?: return false
    return text!!.trim().substringAfter('#') == state
}

/**
 * Each person's own Claude Code and Codex sign-in on the server (Tavi, 23 Oct 13:39: "Claude Code and Codex
 * SUBSCRIPTION login. Not any other kind of thing."). The sign-in is the CLI's own, so an app only opens the
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
    /** Text on the clipboard, read when the app comes back from the provider's page, to take Claude's code from it. */
    fun copied(): String? = null
}

/** What a sign-in page shows. */
enum class AiSignInStep {
    /** The first status answer has not come. */
    Loading,
    /** The status could not be read; the page shows only the problem. */
    Unavailable,
    /** No account is signed in, so there is nobody's own subscription to run on. */
    NeedsAccount,
    /** Claude Code's page is open and its code is pasted back. */
    PasteCode,
    /** Codex's page is open and asks for the code the app shows; the sign-in finishes by itself. */
    EnterCode,
    SignedIn,
    SignedOut,
}

/** The step a sign-in page for [cli] is at, from the last status, the started sign-in and the last problem. */
fun aiSignInStep(cli: AiCli, signIns: AiSignIns?, started: AiSignInStart?, problem: String?): AiSignInStep = when {
    signIns == null && problem == null -> AiSignInStep.Loading
    signIns == null -> AiSignInStep.Unavailable
    !signIns.account -> AiSignInStep.NeedsAccount
    started != null && started.paste -> AiSignInStep.PasteCode
    started != null -> AiSignInStep.EnterCode
    signIns[cli].signedIn -> AiSignInStep.SignedIn
    else -> AiSignInStep.SignedOut
}

/** How waiting for a device-code sign-in ([AiSignInStep.EnterCode]) ended. */
sealed interface DeviceSignInEnd {
    /** The server says the sign-in finished. */
    data object Done : DeviceSignInEnd

    /**
     * The wait failed but the CLI is signed in: a sign-in that finished while the app was away (the provider's page was
     * in front) has no wait left on the server, which then says it ended ("it did work, but it said it failed",
     * recording 26 Oct 2026, 05:00 to 05:13). [signIns] is the status that says so.
     */
    data class FinishedMeanwhile(val signIns: AiSignIns) : DeviceSignInEnd

    /** The wait failed and the CLI is not signed in. */
    data class Failed(val message: String?) : DeviceSignInEnd

    /** The services stopped being ready to act ([AiSignInServices.dispatchReady]). */
    data object Stopped : DeviceSignInEnd
}

/**
 * Waits for [cli]'s device-code sign-in to finish: Codex finishes by itself once its code is entered on OpenAI's page,
 * and the server waits up to 25 seconds a call, so the app asks again every [pollMs] until it does.
 */
suspend fun AiSignInServices.awaitDeviceSignIn(cli: AiCli, pollMs: Long = 500): DeviceSignInEnd {
    while (dispatchReady) {
        val done = try {
            finish(cli, null)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val now = runCatching { status() }.getOrNull()
            return if (now?.get(cli)?.signedIn == true) DeviceSignInEnd.FinishedMeanwhile(now) else DeviceSignInEnd.Failed(e.message)
        }
        if (done) return DeviceSignInEnd.Done
        delay(pollMs)
    }
    return DeviceSignInEnd.Stopped
}
