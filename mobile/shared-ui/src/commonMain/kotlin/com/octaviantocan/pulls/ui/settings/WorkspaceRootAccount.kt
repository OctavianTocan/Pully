package com.octaviantocan.pulls.ui.settings

import com.octaviantocan.pulls.data.Repo
import kotlinx.coroutines.CancellationException

/**
 * One verified GitHub workspace as the root's account, as Android's GuestPullsApp mounts it
 * (`github-one-login-full-workspace`): ready under its login for its whole life, one source, and no server sign-in,
 * Access page or saved server credentials of its own. Its owner retiring ends the mount instead of a new source.
 */
class WorkspaceRootAccount(override val repo: Repo, login: String) : RootAccount {
    override val sourceEpoch = 0L
    override val state = AccountState(AccountPhase.Ready, login)
    override fun refresh() {}
    override fun retryStorage() {}
    override fun signInServices(): SignInServices = throw IllegalStateException("A GitHub workspace has no server sign-in.")
    override suspend fun accessSignIn(title: String, hint: String) = false
    override fun resetCredentials(expectedEpoch: Long) = throw CancellationException("A GitHub workspace has no saved server credentials.")
}
