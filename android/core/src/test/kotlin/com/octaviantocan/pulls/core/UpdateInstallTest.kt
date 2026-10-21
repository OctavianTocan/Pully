package com.octaviantocan.pulls.core

import kotlin.test.Test
import kotlin.test.assertEquals

class UpdateInstallTest {
    private val attempt = UpdateInstall(sessionId = 42, versionCode = 72, startedAt = 1_000)

    @Test
    fun onlyTheInstalledVersionSettlesASuccessEvenAfterProcessOrSessionDisappears() {
        assertEquals(InstallRecovery.Installed, recoverUpdateInstall(attempt, 72, false, false, 0))
        assertEquals(InstallRecovery.Installed, recoverUpdateInstall(attempt, 73, true, true, Long.MAX_VALUE))
        assertEquals(InstallRecovery.Waiting, recoverUpdateInstall(attempt, 71, true, true, 1_001))
    }

    @Test
    fun missingSessionAndLostProcessOfferRetryInsteadOfPretendingToInstall() {
        assertEquals(InstallRecovery.Interrupted, recoverUpdateInstall(attempt, 71, false, true, 1_001))
        assertEquals(InstallRecovery.Interrupted, recoverUpdateInstall(attempt, 71, true, false, 1_001))
    }

    @Test
    fun lostCallbacksAndClockChangesCannotLeaveTheAttemptBusyForever() {
        assertEquals(InstallRecovery.Waiting, recoverUpdateInstall(attempt, 71, true, true, 1_000 + UPDATE_INSTALL_TIMEOUT_MS - 1))
        assertEquals(InstallRecovery.TimedOut, recoverUpdateInstall(attempt, 71, true, true, 1_000 + UPDATE_INSTALL_TIMEOUT_MS))
        assertEquals(InstallRecovery.TimedOut, recoverUpdateInstall(attempt, 71, true, true, 999))
    }
}
