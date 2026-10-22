package com.octaviantocan.pulls.data

/** The engines the server runs a model through, in the order the model picker's tabs show them. */
val ENGINES = listOf("claude-code", "codex", "pi")

/** An engine as the app names it. */
fun engineName(engine: String): String = when (engine) {
    "codex" -> "Codex"
    "pi" -> "Pi"
    else -> "Claude Code"
}

