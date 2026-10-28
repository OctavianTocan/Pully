package com.octaviantocan.pulls.ui.settings

/** An immutable native snapshot; credentials are never a cache key or a diagnostic. */
class AccountConnection(
    val server: String,
    headers: Map<String, String>,
    val engine: String,
    val model: String,
    val generation: Long,
) {
    val headers = headers.toMap()
    override fun toString(): String = "AccountConnection(generation=$generation, credentials=redacted)"
}

/** Only verified email is active. A restored session is private to verification requests. */
class StoredAccount(val email: String?, val verificationRequired: Boolean, val problem: String?)

interface AccountStorageSubscription { fun cancel() }
interface AccountTokenObserver { fun completed(token: String?, problem: String?) }

/** How a native Cloudflare Access sign-in ended. The token stays in the host's secure storage and never comes here. */
interface AccessSignInObserver {
    /** The token was saved for the server it was asked for; the storage observers have been told. */
    fun signedIn()
    /** Null [problem] means the person closed the page. */
    fun ended(problem: String?)
}

interface AccountStorageObserver {
    /** Synchronous: old routes and observers must be retired before a replacement is usable. */
    fun invalidating()
    /** The native mutation, including a failed mutation, has finished publishing its snapshot. */
    fun changed()
}

/** Native secure storage and Google UI only. All account RPCs use the shared Repo. */
interface AccountStorage {
    /** Background-safe immutable snapshot. Null means secure storage is unavailable. */
    fun connection(verification: Boolean): AccountConnection?
    /** UI-thread state, without the token restored from secure storage. */
    fun account(): StoredAccount
    fun observe(observer: AccountStorageObserver): AccountStorageSubscription
    /** Null means success; errors must be safe messages without credential values. */
    fun acceptSession(session: String, email: String, expectedGeneration: Long): String?
    fun confirmRestoredSession(email: String?, expectedGeneration: Long): String?
    fun forgetSession(): String?
    fun saveServer(server: String, accessId: String, accessSecret: String): String?
    /** Model choices preserve the current secure identity and reject an obsolete page owner. */
    fun saveAnalysis(engine: String, model: String, expectedGeneration: Long): String?
    fun resetCredentials(): String?
    fun retryStorage(): String?
    fun googleAvailable(serverClientId: String): Boolean
    /** Null token plus null problem is native selection cancellation. */
    fun googleToken(serverClientId: String, expectedGeneration: Long, completed: AccountTokenObserver): AccountStorageSubscription
    /**
     * Opens the configured server's Cloudflare Access sign-in in a page of the host's, titled [title] with [hint] under
     * it, and keeps the `CF_Authorization` token it ends with as that server's Access sign-in.
     */
    fun accessSignIn(title: String, hint: String, expectedGeneration: Long, completed: AccessSignInObserver): AccountStorageSubscription {
        completed.ended("Signing in to Access is not available here. Add the service token in Server settings.")
        return object : AccountStorageSubscription { override fun cancel() {} }
    }
}
