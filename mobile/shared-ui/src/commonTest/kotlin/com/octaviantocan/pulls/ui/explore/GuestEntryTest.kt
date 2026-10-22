package com.octaviantocan.pulls.ui.explore

import com.octaviantocan.pulls.core.OpenTarget
import com.octaviantocan.pulls.ui.Screen
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GuestEntryTest {
    @Test
    fun freshOrRestoredPreferencesWithoutAConnectionOpenPublicExplore() {
        for (choice in listOf(null, NativeEntryMode.ExistingServer, NativeEntryMode.Explore)) {
            val entry = NativeEntryState(verifiedExistingConnection = false, savedChoice = choice)
            assertEquals(NativeEntryMode.Explore, entry.mode)
            assertTrue(entry.guestSource()!!.current)
            // Existing account remains an explicit action, rather than a hidden network probe on first launch.
            entry.select(NativeEntryMode.ExistingServer)
            assertEquals(NativeEntryMode.ExistingServer, entry.mode)
            assertNull(entry.guestSource())
        }
    }

    @Test
    fun returningConnectionsKeepTheirWorkspaceUnlessPublicBrowsingWasChosen() {
        assertEquals(NativeEntryMode.ExistingServer, NativeEntryState(true).mode)
        assertEquals(NativeEntryMode.ExistingServer, NativeEntryState(true, NativeEntryMode.ExistingServer).mode)
        assertEquals(NativeEntryMode.Explore, NativeEntryState(true, NativeEntryMode.Explore).mode)
    }

    @Test
    fun switchingAwayAndBackNeverRevivesAnOpeningGuestAuthority() {
        val entry = NativeEntryState(false)
        val opening = entry.guestSource()!!
        entry.select(NativeEntryMode.Explore)
        assertTrue(opening.current)
        entry.select(NativeEntryMode.ExistingServer)
        assertFalse(opening.current)
        entry.select(NativeEntryMode.Explore)
        assertFalse(opening.current)
        assertTrue(entry.guestSource()!!.current)
    }

    @Test
    fun personalAccountChangesRetireThePublicSourceWithoutEnteringThePrivateWorkspace() {
        val entry = NativeEntryState(false)
        val opening = entry.guestSource()!!
        var retired = 0
        opening.onRetired { retired++ }
        entry.renewExplore()
        assertEquals(NativeEntryMode.Explore, entry.mode)
        assertFalse(opening.current)
        assertEquals(1, retired)
        val replacement = entry.guestSource()!!
        assertTrue(replacement.current)
        entry.select(NativeEntryMode.ExistingServer)
        val privateGeneration = entry.generation
        entry.renewExplore()
        assertEquals(NativeEntryMode.ExistingServer, entry.mode)
        assertEquals(privateGeneration, entry.generation)
        assertFalse(replacement.current)
    }

    @Test
    fun publicLinkInvitationsNeverConvertPrivateTargetsIntoGitHubRequests() {
        assertNull(guestGitHubUrl(Screen.Review(OpenTarget.Posted("private-diff"))))
        assertNull(guestGitHubUrl(Screen.Review(OpenTarget.Pull("owner", "repo", 1))))
        assertNull(guestGitHubUrl(Screen.Review(OpenTarget.Branch("private-branch"))))
        assertNull(guestGitHubUrl(Screen.Repo("user@evil.invalid", "repo")))
        assertNull(guestGitHubUrl(Screen.Repo("..", "repo")))
        assertNull(guestGitHubUrl(Screen.Review(OpenTarget.Pull("owner", "repo", -1, "github.com"))))
        assertEquals("https://github.com/owner/repo/pull/42", guestGitHubUrl(Screen.Review(OpenTarget.Pull("owner", "repo", 42, "github.com"))))
        assertEquals("https://github.com/owner/repo", guestGitHubUrl(Screen.Repo("owner", "repo")))
    }
}
