package com.octaviantocan.pulls.data

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

data class MergeRiskCategory(val key: String, val verdict: String, val reasons: List<String>)
data class MergeRisk(val level: String, val categories: List<MergeRiskCategory>)

private val MERGE_RISK_VERDICTS = linkedMapOf(
    "alignment" to setOf("poor", "decent", "good"),
    "impact" to setOf("large", "medium", "small"),
    "verification" to setOf("poor", "decent", "good"),
    "rollbackCost" to setOf("high", "medium", "low"),
    "errorDetection" to setOf("poor", "decent", "good", "not-applicable"),
)

/** Incomplete or malformed assessments stay absent; legacy reviews never become low risk by default. */
fun JsonObject.asMergeRisk(): MergeRisk? {
    val level = s("level").takeIf { it in setOf("low", "medium", "high") } ?: return null
    val categories = MERGE_RISK_VERDICTS.map { (key, verdicts) ->
        val category = obj(key) ?: return null
        val verdict = category.s("verdict").takeIf { it in verdicts } ?: return null
        val raw = category["reasons"] as? JsonArray ?: return null
        val reasons = raw.map {
            val text = (it as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim() ?: return null
            text.takeIf { it.isNotEmpty() } ?: return null
        }.takeIf { it.isNotEmpty() } ?: return null
        MergeRiskCategory(key, verdict, reasons)
    }
    return MergeRisk(level, categories)
}
