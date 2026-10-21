package com.octaviantocan.pulls.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PrLinksTest {
    @Test
    fun parsesGithubPullUrls() {
        val target = PrLinks.parse("https://github.com/antfu/pulls.review/pull/62/files?w=1")
        assertEquals(OpenTarget.Pull("antfu", "pulls.review", 62, "github.com"), target)
        assertEquals("antfu/pulls.review/62", target?.serverTarget)
    }

    @Test
    fun parsesPostedDiffLinksOnAnyServer() {
        val target = PrLinks.parse("https://prs.octaviantocan.com/-/k3T9xQ2mLp0a")
        assertEquals(OpenTarget.Posted("k3T9xQ2mLp0a"), target)
        assertEquals("-/k3T9xQ2mLp0a", target?.serverTarget)
        assertEquals(OpenTarget.Posted("k3T9xQ2mLp0a"), PrLinks.parse("Here it is: http://localhost:8792/-/k3T9xQ2mLp0a?x=1"))
        assertEquals(OpenTarget.Posted("k3T9xQ2mLp0a"), PrLinks.parse("prs.example.com/-/k3T9xQ2mLp0a"))
        assertNull(PrLinks.parse("/-/ab"))
    }

    @Test
    fun parsesServerAndShortForms() {
        assertEquals(
            OpenTarget.Pull("antfu", "pulls.review", 62),
            PrLinks.parse("https://prs.octaviantocan.com/antfu/pulls.review/62"),
        )
        assertEquals(
            OpenTarget.Pull("antfu", "pulls.review", 62),
            PrLinks.parse("https://prs.octaviantocan.com/gh/antfu/pulls.review/62"),
        )
        assertEquals(OpenTarget.Pull("antfu", "pulls.review", 62), PrLinks.parse("antfu/pulls.review#62"))
        assertEquals(OpenTarget.Pull("antfu", "pulls.review", 62), PrLinks.parse("antfu/pulls.review/62"))
    }

    @Test
    fun parsesBranchesAndRejectsNoise() {
        assertEquals(OpenTarget.Branch("feat/android-app"), PrLinks.parse("https://prs.octaviantocan.com/branch/feat/android-app"))
        assertNull(PrLinks.parse("not a link"))
        assertNull(PrLinks.parse("diff --git a/a.ts b/a.ts"))
        assertNull(PrLinks.parse(""))
    }

    @Test
    fun parsesCompareAndCommit() {
        assertEquals(
            OpenTarget.Compare("antfu", "pulls.review", "main", "feat/android-app"),
            PrLinks.parse("https://github.com/antfu/pulls.review/compare/main...feat/android-app"),
        )
        assertEquals(
            OpenTarget.Commit("antfu", "pulls.review", "bfa9679"),
            PrLinks.parse("https://github.com/antfu/pulls.review/commit/bfa9679"),
        )
    }
}
