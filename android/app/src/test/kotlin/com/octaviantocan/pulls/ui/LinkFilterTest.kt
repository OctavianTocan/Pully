package com.octaviantocan.pulls.ui

import com.octaviantocan.pulls.core.LinkPlace
import com.octaviantocan.pulls.core.LinkPlaces
import com.octaviantocan.pulls.core.OpenTarget
import com.octaviantocan.pulls.ui.home.HomeLink
import com.octaviantocan.pulls.ui.home.HomeLinks
import org.w3c.dom.Element
import java.io.File
import java.net.URI
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The manifest's github.com link filter, run through Android's own matching, and the share texts Pulls is handed. */
class LinkFilterTest {
    private val android = "http://schemas.android.com/apk/res/android"

    /** Every pathPattern of the VIEW filter that names github.com. */
    private val githubPatterns: List<String> by lazy {
        val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        val doc = factory.newDocumentBuilder().parse(File("src/main/AndroidManifest.xml"))
        val filters = doc.getElementsByTagName("intent-filter")
        (0 until filters.length).map { filters.item(it) as Element }.flatMap { filter ->
            val data = filter.getElementsByTagName("data").let { list -> (0 until list.length).map { list.item(it) as Element } }
            if (data.none { it.getAttributeNS(android, "host") == "github.com" }) emptyList()
            else data.map { it.getAttributeNS(android, "pathPattern") }.filter { it.isNotEmpty() }
        }
    }

    private fun opensInPulls(url: String): Boolean {
        val path = URI(url).path
        return githubPatterns.any { matchGlob(it, path) }
    }

    @Test
    fun tappedPullRequestCommitAndCompareLinksReachPulls() {
        assertTrue(githubPatterns.isNotEmpty())
        assertTrue(opensInPulls("https://github.com/OctavianTocan/pulls.review/pull/49"))
        assertTrue(opensInPulls("https://github.com/OctavianTocan/pulls.review/pull/49/files"))
        assertTrue(opensInPulls("https://github.com/a/b/pull/7#discussion_r123"))
        assertTrue(opensInPulls("https://github.com/a/b/commit/abc1234"))
        assertTrue(opensInPulls("https://github.com/a/b/compare/main...feat/x"))
    }

    @Test
    fun everyOtherGithubPageStaysInTheBrowser() {
        assertFalse(opensInPulls("https://github.com/a/b"))
        assertFalse(opensInPulls("https://github.com/a/b/pulls"))
        assertFalse(opensInPulls("https://github.com/a/b/issues/7"))
        assertFalse(opensInPulls("https://github.com/a/b/blob/main/README.md"))
        assertFalse(opensInPulls("https://github.com/notifications"))
    }

    @Test
    fun theOldPatternNeverMatchedARealLink() {
        // The filter shipped until 0.38.0: ".*" stops at the owner's "/", so "/pull/" met the repository's name instead.
        assertFalse(matchGlob("/..*/pull/.*", "/a/b/pull/1"))
    }

    @Test
    fun aSharedTextOnlyHasToContainTheLink() {
        val pull = HomeLink.Target(OpenTarget.Pull("OctavianTocan", "pulls.review", 49, "github.com"))
        assertEquals(pull, HomeLinks.parseShared("https://github.com/OctavianTocan/pulls.review/pull/49"))
        assertEquals(pull, HomeLinks.parseShared("Have a look: https://github.com/OctavianTocan/pulls.review/pull/49. Thanks"))
        assertEquals(pull, HomeLinks.parseShared("Usage analytics · Pull Request #49 · OctavianTocan/pulls.review\nhttps://github.com/OctavianTocan/pulls.review/pull/49"))
        assertEquals(pull.copy(place = LinkPlace.Commits()), HomeLinks.parseShared("https://www.github.com/OctavianTocan/pulls.review/pull/49/commits"))
        assertEquals(HomeLink.Target(OpenTarget.Commit("a", "b", "abc1234")), HomeLinks.parseShared("https://github.com/a/b/commit/abc1234"))
        assertEquals(HomeLink.Target(OpenTarget.Pull("a", "b", 3)), HomeLinks.parseShared("a/b#3"))
        assertEquals(HomeLink.Repo("a", "b"), HomeLinks.parseShared("https://github.com/a/b"))
        assertEquals(HomeLink.Repo("a", "b"), HomeLinks.parseShared("https://github.com/a/b/pulls"))
    }

    @Test
    fun aSharedIssueOrFileIsNotARepositoryLink() {
        assertNull(HomeLinks.parseShared("https://github.com/a/b/issues/7"))
        assertNull(HomeLinks.parseShared("https://github.com/a/b/blob/main/README.md"))
        // An issue named first still lets the pull request after it open.
        assertEquals(
            HomeLink.Target(OpenTarget.Pull("a", "b", 8, "github.com")),
            HomeLinks.parseShared("Fixes https://github.com/a/b/issues/7 in https://github.com/a/b/pull/8"),
        )
        assertNull(HomeLinks.parseShared("hello"))
    }

