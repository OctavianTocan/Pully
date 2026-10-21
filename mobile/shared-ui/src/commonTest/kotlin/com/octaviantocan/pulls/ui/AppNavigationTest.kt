package com.octaviantocan.pulls.ui

import com.octaviantocan.pulls.core.LinkPlace
import com.octaviantocan.pulls.core.OpenTarget
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

class AppNavigationTest {
    @Test
    fun changingEntryReusesOneStackAndMakesHomeReturnToItsCurrentRoot() {
        val navigation = AppNavigation(rootScreen = Screen.Explore)
        val stack = navigation.stack
        navigation.stack.add(Screen.Repo("public-owner", "public-repo"))
        navigation.nav.home()
        assertEquals(listOf(Screen.Explore), stack.toList())
        navigation.reset(Screen.Home)
        navigation.nav.openTarget(OpenTarget.Posted("private-diff"))
        navigation.nav.home()
        assertSame(stack, navigation.stack)
        assertEquals(listOf(Screen.Home), stack.toList())
        navigation.reset(Screen.Explore)
        navigation.nav.back()
        assertEquals(listOf(Screen.Explore), stack.toList())
    }

    @Test
    fun followingAPlaceThenGoingBackKeepsTheExactUnderlyingRoute() {
        val navigation = AppNavigation()
        navigation.nav.openRepo("pully", "mobile")
        val underlying = navigation.stack.last()
        navigation.nav.openPlace(OpenTarget.Pull("pully", "mobile", 1, "github.com"), "repo", LinkPlace.Files(line = 7))
        val review = navigation.stack.last() as Screen.Review
        assertEquals(LinkPlace.Files(line = 7), review.place?.place)
        review.place?.done = true
        navigation.nav.back()
        assertSame(underlying, navigation.stack.last())
        assertEquals(listOf(Screen.Home, underlying), navigation.stack.toList())
    }

    @Test
    fun tourAndSettingsUseTheSameStackAndHomeRemainsItsRoot() {
        val navigation = AppNavigation()
        navigation.nav.openRepoTours("pully", "mobile")
        assertEquals(Screen.Repo("pully", "mobile", tours = true), navigation.stack.last())
        navigation.nav.openTour(OpenTarget.Pull("pully", "mobile", 2, "github.com"))
        assertTrue((navigation.stack.last() as Screen.Review).tour)
        navigation.nav.settings(); navigation.nav.settings()
        assertEquals(4, navigation.stack.size)
        navigation.nav.home(); navigation.nav.back()
        assertEquals(listOf(Screen.Home), navigation.stack.toList())
    }
}
