package com.octaviantocan.pulls.data

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.jsonPrimitive

/** An API key of the account, as Settings, Agent keys lists it: never the key itself, which is shown once. */
data class AgentKey(val id: String, val name: String, val hint: String, val createdAt: String, val lastUsedAt: String?)

/** A diff an agent posted to the account (plan 13), as the Inbox lists it. */
data class PostedRow(
    val id: String,
    val title: String,
    /** Who posted it: the name the agent gave, or its key's. */
    val author: String,
    val version: Int,
    val updatedAt: String,
    val files: Int,
    val additions: Int,
    val deletions: Int,
)

/** One setting as the account keeps it: its value and when it was changed, so the newer of two phones wins. */
data class SyncedSetting(val value: JsonElement, val at: Long)

internal fun JsonObject.asAgentKey() = AgentKey(s("id"), s("name"), s("hint"), s("createdAt"), sOrNull("lastUsedAt"))

internal fun JsonObject.asPostedRow() = PostedRow(s("id"), s("title"), s("author"), i("version") ?: 1, s("updatedAt"), i("files") ?: 0, i("additions") ?: 0, i("deletions") ?: 0)

internal fun JsonObject.asSyncedSettings(): Map<String, SyncedSetting> =
    (obj("values") ?: JsonObject(emptyMap())).mapNotNull { (name, raw) ->
        val o = raw as? JsonObject ?: return@mapNotNull null
        val value = o["value"] ?: return@mapNotNull null
        val at = (o["at"]?.jsonPrimitive?.longOrNull) ?: return@mapNotNull null
        name to SyncedSetting(value, at)
    }.toMap()

/**
 * The phone's account (plan 13): the session it signed in with, kept until Sign out ("once you sign in it's just signed
 * in", Tavi, 16 Oct 12:47), and whether the server has an account at all. The session rides on every call as
 * `X-Pully-Session`, next to the Access headers. A server with no account yet asks for no sign-in, so the app never
 * locks out a server that has not been set up.
 */
object Account {
    private const val PREFS = "pully-account"
    private var loaded = false

    /** The signed-in email; null while signed out. */
    var email by mutableStateOf<String?>(null)
        private set

    private var session: String? = null

    /** `true` once the server said it has an account, `false` once it said it has none, null before it was asked. */
    var serverHasAccount by mutableStateOf<Boolean?>(null)
        private set

    fun load(context: Context) {
        if (loaded) return
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        session = prefs.getString("session", null)
        email = prefs.getString("email", null)?.takeIf { session != null }
        serverHasAccount = if (prefs.contains("hasAccount")) prefs.getBoolean("hasAccount", false) else null
        loaded = true
    }

    /** Whether the sign-in page covers the app: the server has an account and this phone is not signed in to it. */
    val mustSignIn: Boolean get() = serverHasAccount == true && email == null

    fun headers(context: Context): Map<String, String> {
        load(context)
        return session?.let { mapOf("X-Pully-Session" to it) } ?: emptyMap()
    }

    fun signedIn(context: Context, session: String, email: String) {
        this.session = session
        this.email = email
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("session", session).putString("email", email).apply()
    }

    /** Forgets the session, as Sign out does and as a session the server no longer knows does. */
    fun signedOut(context: Context) {
        session = null
        email = null
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove("session").remove("email").apply()
    }

    fun noteServer(context: Context, hasAccount: Boolean) {
        serverHasAccount = hasAccount
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("hasAccount", hasAccount).apply()
    }
}
