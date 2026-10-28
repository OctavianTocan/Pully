package com.octaviantocan.aikit

// --- Which models to show first ---

/** The models a picker shows first, and the ones it lists below them under "Older models" (rule 89). */
data class ModelGroups(val recent: List<CliModel>, val older: List<CliModel>)

private val VERSION = Regex("""\b(\d+)(?:[.\-_](\d+))?\b""")

/**
 * The version a model's name carries as `major to minor` (`Opus 5.5`, `claude-opus-4-1`), else the version its
 * description gives the family it is named by ("Opus" with "Opus 5.5 · …"), or null for an alias such as "Default" or
 * "Opus (1M context)", which stands for whatever is newest. "1M" is not a version.
 */
fun modelVersion(model: CliModel): Pair<Int, Int>? {
    val found = versioned(model)?.second ?: return null
    return found.groupValues[1].toInt() to (found.groupValues[2].toIntOrNull() ?: 0)
}

/** The words a model's version is read from (its name, else its id, else its description's head) and the version in them. */
private fun versioned(model: CliModel): Pair<String, MatchResult>? =
    listOfNotNull(model.name, model.id, describedName(model)).firstNotNullOfOrNull { text -> VERSION.find(text)?.let { text to it } }

/**
 * The versioned name a model's description opens with when its own name is the family alone: Claude Code lists its
 * aliases as "Opus" with "Opus 5.5 · Best for everyday, complex tasks". Null when the name has a version already, or
 * the description opens with another model (the default's names the model it stands for).
 */
private fun describedName(model: CliModel): String? {
    if (VERSION.containsMatchIn(model.name)) return null
    val head = model.description?.substringBefore(" · ", "")?.trim().orEmpty()
    return head.takeIf { DESCRIBED.matches(it) && it.substringBefore(' ').equals(model.name.trim(), ignoreCase = true) }
}

private val DESCRIBED = Regex("""\S+ \d+(?:\.\d+)?""")

/** Words that name who makes a model, not which model it is: "GPT-6 Sol" is the Sol tier, "claude-opus-4-1" the Opus one. */
private val MAKERS = setOf("gpt", "claude")

private val WORD = Regex("[a-z][a-z0-9]*")

/**
 * The tier a model is a version of, the line a newer version replaces: the words before its version less the maker's
 * name, with the first word after it. "Opus 4.1" and "Opus 5.5" are the tier "opus"; "GPT-6.1 Sol" and "GPT-6 Sol" the
 * tier "sol", which "GPT-6 Astra" and "GPT-6 Luna" are not, so a lineup of tiers that share a version number keeps each
 * tier's newest (recording 16 Oct 12:10, 04:13 to 04:31; N7). "GPT-5.5-Codex" is "codex" and "GPT-5.5" has none.
 */
fun modelTier(model: CliModel): String {
    val (text, version) = versioned(model) ?: return ""
    val lower = text.lowercase()
    val before = WORD.findAll(lower.substring(0, version.range.first)).map { it.value }.filter { it !in MAKERS }
    val after = WORD.find(lower, version.range.last + 1)?.value
    return (before + listOfNotNull(after)).joinToString(" ")
}

/**
 * Splits a CLI's model list the way Claude's own picker does: the newest version of each tier ([modelTier]) from the
 * newest generation (for Claude Code the 5.5 models and Fable; for Codex GPT-6.1 Sol, GPT-6 Astra and GPT-6 Luna, N7),
 * the default, and the aliases come first; every other version goes under "Older models". The CLI's own order is kept
 * inside each group. A list with nothing older is all recent.
 */
fun groupModels(models: List<CliModel>): ModelGroups {
    val versions = models.associateWith { modelVersion(it) }
    val newestMajor = versions.values.filterNotNull().maxOfOrNull { it.first } ?: return ModelGroups(models, emptyList())
    val newestOfFamily = models.filter { versions[it] != null }
        .groupBy { modelTier(it) }
        .mapValues { (_, members) -> members.maxOf { versions.getValue(it)!!.let { (major, minor) -> major * 1000 + minor } } }
    val (recent, older) = models.partition { model ->
        val version = versions[model]
        version == null || model.isDefault ||
            (version.first == newestMajor && version.first * 1000 + version.second == newestOfFamily[modelTier(model)])
    }
    return ModelGroups(recent, older)
}

