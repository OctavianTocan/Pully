package com.octaviantocan.pulls.update

import com.octaviantocan.pulls.data.ServerConfig
import com.octaviantocan.pulls.data.stagingAccessHeaders
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UpdateSourceTest {
    private val staging = "https://pully-staging.octaviantocan.com"
    private val saved = ServerConfig(url = "https://compatibility.example", accessId = "saved-id", accessSecret = "saved-secret", accessToken = "saved-cookie")
    private fun baked(server: String, path: String) = stagingAccessHeaders(server, path, staging, "fixture-id", "fixture-secret")

    @Test fun coldExploreAndGitHubUseBuiltInStagingWithoutReadingLegacyOrWorkspaceState() {
        var savedReads = 0
        // Both entries retain Explore as their entry choice; no NativeWorkspaceBinding exists after process death.
        val config = resolveUpdateConfig(false, { savedReads++; saved }, staging, "staging/", ::baked)
        assertEquals(staging, config?.url)
        assertTrue(config!!.headers().isEmpty())
        assertEquals(0, savedReads)
        assertEquals("https://compatibility.example", saved.url)
        assertEquals("saved-secret", saved.accessSecret)
        assertEquals("saved-cookie", saved.accessToken)
    }

    @Test fun explicitExistingServerRetainsSavedConfigurationEvenWhenStagingIsAvailable() {
        assertEquals(saved, resolveUpdateConfig(true, { saved }, staging, "staging/", ::baked))
        assertEquals(saved, resolveUpdateConfig(true, { saved }, "https://pulls.review", "", { _, _ -> emptyMap() }))
    }

    @Test fun exploreCannotAdoptCustomOrStableUpdateAuthority() {
        assertNull(resolveUpdateConfig(false, { saved }, "https://pulls.review", "", ::baked))
        assertNull(resolveUpdateConfig(false, { saved }, "https://custom.example", "staging/", ::baked))
        assertNull(resolveUpdateConfig(false, { saved }, staging, "staging/", { _, _ -> emptyMap() }))
    }
}
