package com.octaviantocan.pulls.data

import kotlin.test.*

class StagingAccessTest {
    private val staging = "https://pully-staging.octaviantocan.com"
    private fun headers(server: String = staging, path: String, default: String = staging) =
        stagingAccessHeaders(server, path, default, "fixture-id", "fixture-secret")

    @Test fun tokenIsOnlyForBuiltInStagingWorkspaceAndDownloads() {
        assertEquals("fixture-secret", headers(path = "/workspace/api/rpc")["CF-Access-Client-Secret"])
        assertEquals(2, headers(path = "/download/staging/latest.json").size)
        assertEquals(2, headers(path = "/download/staging/pully.apk").size)
        for (path in listOf("/api/rpc", "/health", "/api/personal/session", "/api/personal/read/account/pulls", "/download/latest.json", "/workspace/../api/rpc", "/workspace/%2e%2e/api/rpc"))
            assertTrue(headers(path = path).isEmpty(), path)
        for (server in listOf("https://custom.example", "http://pully-staging.octaviantocan.com", "$staging.evil.example", "$staging:443", "$staging/custom"))
            assertTrue(headers(server, "/workspace/api/rpc").isEmpty(), server)
        assertTrue(headers(path = "/workspace/api/rpc", default = "https://pulls.review").isEmpty())
        assertTrue(stagingAccessHeaders(staging, "/workspace/api/rpc", staging, "", "fixture").isEmpty())
    }
}
