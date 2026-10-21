package com.octaviantocan.pulls.core

import kotlinx.serialization.Serializable

@Serializable
data class UpdateInstall(val sessionId: Int, val versionCode: Int, val startedAt: Long)

enum class InstallRecovery { Installed, Waiting, Interrupted, TimedOut }

/** Success requires the installed package version, never merely opening or returning from the installer. */
fun recoverUpdateInstall(
    attempt: UpdateInstall,
    installedVersionCode: Int,
    sessionExists: Boolean,
    ownedByProcess: Boolean,
    now: Long,
): InstallRecovery = when {
    installedVersionCode >= attempt.versionCode -> InstallRecovery.Installed
    !sessionExists || !ownedByProcess -> InstallRecovery.Interrupted
    now < attempt.startedAt || now - attempt.startedAt >= UPDATE_INSTALL_TIMEOUT_MS -> InstallRecovery.TimedOut
    else -> InstallRecovery.Waiting
}

const val UPDATE_INSTALL_TIMEOUT_MS = 5 * 60_000L
