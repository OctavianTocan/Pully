package com.octaviantocan.pulls.update

import com.octaviantocan.pulls.BuildConfig
import com.octaviantocan.pulls.data.ServerStore
import com.octaviantocan.pulls.data.stagingAccessHeaders
import java.net.URL
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class UpdateAccessTest {
    private val staging = "https://pully-staging.octaviantocan.com"
    private fun baked(server: String, path: String) = stagingAccessHeaders(server, path, staging, "fixture-id", "fixture-secret")

    @Test fun bakedUpdateHeadersRequireTheExactBuiltInOriginAndDownloadPathAtEachHop() {
        assertEquals("fixture-secret", updateHeadersForUrl(staging, URL("$staging/download/staging/pulls.apk"), emptyMap(), ::baked)["CF-Access-Client-Secret"])
        for (target in listOf(
            "$staging/api/personal/session", "$staging/api/rpc", "$staging/workspace/api/rpc",
            "$staging/download/latest.json", "$staging/download/staging/../latest.json", "$staging/download/staging/%2e%2e/latest.json",
            "http://pully-staging.octaviantocan.com/download/staging/pulls.apk",
            "$staging:443/download/staging/pulls.apk", "https://custom.example/download/staging/pulls.apk",
        )) assertTrue(updateHeadersForUrl(staging, URL(target), emptyMap(), ::baked).isEmpty(), target)
    }

    @Test fun generatedBuildCredentialsReachOnlyTheBuiltInWorkspaceAndStagingDownloads() {
        val configured = BuildConfig.UPDATE_CHANNEL == "staging/" && BuildConfig.DEFAULT_SERVER == staging
        for (path in listOf("/workspace/api/rpc", "/workspace/api/events", "/download/staging/latest.json")) {
            val headers = ServerStore.bakedHeaders(staging, path)
            if (configured) {
                assertTrue(BuildConfig.STAGING_ACCESS_CLIENT_ID.isNotBlank())
                assertTrue(BuildConfig.STAGING_ACCESS_CLIENT_SECRET.isNotBlank())
                assertTrue(headers["CF-Access-Client-Id"] == BuildConfig.STAGING_ACCESS_CLIENT_ID)
                assertTrue(headers["CF-Access-Client-Secret"] == BuildConfig.STAGING_ACCESS_CLIENT_SECRET)
            } else assertTrue(headers.isEmpty())
        }
        for (path in listOf("/api/personal/v1/session", "/api/rpc", "/health", "/download/latest.json"))
            assertTrue(ServerStore.bakedHeaders(staging, path).isEmpty())
        assertTrue(ServerStore.bakedHeaders("https://custom.example", "/workspace/api/rpc").isEmpty())
    }

    @Test fun savedCompatibilityHeadersKeepTheirSameOriginBehavior() {
        val saved = mapOf("CF-Access-Client-Id" to "saved-id", "CF-Access-Client-Secret" to "saved-secret")
        assertEquals(saved, updateHeadersForUrl("https://custom.example", URL("https://custom.example/api/rpc"), saved, ::baked))
        assertEquals(saved, updateHeadersForUrl(staging, URL("$staging/api/rpc"), saved, ::baked))
    }
}
