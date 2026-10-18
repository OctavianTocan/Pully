package com.octaviantocan.pulls.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BrowserRepoTest {
    private val repo = BrowserRepository("OctavianTocan", "pulls.review")
    private val chrome = "com.android.chrome"
    private val chromeId = "$chrome:id/url_bar"
    private val address = "https://github.com/OctavianTocan/pulls.review"

    @Test
    fun acceptsCompleteHttpsAndBrowserDisplayAddresses() {
        listOf(
            address,
            "$address/",
            "github.com/OctavianTocan/pulls.review",
            "www.github.com/OctavianTocan/pulls.review",
            "HTTPS://WWW.GITHUB.COM/OctavianTocan/pulls.review",
        ).forEach { assertEquals(repo, BrowserRepo.parseAddress(it), it) }
        assertEquals("OctavianTocan/pulls.review", repo.label)
    }

    @Test
    fun reducesRepositorySubpagesAndQueriesToTheRepository() {
        listOf(
            "/pull/42/files?w=1#diff-a", "/issues/4", "/tree/main", "/blob/main/README.md",
            "/actions/runs/123", "/settings/access", "/wiki", "?tab=readme-ov-file#readme",
            "/compare/main...branch", "/releases/tag/v1.0",
        ).forEach { assertEquals(repo, BrowserRepo.parseAddress(address + it), it) }
    }

    @Test
    fun acceptsRealOwnerAndRepositoryNameShapes() {
        listOf("github/docs", "actions/setup-node", "education/classroom", "a/.github", "a/-repo_2.0", "mona-cat_octo/repo")
            .forEach {
                val (owner, name) = it.split('/')
                assertEquals(BrowserRepository(owner, name), BrowserRepo.parseAddress("https://github.com/$it"))
            }
        assertEquals(BrowserRepository("a".repeat(39), "r".repeat(100)),
            BrowserRepo.parseAddress("https://github.com/${"a".repeat(39)}/${"r".repeat(100)}"))
    }

    @Test
    fun rejectsNonRepositoryGitHubPages() {
        listOf(
            "", "https://github.com", "github.com", "https://github.com/", "https://github.com/octocat",
            "https://github.com/octocat?tab=repositories", "https://github.com/search?q=owner/repo",
            "https://github.com/orgs/octocat/repositories", "https://github.com/users/octocat/contributions",
            "https://github.com/settings/profile", "https://github.com/login/oauth/authorize",
            "https://github.com/marketplace/actions/checkout", "https://github.com/topics/android",
            "https://github.com/features/actions", "https://github.com/sponsors/octocat",
            "https://github.com/notifications/threads", "https://github.com/SEARCH/repositories",
            "https://github.com/solutions/industry/healthcare", "https://github.com/resources/articles/security",
        ).forEach { assertNull(BrowserRepo.parseAddress(it), it) }
    }

    @Test
    fun rejectsLookalikesCredentialsPortsAndOtherSchemes() {
        listOf(
            "https://github.com.evil.test/owner/repo", "https://evilgithub.com/owner/repo",
            "https://github.com@evil.test/owner/repo", "https://evil.test@github.com/owner/repo",
            "https://name:password@github.com/owner/repo", "https://github.com:443/owner/repo",
            "https://github.com:/owner/repo", "https://github.com./owner/repo",
            "https://gist.github.com/owner/repo", "https://api.github.com/owner/repo",
            "https://github.enterprise.test/owner/repo", "http://github.com/owner/repo",
            "javascript:https://github.com/owner/repo", "ftp://github.com/owner/repo",
            "//github.com/owner/repo", "https://www.github.com.evil.test/owner/repo",
            "https://gіthub.com/owner/repo",
        ).forEach { assertNull(BrowserRepo.parseAddress(it), it) }
    }

    @Test
    fun rejectsEncodedAmbiguousMalformedOrEmbeddedAddresses() {
        listOf(
            "Open $address", "$address some words", " $address", "$address\n", "$address\t",
            "https://github.com/owner/repo\u0000", "https://github.com/owner/repo\u202e",
            "https://github.com/owner/repo…", "https://github.com/owner/repo.../…",
            "https://github.com/owner%2frepo/other", "https://%67ithub.com/owner/repo",
            "https://github.com/owner/%72epo", "$address?return=%2Felsewhere",
            "https://github.com\\@evil.test/owner/repo", "https://github.com//owner/repo",
            "https://github.com/owner//repo", "https://github.com/owner/repo//tree/main",
            "https://github.com/owner/../repo", "https://github.com/owner/repo/../other",
            "https://github.com/owner/repo/./file", "https://github.com/owner/repo?bad=<",
        ).forEach { assertNull(BrowserRepo.parseAddress(it), it) }
        assertNull(BrowserRepo.parseAddress(address + "?" + "q".repeat(8192)))
    }

    @Test
    fun rejectsInvalidOwnerAndRepositoryNames() {
        listOf(
            "-owner/repo", "owner-/repo", "owner--name/repo", "owner__name/repo", "owner.name/repo",
            "owner_ab/repo", "owner_123456789/repo",
            "${"a".repeat(40)}/repo", "owner/${"a".repeat(101)}", "owner/.", "owner/..",
            "owner/repo+name", "owner/repo:name", "owner/repo@name", "owner/répo", "owner/",
        ).forEach { assertNull(BrowserRepo.parseAddress("https://github.com/$it"), it) }
    }

    @Test
    fun exposesOnlyExactPackageScopedAddressIds() {
        val adapters = mapOf(
            "com.android.chrome" to "url_bar", "com.chrome.beta" to "url_bar",
            "com.chrome.dev" to "url_bar", "com.chrome.canary" to "url_bar",
            "org.chromium.chrome" to "url_bar", "com.brave.browser" to "url_bar",
            "com.brave.browser_beta" to "url_bar", "com.brave.browser_nightly" to "url_bar",
            "com.microsoft.emmx" to "url_bar", "com.microsoft.emmx.beta" to "url_bar",
            "com.microsoft.emmx.dev" to "url_bar", "com.microsoft.emmx.canary" to "url_bar",
            "com.vivaldi.browser" to "url_bar", "com.vivaldi.browser.snapshot" to "url_bar",
            "org.mozilla.firefox" to "mozac_browser_toolbar_url_view",
            "org.mozilla.firefox_beta" to "mozac_browser_toolbar_url_view",
            "org.mozilla.fenix" to "mozac_browser_toolbar_url_view",
            "com.sec.android.app.sbrowser" to "location_bar_edit_text",
            "com.sec.android.app.sbrowser.beta" to "location_bar_edit_text",
            "com.opera.browser" to "url_field", "com.opera.browser.beta" to "url_field",
        )
        for ((browser, field) in adapters) {
            assertEquals(setOf("$browser:id/$field"), BrowserRepo.addressBarIds(browser))
            assertEquals(repo, BrowserRepo.detect(browser, listOf(BrowserAddressNode("$browser:id/$field", address))))
        }
        assertTrue(BrowserRepo.addressBarIds("com.unknown.browser").isEmpty())
        assertTrue(BrowserRepo.addressBarIds("COM.ANDROID.CHROME").isEmpty())
        assertFalse("com.fake.chrome:id/url_bar" in BrowserRepo.addressBarIds(chrome))
    }

    @Test
    fun neverTreatsPageTextLinksOrOtherAppFieldsAsTheAddress() {
        listOf(
            "com.android.chrome:id/page_title", "com.android.chrome:id/suggestion_url",
            "com.android.chrome:id/bookmark_url", "com.android.chrome:id/url_bar_extra",
            "com.android.chrome:id/find_in_page_text", "com.fake.chrome:id/url_bar", "url_bar", null,
        ).forEach {
            assertNull(BrowserRepo.detect(chrome, listOf(BrowserAddressNode(it, address))), it)
        }
        assertNull(BrowserRepo.detect("com.unknown.browser", listOf(BrowserAddressNode(chromeId, address))))
        assertNull(BrowserRepo.detect(chrome, emptyList()))
        assertNull(BrowserRepo.detect(chrome, listOf(BrowserAddressNode(chromeId, null))))
        assertNull(BrowserRepo.detect(chrome, listOf(BrowserAddressNode(chromeId, ""))))
        assertNull(BrowserRepo.detect(chrome, listOf(BrowserAddressNode(chromeId, "GitHub"))))
    }

    @Test
    fun ignoresInvisibleAndWebContentNodesButRejectsFocusedFields() {
        assertNull(BrowserRepo.detect(chrome, listOf(BrowserAddressNode(chromeId, address, visible = false))))
        assertNull(BrowserRepo.detect(chrome, listOf(BrowserAddressNode(chromeId, address, inWebContent = true))))
        assertNull(BrowserRepo.detect(chrome, listOf(BrowserAddressNode(chromeId, address, focused = true))))
        assertNull(BrowserRepo.detect(chrome, listOf(BrowserAddressNode(chromeId, address, focused = true, editable = true))))
        assertEquals(repo, BrowserRepo.detect(chrome, listOf(BrowserAddressNode(chromeId, address, editable = true))))
        assertEquals(repo, BrowserRepo.detect(chrome, listOf(
            BrowserAddressNode(chromeId, "https://github.com/other/repo", visible = false),
            BrowserAddressNode(chromeId, "https://github.com/other/repo", inWebContent = true),
            BrowserAddressNode(chromeId, address),
        )))
    }

    @Test
    fun requiresAllVisibleAddressCandidatesToAgree() {
        assertEquals(repo, BrowserRepo.detect(chrome, listOf(
            BrowserAddressNode(chromeId, address),
            BrowserAddressNode(chromeId, "github.com/octaviantocan/Pulls.Review/issues/4"),
        )))
        listOf("https://github.com/other/repo", "https://example.com", "", "GitHub", null)
            .forEach { conflicting ->
                val good = BrowserAddressNode(chromeId, address)
                val bad = BrowserAddressNode(chromeId, conflicting)
                assertNull(BrowserRepo.detect(chrome, listOf(good, bad)), conflicting)
                assertNull(BrowserRepo.detect(chrome, listOf(bad, good)), conflicting)
            }
        assertNull(BrowserRepo.detect(chrome, listOf(
            BrowserAddressNode(chromeId, address),
            BrowserAddressNode(chromeId, address, focused = true),
        )))
    }
}