/** One provider's models on a list that mixes providers: [heading] is the provider ("AGY", "Claude"), null when the server names none. */
data class ModelSection(val heading: String?, val models: List<CliModel>)

/**
 * Pi's list in sections, one per provider the server groups it by (K2), in the server's order: the default's group
 * first, the rest by name, each newest first. Pi has no "Older models" (K3): every model the endpoint serves is a
 * current choice. A server that sends no groups gives one section with no heading.
 */
fun modelSections(models: List<CliModel>): List<ModelSection> =
    if (models.none { it.group != null }) listOf(ModelSection(null, models))
    else models.groupBy { it.group?.trim()?.ifEmpty { null } ?: "Other" }.map { (heading, members) -> ModelSection(heading, members) }

/** The hyphen between two words reads as a line in a name: `GPT-5.1-Codex-Max` is shown `GPT-5.1 Codex Max`. */
private fun tidyHyphens(name: String): String {
    val parts = name.split(Regex("""(?<=\S)-(?=\S)"""))
    if (parts.size < 2) return name
    return buildString {
        parts.forEachIndexed { index, part ->
            // "GPT-5": a word and its number keep the hyphen they are written with.
            if (index > 0) append(if (index == 1 && parts[0].all { it.isLetter() } && part.firstOrNull()?.isDigit() == true) '-' else ' ')
            append(if (part.equals("gpt", ignoreCase = true)) "GPT" else part)
        }
    }
}

/**
 * The name a row shows: the CLI's display name with its version ("Opus 5.5", from the description when the name is the
 * family alone), without "(recommended)", which the pill says, and without hyphens between words.
 */
fun modelLabel(model: CliModel): String {
    val name = (describedName(model) ?: model.name).replace(Regex("""\s*\(recommended\)""", RegexOption.IGNORE_CASE), "").trim()
    // A CLI that names a model by its id alone (`gpt-6-luna`) still gets a name on the row (H7); a name of its own
    // (`o3-mini`) is kept as the CLI wrote it.
    return if (name == model.id) prettyModelId(name) else tidyHyphens(name)
}

/**
 * A model id written as a name, for when no catalog names it: `gpt-6-luna` is `GPT-6 Luna`, `claude-opus-4-1` is
 * `Claude Opus 4.1`. A word and its number keep their hyphen, two numbers become a version, and words are capitalised.
 */
fun prettyModelId(id: String): String {
    // A route prefix that repeats the family ("claude-claude-opus-5", from a proxy's credential prefix) says it once (K3).
    val parts = id.trim().substringAfterLast('/').split('-', '_', ':').filter { it.isNotEmpty() }
        .let { words -> words.filterIndexed { index, word -> index == 0 || !word.equals(words[index - 1], ignoreCase = true) } }
    if (parts.isEmpty()) return id
    return buildString {
        parts.forEachIndexed { index, part ->
            val previous = parts.getOrNull(index - 1)
            if (index > 0) append(
                when {
                    previous!!.all { it.isDigit() } && part.all { it.isDigit() } -> '.'
                    index == 1 && previous.all { it.isLetter() } && part.first().isDigit() && previous.length <= 3 -> '-'
                    else -> ' '
                },
            )
            append(
                when {
                    part.equals("gpt", ignoreCase = true) -> "GPT"
                    part.equals("agy", ignoreCase = true) -> "AGY"
                    part.first().isLetter() -> part.replaceFirstChar { it.titlecase() }
                    else -> part
                },
            )
        }
    }
}

/** The row a saved id the list no longer carries reads as: the one whose name its id makes, if any. */
fun sameNamed(models: List<CliModel>, id: String): CliModel? =
    if (id.isBlank()) null else prettyModelId(id).let { name -> models.firstOrNull { modelLabel(it).equals(name, ignoreCase = true) } }

/** The one line a row adds under its name, only for the default, whose name alone does not say which model it is. */
fun modelNote(model: CliModel): String? =
    if (model.id == "default") model.description?.substringBefore(" · ")?.trim()?.takeIf { it.isNotEmpty() } else null

