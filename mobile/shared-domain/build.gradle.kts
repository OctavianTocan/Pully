plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("org.jetbrains.kotlin.plugin.serialization")
}

kotlin {
    jvmToolchain(21)
    jvm()
    listOf(iosArm64(), iosSimulatorArm64()).forEach {
        // The extension links only the UI-free parser. The main app links PullyShared instead.
        it.binaries.framework {
            baseName = "PullyLinks"
            isStatic = true
        }
    }
    sourceSets {
        commonMain.dependencies {
            api(project(":ai-kit"))
            api("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
            api("org.jetbrains.kotlinx:kotlinx-datetime:0.7.1")
            implementation("org.jetbrains:markdown:0.7.3")
        }
        commonMain { kotlin.srcDir(layout.buildDirectory.dir("generated/markdownEntities")) }
        jvmMain.dependencies {
            implementation("org.commonmark:commonmark:0.24.0")
            implementation("org.commonmark:commonmark-ext-gfm-tables:0.24.0")
            implementation("org.commonmark:commonmark-ext-gfm-strikethrough:0.24.0")
            implementation("org.commonmark:commonmark-ext-autolink:0.24.0")
            implementation("org.commonmark:commonmark-ext-task-list-items:0.24.0")
        }
        jvmTest { resources.srcDir("../../android/app/src/test/resources") }
        commonTest {
            kotlin.srcDir(layout.buildDirectory.dir("generated/briefFixture"))
            kotlin.srcDir(layout.buildDirectory.dir("generated/markdownFixtures"))
            kotlin.srcDir(layout.buildDirectory.dir("generated/overviewFixture"))
            dependencies { implementation(kotlin("test")) }
        }
    }
}

// CommonMark 0.24.0's existing HTML5 data stays the semantic source on both hosts.
val markdownEntities = tasks.register("generateMarkdownEntities") {
    val source = layout.projectDirectory.file("markdown/html5-entities.txt")
    val output = layout.buildDirectory.file("generated/markdownEntities/com/octaviantocan/pulls/core/markdown/MarkdownEntities.kt")
    inputs.file(source)
    outputs.file(output)
    doLast {
        val quoted = source.asFile.readText().replace("\\", "\\\\").replace("\"", "\\\"")
            .replace("$", "\\$").replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t")
        output.get().asFile.apply {
            parentFile.mkdirs()
            writeText("package com.octaviantocan.pulls.core.markdown\n" +
                "// Generated from CommonMark 0.24.0 HTML5 entity data.\n" +
                "internal val markdownEntities = \"$quoted\".lineSequence().filter { '=' in it }.associate { it.substringBefore('=') to it.substringAfter('=') } + mapOf(\"NewLine\" to \"\\n\")\n")
        }
    }
}
tasks.matching { it.name.startsWith("compile", ignoreCase = true) }.configureEach { dependsOn(markdownEntities) }

val markdownFixtures = tasks.register("generateMarkdownFixtures") {
    val source = layout.projectDirectory.file("markdown/renderer-fixtures.json")
    val output = layout.buildDirectory.file("generated/markdownFixtures/com/octaviantocan/pulls/core/markdown/MarkdownFixtures.kt")
    inputs.file(source)
    outputs.file(output)
    doLast {
        val quoted = source.asFile.readText().replace("\\", "\\\\").replace("\"", "\\\"")
            .replace("$", "\\$").replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t")
        output.get().asFile.apply {
            parentFile.mkdirs()
            writeText("package com.octaviantocan.pulls.core.markdown\ninternal object MarkdownFixtures { val json = \"$quoted\" }\n")
        }
    }
}
tasks.matching { it.name.contains("compile", ignoreCase = true) && it.name.contains("test", ignoreCase = true) }.configureEach {
    dependsOn(markdownFixtures)
}

tasks.withType<org.jetbrains.kotlin.gradle.targets.native.tasks.KotlinNativeSimulatorTest>().configureEach {
    providers.gradleProperty("pully.iosSimulatorId").orNull?.let { device.set(it) }
}

// The TypeScript producer and both Kotlin platforms use the existing single golden JSON.
val briefFixture = tasks.register("generateBriefFixture") {
    val source = layout.projectDirectory.file("../../android/core/src/test/resources/brief-fixture.json")
    val output = layout.buildDirectory.file("generated/briefFixture/com/octaviantocan/pulls/core/BriefFixture.kt")
    inputs.file(source)
    outputs.file(output)
    doLast {
        val bytes = source.asFile.readBytes().joinToString(",") { it.toString() }
        output.get().asFile.apply {
            parentFile.mkdirs()
            writeText("package com.octaviantocan.pulls.core\ninternal object BriefFixture { val json = byteArrayOf($bytes).decodeToString() }\n")
        }
    }
}
tasks.matching { it.name.contains("compile", ignoreCase = true) && it.name.contains("test", ignoreCase = true) }.configureEach {
    dependsOn(briefFixture)
}

// The existing captured server answer also exercises both platforms' actual overview policies.
val overviewFixture = tasks.register("generateOverviewFixture") {
    val source = layout.projectDirectory.file("../../android/app/src/test/resources/fixtures/pr-overview-106.json")
    val output = layout.buildDirectory.file("generated/overviewFixture/com/octaviantocan/pulls/ui/review/OverviewFixture.kt")
    inputs.file(source)
    outputs.file(output)
    doLast {
        val chunks = source.asFile.readText().chunked(4_000).joinToString(",\n") { chunk ->
            buildString {
                append('"')
                for (character in chunk) when (character) {
                    '\\' -> append("\\\\")
                    '"' -> append("\\\"")
                    '\n' -> append("\\n")
                    '\r' -> append("\\r")
                    '\t' -> append("\\t")
                    else -> if (character.code == 36) append("\\" + character) else append(character)
                }
                append('"')
            }
        }
        output.get().asFile.apply {
            parentFile.mkdirs()
            writeText("package com.octaviantocan.pulls.ui.review\ninternal object OverviewFixture { val json = listOf(\n$chunks\n).joinToString(\"\") }\n")
        }
    }
}
tasks.matching { it.name.contains("compile", ignoreCase = true) && it.name.contains("test", ignoreCase = true) }.configureEach {
    dependsOn(overviewFixture)
}
