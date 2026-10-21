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

/** What the server says about its account: whether it has one, and the client ID for Google sign-in, null when off. */
data class AccountStatus(val hasAccount: Boolean, val googleClientId: String? = null)

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
 * in", Tavi, 16 Oct 12:47), whether the server takes a sign-in at all (with Google or the test account's password), and
 * its Google client ID. The session rides on every call as `X-Pully-Session`, next to the Access headers. A server with
 * no way to sign in asks for none, so the app never locks out a server that has not been set up.
 */
object Account {
    private const val PREFS = "pully-account"
    private var loaded = false

    /** The signed-in email; null while signed out. */
    var email by mutableStateOf<String?>(null)
        private set

    private var session: String? = null

    /** The server [session] came from; the session goes to that server only. */
    private var server: String? = null

    /** `true` once the server said it takes a sign-in, `false` once it said it takes none, null before it was asked. */
    var serverHasAccount by mutableStateOf<Boolean?>(null)
        private set

    /** The client ID the server takes Google sign-in for; null while the server has none, so the page hides the button. */
    var googleClientId by mutableStateOf<String?>(null)
        private set

    fun load(context: Context) {
        if (loaded) return
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        session = prefs.getString("session", null)
        email = prefs.getString("email", null)?.takeIf { session != null }
        server = prefs.getString("server", null)
        // A session kept before it was tied to a server belongs to the one the phone uses now.
        if (session != null && server == null) {
            server = ServerStore.load(context).url.trim()
            prefs.edit().putString("server", server).apply()
        }
        serverHasAccount = if (prefs.contains("hasAccount")) prefs.getBoolean("hasAccount", false) else null
        googleClientId = prefs.getString("googleClientId", null)
        loaded = true
    }

    /** Whether the sign-in page covers the app: the server has an account and this phone is not signed in to it. */
    val mustSignIn: Boolean get() = serverHasAccount == true && email == null

    /**
     * The session header, only for the server it came from and only over HTTPS (or to this machine, as the emulator
     * reaches it), so a changed server address or a plain-HTTP one never receives it.
     */
    fun headers(context: Context): Map<String, String> {
        load(context)
        val url = ServerStore.load(context).url.trim()
        val session = session ?: return emptyMap()
        return if (url == server && carriesSecrets(url)) mapOf("X-Pully-Session" to session) else emptyMap()
    }

    fun signedIn(context: Context, session: String, email: String) {
        com.octaviantocan.pulls.notifications.DeviceNotifications.reset(context)
        this.session = session
        this.email = email
        server = ServerStore.load(context).url.trim()
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("session", session).putString("email", email).putString("server", server).apply()
        com.octaviantocan.pulls.notifications.DeviceNotifications.sync(context)
    }

    /** Forgets the session, as Sign out does and as a session the server no longer knows does. */
    fun signedOut(context: Context) {
        com.octaviantocan.pulls.notifications.DeviceNotifications.reset(context)
        session = null
        email = null
        server = null
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove("session").remove("email").remove("server").apply()
        com.octaviantocan.pulls.notifications.DeviceNotifications.sync(context)
    }

    fun noteServer(context: Context, status: AccountStatus) {
        val ownershipChanged = serverHasAccount != null && serverHasAccount != status.hasAccount
        if (ownershipChanged) com.octaviantocan.pulls.notifications.DeviceNotifications.reset(context)
        serverHasAccount = status.hasAccount
        googleClientId = status.googleClientId
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean("hasAccount", status.hasAccount).putString("googleClientId", status.googleClientId).apply()
        if (ownershipChanged) com.octaviantocan.pulls.notifications.DeviceNotifications.sync(context)
    }
}

/** Hosts a password or session may go to over plain HTTP: this phone, and the machine the emulator runs on. */
private val LOCAL_HOSTS = setOf("localhost", "127.0.0.1", "10.0.2.2")

/** Whether [url] may carry a password or a session: HTTPS, or plain HTTP to [LOCAL_HOSTS] only. */
fun carriesSecrets(url: String): Boolean {
    val uri = runCatching { java.net.URI(url.trim()) }.getOrNull() ?: return false
    return when (uri.scheme?.lowercase()) {
        "https" -> true
        "http" -> uri.host?.lowercase() in LOCAL_HOSTS
        else -> false
    }
}
