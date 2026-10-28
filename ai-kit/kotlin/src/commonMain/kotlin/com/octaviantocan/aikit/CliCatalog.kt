package com.octaviantocan.aikit

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** A model a CLI reports (`CliModel`). [group] is who serves it, on a list that mixes providers (Pi); null for the CLIs. */
data class CliModel(
    val id: String,
    val name: String,
    val description: String?,
    val efforts: List<String>,
    val defaultEffort: String?,
    val isDefault: Boolean,
    val group: String? = null,
)

/** `CliModelCatalog`. */
data class CliCatalog(val models: List<CliModel>, val account: String?, val version: String?) {
    /** `cliLine`: account and version joined with ` · `. */
    val line: String get() = listOfNotNull(account?.ifBlank { null }, version?.ifBlank { null }).joinToString(" · ")
}

fun JsonObject.asCatalog(): CliCatalog {
    fun JsonObject.s(key: String) = (this[key] as? JsonPrimitive)?.contentOrNull
    return CliCatalog(
        models = (this["models"] as? JsonArray).orEmpty().mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            CliModel(
                id = o.s("id").orEmpty(),
                name = o.s("name") ?: o.s("id").orEmpty(),
                description = o.s("description")?.ifBlank { null },
                efforts = (o["efforts"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull },
                defaultEffort = o.s("defaultEffort")?.ifBlank { null },
                isDefault = o.s("isDefault") == "true",
                group = o.s("group")?.trim()?.ifBlank { null },
            )
        },
        account = s("account"),
        version = s("version"),
    )
}

/** Settings logic shared with tests: `keptEffort` and `effortLabel` from the web AI settings. */
object AiSettingsLogic {
    val NAMED_EFFORTS = listOf("minimal", "low", "medium", "high", "xhigh", "max", "ultra")

    /** An effort survives a model switch only when the new model lists it. */
    fun keptEffort(catalog: CliCatalog?, id: String, effort: String): String =
        if (effort.isNotEmpty() && catalog?.models?.firstOrNull { it.id == id }?.efforts?.contains(effort) == true) effort else ""

    /** The i18n key of a named effort, or a capitalized id. */
    fun effortLabel(id: String, translate: (String) -> String): String =
        if (id in NAMED_EFFORTS) translate("ai.effort.$id") else id.replaceFirstChar { it.uppercase() }

    /** The model a blank id stands for: the CLI's default. */
    fun current(catalog: CliCatalog?, id: String): CliModel? =
        catalog?.models?.firstOrNull { it.id == id } ?: if (id.isEmpty()) catalog?.models?.firstOrNull { it.isDefault } else null
}

