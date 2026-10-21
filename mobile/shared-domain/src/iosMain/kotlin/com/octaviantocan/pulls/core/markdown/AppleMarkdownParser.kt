package com.octaviantocan.pulls.core.markdown

actual fun parseMarkdown(source: String): Document = parsePortableMarkdown(source)
