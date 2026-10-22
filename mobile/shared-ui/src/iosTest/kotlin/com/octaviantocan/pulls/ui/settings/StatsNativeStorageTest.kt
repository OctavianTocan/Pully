package com.octaviantocan.pulls.ui.settings

import com.octaviantocan.pulls.data.Repo
import com.octaviantocan.pulls.data.RepoConfiguration
import com.octaviantocan.pulls.data.RpcResponse
import com.octaviantocan.pulls.data.RpcTransport
import com.octaviantocan.pulls.data.sourceStorageNamespace
import com.octaviantocan.pulls.settings.UserDefaultsSettings
import kotlinx.coroutines.test.runTest
import platform.Foundation.NSUserDefaults
import platform.Foundation.NSUUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class StatsNativeStorageTest {
    @Test
    fun actualPreferencesRestoreOnlyTheSameOpaqueAccountAndAccessNamespace() = runTest {
        val suite = "PullyStatsTests.${NSUUID().UUIDString}"
        val defaults = NSUserDefaults(suiteName = suite)
        try {
            val repo = Repo({ RepoConfiguration("https://stats.example") }, RpcTransport {
                RpcResponse(200, """{"result":{"totals":{"events":1,"sessions":12}}}""")
            })
            fun service(email: String, access: String): SourceStatsServices {
                val namespace = requireNotNull(sourceStorageNamespace("https://stats.example", email, true, mapOf("CF-Access-Client-Secret" to access)))
                return SourceStatsServices(repo, UserDefaultsSettings(defaults, "pully-stats.$namespace"), { true }, { true })
            }
            service("first@example.com", "fixture-access").fetch(7)
            assertEquals(12L, service("first@example.com", "fixture-access").cached(7)?.totals?.sessions)
            assertNull(service("replacement@example.com", "fixture-access").cached(7))
            assertNull(service("first@example.com", "replacement-access").cached(7))
        } finally { defaults.removePersistentDomainForName(suite) }
    }
}
