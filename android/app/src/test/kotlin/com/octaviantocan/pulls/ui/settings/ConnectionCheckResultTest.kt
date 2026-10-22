package com.octaviantocan.pulls.ui.settings

import com.octaviantocan.pulls.data.Account
import com.octaviantocan.pulls.data.ConnectionProblem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ConnectionCheckResultTest {
    @Test
    fun aSaveProbeReplacedByAutomaticDiscoveryCannotDisplayUnreachable() {
        val manual = Account.beginConnectionCheck()
        val automatic = Account.beginConnectionCheck()
        try {
            assertNull(connectionCheckResult(manual).messageKey)
            assertNull(connectionCheckResult(manual, ConnectionProblem.Unreachable).messageKey)
            Account.connectionFailed(manual, ConnectionProblem.Unreachable)
            Account.finishConnectionCheck(manual)
            assertTrue(Account.checkingConnection)
            assertNull(Account.connectionProblem)
            assertEquals("connection.saved", connectionCheckResult(automatic).messageKey)
        } finally { Account.finishConnectionCheck(automatic) }
    }

    @Test
    fun failureFeedbackKeepsTheCompletedAttemptsOwnCategory() {
        val revision = Account.beginConnectionCheck()
        try {
            val completed = connectionCheckResult(revision, ConnectionProblem.WebPage)
            Account.connectionFailed(revision, ConnectionProblem.Unreachable)
            assertEquals("connection.problem.WebPage", completed.messageKey)
        } finally {
            val clean = Account.beginConnectionCheck()
            Account.finishConnectionCheck(clean)
        }
    }
}
