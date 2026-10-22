package com.octaviantocan.pulls.ui.home

import com.octaviantocan.pulls.core.LinkPlaces
import platform.Foundation.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class UserDefaultsHomeStoreTest {
    @Test
    fun nativeDefaultsRoundTripTheOwnedCopyAndRejectInvalidatedReadsAndWrites() {
        val namespace = LinkPlaces.fileAnchor("home-store-test:${kotlin.time.Clock.System.now()}")
        val defaults = NSUserDefaults.standardUserDefaults
        var valid = true
        val store = UserDefaultsHomeStore(namespace, { valid }, defaults)
        try {
            val tours = TourIndex(store)
            tours.upsert(TourEntry("a", "b", 1, "Native round trip", 4, 2, "Second stop", false, 100, at = 2))
            assertEquals(2, TourIndex(UserDefaultsHomeStore(namespace, { valid }, defaults)).get("a/b#1")?.resumeAt)
            valid = false
            assertNull(store.get(TourIndex.STORAGE_KEY))
            store.set(TourIndex.STORAGE_KEY, "[]")
            valid = true
            assertEquals("Native round trip", TourIndex(store).get("a/b#1")?.prTitle)
        } finally {
            defaults.removeObjectForKey("pully-home.$namespace.${TourIndex.STORAGE_KEY}")
        }
    }
}
