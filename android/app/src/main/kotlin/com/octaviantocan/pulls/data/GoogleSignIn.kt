package com.octaviantocan.pulls.data

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.NoCredentialException
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential

/**
 * Google sign-in through Credential Manager, Google's own library for it: Android's account sheet picks the Google
 * account, and Google answers an ID token for the server's web client ID, which the server checks and trades for the
 * account's session. The phone never sees a password.
 */
object GoogleSignIn {
    /**
     * The ID token for the Google account picked, or null when the sheet was closed without one.
     * @param clientId The server's web client ID, which the token is made out to.
     */
    suspend fun idToken(context: Context, clientId: String): String? {
        // The account sheet opens over the app's own window, so it needs the activity rather than a dialog's context.
        val host = context.activity() ?: context
        val request = GetCredentialRequest.Builder()
            .addCredentialOption(GetSignInWithGoogleOption.Builder(clientId).build())
            .build()
        val credential = try {
            CredentialManager.create(host).getCredential(host, request).credential
        } catch (_: GetCredentialCancellationException) {
            return null
        } catch (_: NoCredentialException) {
            throw IllegalStateException("There is no Google account on this phone. Add one in Android Settings, Accounts.")
        } catch (e: GetCredentialException) {
            throw IllegalStateException(problem(e.message))
        }
        if (credential !is CustomCredential || credential.type != GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL)
            throw IllegalStateException("Google answered something other than a sign-in.")
        return GoogleIdTokenCredential.createFrom(credential.data).idToken
    }

    /** What a Credential Manager failure means, in words: Google's code 28444 is a Cloud project that does not know this app. */
    internal fun problem(message: String?): String = when {
        message == null -> "Google could not sign you in."
        "28444" in message || "Developer console" in message ->
            "Google sign-in is not set up for this app yet: its Android client in Google Cloud needs this app's package and signing key."
        else -> "Google could not sign you in: $message"
    }

    private tailrec fun Context.activity(): Activity? = when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.activity()
        else -> null
    }
}
