package com.octaviantocan.pulls.ui

import com.octaviantocan.pulls.ui.settings.AccountConnection
import com.octaviantocan.pulls.ui.settings.StoredAccount

/** A retained email alone never grants private cache, settings or analytics ownership. */
internal fun eligibleAccountOwner(status: Boolean?, account: StoredAccount, connection: AccountConnection?): String? {
    if (status != true || account.verificationRequired || account.problem != null) return null
    val email = account.email?.takeIf { it.isNotBlank() } ?: return null
    val session = connection?.headers?.any { (name, value) ->
        name.equals("X-Pully-Session", ignoreCase = true) && value.isNotBlank()
    } == true
    return email.takeIf { session }
}
