package com.octaviantocan.pulls.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MergeRiskTest {
    private val risk = Json.parseToJsonElement("""{
        "level":"high",
        "alignment":{"verdict":"good","reasons":["Matches the description."]},
        "impact":{"verdict":"large","reasons":["Touches [auth](src/auth.kt#L2)."]},
        "verification":{"verdict":"poor","reasons":["No end-to-end evidence."]},
        "rollbackCost":{"verdict":"high","reasons":["Issued tokens need revocation."]},
        "errorDetection":{"verdict":"not-applicable","reasons":["No runtime detection applies."]}
    }""").jsonObject

    @Test
    fun anAssessmentKeepsEveryVerdictAndLinkedReasonWithoutFindings() {
        val critique = JsonObject(mapOf("summary" to JsonPrimitive("No inline defects."), "mergeRisk" to risk)).asCritique()
        assertEquals(emptyList(), critique.findings)
        assertEquals("high", critique.mergeRisk?.level)
        assertEquals(5, critique.mergeRisk?.categories?.size)
        assertEquals("Touches [auth](src/auth.kt#L2).", critique.mergeRisk?.categories?.get(1)?.reasons?.single())
        assertEquals("not-applicable", critique.mergeRisk?.categories?.last()?.verdict)
    }

    @Test
    fun legacyAndIncompleteAssessmentsStayAbsent() {
        assertNull(JsonObject(emptyMap()).asCritique().mergeRisk)
        for (broken in listOf(
            JsonObject(risk - "verification"),
            JsonObject(risk + ("level" to JsonPrimitive("unknown"))),
            JsonObject(risk + ("impact" to Json.parseToJsonElement("""{"verdict":"good","reasons":["Wrong scale."]}"""))),
            JsonObject(risk + ("alignment" to Json.parseToJsonElement("""{"verdict":"good","reasons":[" "]}"""))),
        )) {
            val critique = JsonObject(mapOf("summary" to JsonPrimitive("Still readable."), "mergeRisk" to broken)).asCritique()
            assertEquals("Still readable.", critique.summary)
            assertNull(critique.mergeRisk)
        }
    }
}
