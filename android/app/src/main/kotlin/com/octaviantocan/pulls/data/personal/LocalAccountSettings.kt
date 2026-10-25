package com.octaviantocan.pulls.data.personal

import com.octaviantocan.pulls.data.PersonalAuthOrigin
import com.octaviantocan.pulls.data.PersonalAuthority
import com.octaviantocan.pulls.data.PersonalSessionVault
import com.octaviantocan.pulls.data.sourceStorageNamespace

/** Opaque local preference selection only, including cold update workers; no verification or dispatch grant. */
internal fun savedAccountSettingsNamespace(origin: PersonalAuthOrigin, vault: PersonalSessionVault): String? = try {
    vault.verifyStorage()
    vault.accepted(origin)?.takeIf { it.authority == PersonalAuthority.PrivateRepositories }?.let {
        sourceStorageNamespace("${origin.base}/workspace", it.accountId, true)
    }
} catch (_: Exception) { null }
