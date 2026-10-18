package com.octaviantocan.pulls.core

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BrowserWindowPolicyTest {
    private val content = BrowserWindowBounds(0, 24, 1080, 2300)
    private val observation = BrowserObservation("com.android.chrome", 7, BrowserRepository("octocat", "Hello-World"))

    @Test
    fun repeatedScrollAndContentEventsKeepTheSameLiveObservation() {
        repeat(200) {
            assertTrue(BrowserWindowPolicy.keepObservation(observation, observation.copy(), sameBrowserEvent = true, windowsChanged = false))
        }
        assertTrue(BrowserWindowPolicy.keepObservation(observation, observation, sameBrowserEvent = false, windowsChanged = true))
    }

    @Test
    fun navigationUnknownAddressOrAnotherApplicationNeverKeepsAnOldButton() {
        for (current in listOf(null, observation.copy(windowId = 8), observation.copy(packageName = "org.mozilla.firefox"),
            observation.copy(repository = BrowserRepository("other", "repo")))) {
            assertFalse(BrowserWindowPolicy.keepObservation(observation, current, sameBrowserEvent = true, windowsChanged = true))
        }
        assertFalse(BrowserWindowPolicy.keepObservation(null, observation, sameBrowserEvent = true, windowsChanged = true))
        assertFalse(BrowserWindowPolicy.keepObservation(observation, observation, sameBrowserEvent = false, windowsChanged = false))
    }

    @Test
    fun higherSystemShadeOrKeyboardBlocksEvenWhenBrowserKeepsFocus() {
        listOf(
            BrowserWindowBounds(0, 0, 1080, 2400),
            BrowserWindowBounds(0, 0, 1080, 500),
            BrowserWindowBounds(0, 1500, 1080, 2400),
            BrowserWindowBounds(200, 500, 900, 1000),
        ).forEach { bounds -> assertFalse(BrowserWindowPolicy.exposed(content, 1, listOf(BrowserWindowCover(bounds, 2)))) }
    }

    @Test
    fun keyboardBlocksEvenWhenResizedBrowserDoesNotOverlapIt() {
        val resized = BrowserWindowBounds(0, 24, 1080, 1500)
        val keyboard = BrowserWindowBounds(0, 1500, 1080, 2400)
        assertFalse(BrowserWindowPolicy.exposed(resized, 1, listOf(
            BrowserWindowCover(keyboard, 3, inputMethod = true),
        )))
    }

    @Test
    fun normalStatusAndNavigationBarsOutsideContentAreAllowed() {
        assertTrue(BrowserWindowPolicy.exposed(content, 1, listOf(
            BrowserWindowCover(BrowserWindowBounds(0, 0, 1080, 24), 9),
            BrowserWindowCover(BrowserWindowBounds(0, 2300, 1080, 2400), 9),
        )))
        assertFalse(BrowserWindowPolicy.exposed(content, 1, listOf(
            BrowserWindowCover(BrowserWindowBounds(0, 0, 1080, 25), 9),
        )))
    }

    @Test
    fun onlyOwnOverlayIsExemptAndOtherSplitScreenWindowDoesNotCoverBrowser() {
        val overlay = BrowserWindowBounds(600, 2000, 1060, 2200)
        assertTrue(BrowserWindowPolicy.exposed(content, 1, listOf(BrowserWindowCover(overlay, 5, ownOverlay = true))))
        assertFalse(BrowserWindowPolicy.exposed(content, 1, listOf(BrowserWindowCover(overlay, 5))))
        assertTrue(BrowserWindowPolicy.exposed(content, 5, listOf(BrowserWindowCover(content, 1))))
        assertTrue(BrowserWindowPolicy.exposed(BrowserWindowBounds(0, 24, 540, 2300), 1, listOf(
            BrowserWindowCover(BrowserWindowBounds(540, 24, 1080, 2300), 2),
        )))
        assertFalse(BrowserWindowPolicy.exposed(BrowserWindowBounds(0, 0, 0, 0), 1, emptyList()))
    }
}
