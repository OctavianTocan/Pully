package com.octaviantocan.pulls.data

/**
 * Native secure storage, isolated from accepted personal/legacy accounts. Unpromoted records are
 * cleanup-only after process restart. The successful accept callback atomically promotes its record.
 */
interface PersonalSessionStaging {
    fun savePending(session: PersonalSession)

    /** Delete only this origin/token's pending record, after its server revocation was confirmed. */
    fun discardPending(session: PersonalSession)
}
