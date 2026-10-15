package com.octaviantocan.pulls.core

import kotlin.test.Test
import kotlin.test.assertEquals

class PastVersionsTest {
    private fun entry(name: String, code: Int, pkg: String = "com.octaviantocan.pulls.v${name.replace('.', '_')}") =
        """{"versionName":"$name","versionCode":$code,"packageName":"$pkg","sha256":"ab","date":"2026-10-15T09:00:00Z","extra":1}"""

    @Test
    fun `lists past versions oldest first and drops the current one and anything newer`() {
        val text = """{"versions":[${entry("0.20.0", 34)},${entry("0.9.0", 23)},${entry("0.35.0", 49)},${entry("0.36.0", 50)}]}"""
        assertEquals(listOf("0.9.0", "0.20.0"), parsePastVersions(text, currentCode = 49).map { it.versionName })
    }

    @Test
    fun `refuses a package that is not a past version of Pulls`() {
        val text = """{"versions":[${entry("0.20.0", 34, pkg = "com.example.other")},${entry("0.9.0", 23)}]}"""
        assertEquals(listOf("com.octaviantocan.pulls.v0_9_0"), parsePastVersions(text, currentCode = 49).map { it.packageName })
    }

    @Test
    fun `an empty index has no versions`() {
        assertEquals(emptyList(), parsePastVersions("{}", currentCode = 49))
    }
}