    @Test
    fun aLinkCarriesTheThingItPointsAt() {
        val pull = OpenTarget.Pull("OctavianTocan", "pulls.review", 49, "github.com")
        val base = "https://github.com/OctavianTocan/pulls.review/pull/49"
        assertEquals(HomeLink.Target(pull, LinkPlace.Comment(3412345678)), HomeLinks.parseShared("$base#issuecomment-3412345678"))
        assertEquals(HomeLink.Target(pull, LinkPlace.Review(77)), HomeLinks.parseShared("Look: $base#pullrequestreview-77"))
        assertEquals(HomeLink.Target(pull, LinkPlace.CodeComment(88)), HomeLinks.parseShared("$base/files#r88"))
        assertEquals(HomeLink.Target(pull, LinkPlace.Checks(99)), HomeLinks.parseShared("$base/checks?check_run_id=99"))
        val readme = LinkPlaces.fileAnchor("README.md")
        assertEquals(
            HomeLink.Target(OpenTarget.Commit("a", "b", "abc1234"), LinkPlace.Files(readme, 3)),
            HomeLinks.parseShared("https://github.com/a/b/commit/abc1234#diff-${readme}R3"),
        )
    }

    @Test
    fun linksInsidePullyOpenInPullyOnlyWhenATapElsewhereWould() {
        assertTrue(HomeLinks.opensInPully("https://github.com/a/b/pull/7#issuecomment-1"))
        assertTrue(HomeLinks.opensInPully("https://github.com/a/b/commit/abc1234"))
        assertTrue(HomeLinks.opensInPully("https://www.github.com/a/b/compare/main...dev"))
        assertFalse(HomeLinks.opensInPully("https://github.com/a/b/issues/7"))
        assertFalse(HomeLinks.opensInPully("https://github.com/a/b/blob/main/README.md"))
        assertFalse(HomeLinks.opensInPully("https://github.com/a/b"))
        assertFalse(HomeLinks.opensInPully("https://docs.example.com/a/b/3"))
        assertFalse(HomeLinks.opensInPully("a/b#3"))
        // Whatever Pully opens from inside, a tap in another app hands it to Pully too.
        listOf("https://github.com/a/b/pull/7#issuecomment-1", "https://github.com/a/b/commit/abc1234", "https://www.github.com/a/b/compare/main...dev")
            .forEach { assertTrue(opensInPulls(it), it) }
    }

    @Test
    fun onGithubNeverGoesBackToPully() {
        val own = "com.octaviantocan.pulls"
        assertEquals(
            listOf(OutsideLinks.GITHUB_APP, "com.android.chrome", "org.mozilla.firefox"),
            OutsideLinks.order(listOf(own, OutsideLinks.GITHUB_APP, "com.android.chrome"), "com.android.chrome", listOf("org.mozilla.firefox", "com.android.chrome"), own),
        )
        // No GitHub app, and Android's chooser as the default: the browsers, never Pully.
        assertEquals(listOf("org.mozilla.firefox"), OutsideLinks.order(listOf(own, "android"), "android", listOf("org.mozilla.firefox"), own))
        assertEquals(emptyList(), OutsideLinks.order(listOf(own), null, emptyList(), own))
    }

    /** `PatternMatcher.matchGlobPattern` from AOSP: `.*` takes everything up to the next pattern character and never gives it back. */
    private fun matchGlob(pattern: String, match: String): Boolean {
        val np = pattern.length
        if (np <= 0) return match.isEmpty()
        val nm = match.length
        var ip = 0
        var im = 0
        var nextChar = pattern[0]
        while (ip < np && im < nm) {
            var c = nextChar
            ip++
            nextChar = if (ip < np) pattern[ip] else 0.toChar()
            val escaped = c == '\\'
            if (escaped) {
                c = nextChar
                ip++
                nextChar = if (ip < np) pattern[ip] else 0.toChar()
            }
            if (nextChar == '*') {
                if (!escaped && c == '.') {
                    if (ip >= np - 1) return true
                    ip++
                    nextChar = pattern[ip]
                    if (nextChar == '\\') {
                        ip++
                        nextChar = if (ip < np) pattern[ip] else 0.toChar()
                    }
                    do {
                        if (match[im] == nextChar) break
                        im++
                    } while (im < nm)
                    if (im == nm) return false
                    ip++
                    nextChar = if (ip < np) pattern[ip] else 0.toChar()
                    im++
                } else {
                    do {
                        if (match[im] != c) break
                        im++
                    } while (im < nm)
                    ip++
                    nextChar = if (ip < np) pattern[ip] else 0.toChar()
                }
            } else {
                if (c != '.' && match[im] != c) return false
                im++
            }
        }
        if (ip >= np && im >= nm) return true
        return ip == np - 2 && pattern[ip] == '.' && pattern[ip + 1] == '*'
    }
}
