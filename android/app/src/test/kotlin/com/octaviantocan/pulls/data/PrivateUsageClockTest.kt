package com.octaviantocan.pulls.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PrivateUsageClockTest {
    @Test
    fun guestTimeIsExcludedFromPrivateScreenAndForegroundDurations() {
        val clock = PrivateUsageClock(true)
        clock.foreground(0)
        clock.show("home", 0)
        assertEquals(listOf("screen.time" to mapOf("screen" to "home", "seconds" to 1.0)), clock.setPrivateConnection(false, 1_000))
        assertTrue(clock.show("review", 20_000).isEmpty())
        clock.setPrivateConnection(true, 100_000)
        assertEquals("screen.time" to mapOf("screen" to "home", "seconds" to 2.0), clock.show("review", 102_000).first())
        val events = clock.background(105_000)
        assertEquals("screen.time" to mapOf("screen" to "review", "seconds" to 3.0), events.first())
        assertEquals("app.background" to mapOf("seconds" to 6L), events.last())
    }

    @Test
    fun backgroundAndForegroundDuringGuestBrowsingCannotRestartPrivateMeasurement() {
        val clock = PrivateUsageClock(true)
        clock.foreground(0)
        clock.show("home", 0)
        clock.setPrivateConnection(false, 1_000)
        assertTrue(clock.background(90_000).isEmpty())
        clock.foreground(100_000)
        assertTrue(clock.show("review", 110_000).isEmpty())
        clock.setPrivateConnection(true, 200_000)
        assertEquals("screen.time" to mapOf("screen" to "home", "seconds" to 2.0), clock.show("review", 202_000).first())
        assertEquals("app.background" to mapOf("seconds" to 4L), clock.background(204_000).last())
        assertTrue(clock.background(205_000).isEmpty())
    }

    @Test
    fun aGuestStartupOnlyMeasuresTimeAfterTheExplicitPrivateEntry() {
        val clock = PrivateUsageClock()
        clock.foreground(0)
        assertTrue(clock.show("home", 0).isEmpty())
        assertTrue(clock.background(50_000).isEmpty())
        clock.foreground(60_000)
        assertTrue(clock.setPrivateConnection(true, 100_000).isEmpty())
        assertEquals(listOf("screen.view" to mapOf("screen" to "home", "from" to "start")), clock.show("home", 100_000))
        assertEquals("app.background" to mapOf("seconds" to 2L), clock.background(102_000).last())
    }
}
