package com.octaviantocan.pulls.data

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

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
    fun headers(context: Context, url: String = ServerStore.load(context).url.trim()): Map<String, String> {
        load(context)
        val session = session ?: return emptyMap()
        return if (url == server && carriesSecrets(url)) mapOf("X-Pully-Session" to session) else emptyMap()
    }

    fun signedIn(context: Context, session: String, email: String) {
        com.octaviantocan.pulls.notifications.DeviceNotifications.reset(context)
        RepoIdentity.invalidate()
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
        RepoIdentity.invalidate()
        session = null
        email = null
        server = null
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove("session").remove("email").remove("server").apply()
        com.octaviantocan.pulls.notifications.DeviceNotifications.sync(context)
    }

    fun noteServer(context: Context, status: AccountStatus) {
        val ownershipChanged = serverHasAccount != null && serverHasAccount != status.hasAccount
        if (ownershipChanged) {
            RepoIdentity.invalidate()
            com.octaviantocan.pulls.notifications.DeviceNotifications.reset(context)
        }
        serverHasAccount = status.hasAccount
        googleClientId = status.googleClientId
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean("hasAccount", status.hasAccount).putString("googleClientId", status.googleClientId).apply()
        if (ownershipChanged) com.octaviantocan.pulls.notifications.DeviceNotifications.sync(context)
    }
}
