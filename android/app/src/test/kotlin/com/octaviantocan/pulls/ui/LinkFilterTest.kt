package com.octaviantocan.pulls.ui

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
        assertEquals(pull, HomeLinks.parse("https://github.com/OctavianTocan/pulls.review/pull/49"))
        assertEquals(pull, HomeLinks.parse("Have a look: https://github.com/OctavianTocan/pulls.review/pull/49 thanks"))
        assertEquals(pull, HomeLinks.parse("Usage analytics · Pull Request #49 · OctavianTocan/pulls.review\nhttps://github.com/OctavianTocan/pulls.review/pull/49"))
        assertEquals(pull, HomeLinks.parse("https://www.github.com/OctavianTocan/pulls.review/pull/49/commits"))
        assertEquals(HomeLink.Target(OpenTarget.Pull("a", "b", 3)), HomeLinks.parse("a/b#3"))
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
