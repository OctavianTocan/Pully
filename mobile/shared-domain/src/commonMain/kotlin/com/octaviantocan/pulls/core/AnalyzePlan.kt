package com.octaviantocan.pulls.core

/** The depth Analyze runs at. Each one bundles an effort and which passes follow the analysis. */
enum class AnalyzePreset(val id: String, val title: String, val blurb: String) {
    Quick("quick", "Quick", "A summary and a note per file, at the lowest effort. Fastest."),
    Standard("standard", "Standard", "Adds the tour and a brief page made for this change, at your usual effort."),
    Deep("deep", "Deep", "Everything, plus an AI review, at high effort. Slowest."),
}

/**
 * What one Analyze press asks the server for. [effort] overrides the Settings effort when set; [then] are the
 * passes the server starts itself when the analysis succeeds (`then` of the `analyze` task); [review] starts an
 * AI review next to the analysis.
 */
data class AnalyzeRun(val effort: String?, val then: List<String>, val review: Boolean)

/**
 * The choice behind the Analyze button: a preset, or the passes picked one by one under Customize
 * ([preset] is `null`). The button runs the last choice, so it is stored.
 */
data class AnalyzeChoice(val preset: AnalyzePreset?, val tour: Boolean, val brief: Boolean, val review: Boolean) {
    /** The request for this choice. [efforts] are the efforts the chosen model supports, lowest to highest or not. */
    fun run(efforts: List<String>): AnalyzeRun = AnalyzeRun(
        effort = when (preset) {
            AnalyzePreset.Quick -> lowestEffort(efforts)
            AnalyzePreset.Deep -> deepEffort(efforts)
            else -> null
        },
        then = listOfNotNull("tour".takeIf { tour }, "brief".takeIf { brief }),
        review = review,
    )

    /** The name the Analyze button carries: the preset, or Custom. */
    val title: String get() = preset?.title ?: "Custom"

    /** What this choice produces, as one sentence for under the button. */
    fun summary(): String {
        val parts = listOf("a summary", "a note per file") + listOfNotNull("a tour".takeIf { tour }, "a brief".takeIf { brief }, "an AI review".takeIf { review })
        val joined = if (parts.size == 1) parts.single() else parts.dropLast(1).joinToString(", ") + " and " + parts.last()
        // raw-value-ok: Capitalizes one sentence-initial character; never all-caps UI text.
        return joined.replaceFirstChar { it.uppercase() } + "."
    }

    /** The text kept in preferences. */
    fun encode(): String = preset?.id ?: "custom:" + listOfNotNull("tour".takeIf { tour }, "brief".takeIf { brief }, "review".takeIf { review }).joinToString(",")

    companion object {
        fun of(preset: AnalyzePreset): AnalyzeChoice = when (preset) {
            AnalyzePreset.Quick -> AnalyzeChoice(preset, tour = false, brief = false, review = false)
            AnalyzePreset.Standard -> AnalyzeChoice(preset, tour = true, brief = true, review = false)
            AnalyzePreset.Deep -> AnalyzeChoice(preset, tour = true, brief = true, review = true)
        }

        val Default: AnalyzeChoice = of(AnalyzePreset.Standard)

        /** Reads [encode]'s text; anything unknown is the default, so an old or damaged preference never breaks Analyze. */
        fun decode(text: String?): AnalyzeChoice {
            if (text == null) return Default
            AnalyzePreset.entries.firstOrNull { it.id == text }?.let { return of(it) }
            if (!text.startsWith("custom:")) return Default
            val passes = text.removePrefix("custom:").split(',')
            return AnalyzeChoice(null, "tour" in passes, "brief" in passes, "review" in passes)
        }
    }
}

private val EFFORT_ORDER = listOf("minimal", "low", "medium", "high", "xhigh", "max")

private fun rankOf(effort: String): Int = EFFORT_ORDER.indexOf(effort)

/** The least thorough effort the model offers, or `null` when it names none this knows (the Settings effort then stands). */
internal fun lowestEffort(efforts: List<String>): String? = efforts.filter { rankOf(it) >= 0 }.minByOrNull(::rankOf)

/** `high` when offered, else the most thorough one short of `max`; `max` is a deliberate choice in Settings, not a preset. */
internal fun deepEffort(efforts: List<String>): String? =
    if ("high" in efforts) "high" else efforts.filter { rankOf(it) >= 0 && it != "max" }.maxByOrNull(::rankOf)
