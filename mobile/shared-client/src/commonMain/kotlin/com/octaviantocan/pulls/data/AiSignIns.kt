package com.octaviantocan.pulls.data

import kotlinx.serialization.json.JsonObject

/** The two CLIs Pully runs AI with. Each person signs each one in with their own subscription; there are no API keys. */
enum class AiCli(val engine: String, val title: String) {
    Claude("claude", "Claude Code"),
    Codex("codex", "Codex"),
}

/** One CLI's sign-in on the server, for the signed-in Pully account (or the server's own without one). */
data class AiSignIn(val signedIn: Boolean, val email: String? = null, val plan: String? = null) {
    /** The plan as a name ("max" reads "Max"). */
    val planName: String? get() = plan?.replaceFirstChar(Char::titlecaseChar)
}

/** `ai-login-status`: [account] is false when no Pully account is signed in, so the sign-ins are the server's. */
data class AiSignIns(val account: Boolean, val claude: AiSignIn, val codex: AiSignIn) {
    operator fun get(cli: AiCli): AiSignIn = if (cli == AiCli.Claude) claude else codex
}

/** `ai-login-start`: the provider's own page to open, Codex's one-time [code], and whether a code is pasted back. */
data class AiSignInStart(val url: String, val code: String?, val paste: Boolean)

internal fun JsonObject.asAiSignIns(): AiSignIns {
    fun one(key: String) = obj(key)?.let { AiSignIn(it.b("signedIn"), it.sOrNull("email"), it.sOrNull("plan")) } ?: AiSignIn(false)
    return AiSignIns(b("account"), one("claude"), one("codex"))
}

internal fun JsonObject.asAiSignInStart(): AiSignInStart {
    val url = s("url")
    require(url.startsWith("https://")) { "The server did not return a sign-in page." }
    return AiSignInStart(url, sOrNull("code"), b("paste"))
}
