package com.octaviantocan.pulls.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

class ModelsTest {
    private fun timestamp(value: String?): Long? = buildJsonObject {
        if (value == null) put("updatedAt", JsonNull) else put("updatedAt", value)
    }.asPull().updatedAtMs

    @Test
    fun timestampsPreserveUtcOffsetsAndMillisecondPrecision() {
        for (value in listOf(
            "2026-10-21T12:34:56.123Z",
            "2026-10-21T12:34:56.123456789Z",
            "2026-10-21t12:34:56.123z",
            "2026-10-21T14:34:56.123+02:00",
            "2026-10-21T08:34:56.123-04:00",
            "2026-10-21T15:05:11.123+02:30:15",
        )) assertEquals(1_792_586_096_123L, timestamp(value), value)
    }

    @Test
    fun submillisecondTimesRoundDownAcrossTheEpoch() {
        assertEquals(-1L, timestamp("1969-12-31T23:59:59.999999999Z"))
        assertEquals(0L, timestamp("1970-01-01T00:00:00.000000001Z"))
        assertEquals(0L, timestamp("1970-01-01T00:00:00.Z"))
    }

    @Test
    fun legacyIsoLeapSecondsAndEndOfDayKeepTheirMeaning() {
        assertEquals(1_483_228_799_000L, timestamp("2016-12-31T23:59:60Z"))
        assertEquals(1_483_228_799_123L, timestamp("2016-12-31T23:59:60.123Z"))
        assertEquals(1_483_225_199_000L, timestamp("2016-12-31T23:59:60+01:00"))
        assertEquals(1_792_627_200_000L, timestamp("2026-10-21T24:00:00Z"))
        assertEquals(1_792_620_000_000L, timestamp("2026-10-21T24:00:00.000+02:00"))
    }

    @Test
    fun missingMalformedAndIncompleteTimestampsStayUnknown() {
        assertNull(timestamp(null))
        for (value in listOf(
            "", "not a date", "2026-10-21", "2026-10-21T12:34:56", "2026-10-21T12:34Z",
            "2026-10-21T12:34:56+02", "2026-10-21T12:34:56+19:00", "2026-10-21T12:34:56+18:01",
            "2026-02-30T12:34:56Z", "2016-12-31T22:59:60Z", "2026-10-21T24:00:00.001Z",
            "-0000-01-01T00:00:00Z",
        )) assertNull(timestamp(value), value)
    }

    @Test
    fun epochMillisecondOverflowStaysUnknown() {
        assertEquals(Long.MIN_VALUE, timestamp("-292275055-05-16T16:47:04.192Z"))
        assertEquals(Long.MAX_VALUE, timestamp("+292278994-08-17T07:12:55.807999999Z"))
        assertNull(timestamp("-292275055-05-16T16:47:04.191999999Z"))
        assertNull(timestamp("+292278994-08-17T07:12:55.808Z"))
        assertNull(timestamp("+999999999-12-31T23:59:59.999Z"))
    }

    @Test
    fun optionalFieldsRetainDefaultsAndUnknownFieldsAreIgnored() {
        val pull = Json.parseToJsonElement("""{"future":{"shape":[1,2,3]}}""").jsonObject.asPull()
        assertEquals("open", pull.state)
        assertEquals(0, pull.commentsCount)
        assertEquals(emptyList(), pull.labels)
        assertNull(pull.requestedFromMe)
        assertNull(pull.additions)
        assertNull(pull.updatedAtMs)
        assertNull(pull.stack)
        assertNull(pull.jev)
        assertFalse(pull.isDraft)
        assertFalse(pull.merged)
    }

    @Test
    fun explicitFalseStaysDistinctFromAnUnknownReviewRequest() {
        val pulls = Json.parseToJsonElement(
            """{"pulls":[{"number":1,"requestedFromMe":false,"labels":["Tracked",{"name":"bug"}],"reviewRequests":["bob"]},{"number":2}]}""",
        ).asPulls()
        assertEquals(false, pulls[0].requestedFromMe)
        assertEquals(listOf("Tracked", "bug"), pulls[0].labels)
        assertEquals(listOf("bob"), pulls[0].reviewRequests)
        assertNull(pulls[1].requestedFromMe)
    }

    @Test
    fun pullListsKeepArrayAndWrappedResponseCompatibility() {
        assertEquals(listOf(3), Json.parseToJsonElement("""[null,3,{"number":3}]""").asPulls().map { it.number })
        assertEquals(emptyList(), Json.parseToJsonElement("null").asPulls())
        assertEquals(emptyList(), Json.parseToJsonElement("{}").asPulls())
    }
}
