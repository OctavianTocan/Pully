package com.octaviantocan.pulls.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class UpdateManifestTest {
    @Test
    fun parsesAndCompares() {
        val manifest = parseUpdateManifest(
            """{"versionCode":3,"versionName":"0.3.0","sha256":"abc","apkUrl":"https://prs.octaviantocan.com/download/pully.apk","notes":"Fixes"}""",
        )
        assertEquals(3, manifest.versionCode)
        assertTrue(manifest.isNewerThan(2))
        assertFalse(manifest.isNewerThan(3))
    }

    @Test
    fun hashesKnownBytes() {
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            sha256Hex("abc".toByteArray()),
        )
    }
}
