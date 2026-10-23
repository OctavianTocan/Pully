package com.octaviantocan.pulls.ui.home

import kotlin.test.Test
import kotlin.test.assertEquals

class RelativeTimeNativeTest {
    @Test
    fun namedRelativeDatesUseTheAppLocaleAndSharedUnits() {
        val now = 172_800_000L
        assertEquals("now", timeAgo(now - 30_000, now, "en_US"))
        assertEquals("yesterday", timeAgo(now - 86_400_000, now, "en_US"))
        assertEquals("tomorrow", timeAgo(now + 86_400_000, now, "en_US"))
        assertEquals("ieri", timeAgo(now - 86_400_000, now, "it_IT"))
    }
}
