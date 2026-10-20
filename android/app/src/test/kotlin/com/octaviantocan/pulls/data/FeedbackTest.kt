package com.octaviantocan.pulls.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals

class FeedbackTest {
    private val report = FeedbackReport(
        text = "  The chip jumps.\n", version = "0.51.0", versionCode = 65, screen = "review", place = "octaviantocan/pulls.review/70",
        manufacturer = "Google", model = "Pixel 8", android = "16", sdk = 36, theme = "dark", locale = "en", sentAt = "2026-10-20T06:10:45Z",
    )

    @Test
    fun sendsTheNoteWithItsVersionScreenAndPhone() {
        assertEquals(
            Json.parseToJsonElement(
                """{"text":"The chip jumps.","app":{"version":"0.51.0","versionCode":65},"screen":"review","place":"octaviantocan/pulls.review/70",
                |"device":{"manufacturer":"Google","model":"Pixel 8","android":"16","sdk":36},"theme":"dark","locale":"en","sentAt":"2026-10-20T06:10:45Z"}""".trimMargin(),
            ),
            report.toJson(),
        )
    }

    @Test
    fun leavesOutAPlaceItDoesNotHave() {
        assertEquals(null, report.copy(place = null).toJson()["place"])
    }

    @Test
    fun readsAKeptNote() {
        val item = Json.parseToJsonElement("""{"id":"a1B2c3D4e5F6","text":"Slow swipe","createdAt":"2026-10-20T06:10:45.000Z","status":"done","note":"PR 70"}""").jsonObject.asFeedbackItem()
        assertEquals(FeedbackItem("a1B2c3D4e5F6", "Slow swipe", "2026-10-20T06:10:45.000Z", done = true), item)
    }
}
