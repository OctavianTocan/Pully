package com.octaviantocan.pulls.ui.ai

/** Something the person asks about, attached to the question: a file note, a file, or selected lines. [label] names it, [text] is what it says. */
data class AskReference(val label: String, val text: String)
