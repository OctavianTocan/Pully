import com.android.build.api.variant.KotlinMultiplatformAndroidComponentsExtension

plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("com.android.kotlin.multiplatform.library")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("org.jetbrains.compose")
}

kotlin {
    android {
        namespace = "com.octaviantocan.pulls.shared.ui"
        compileSdk = 37
        minSdk = 26
        androidResources { enable = true }
        withHostTestBuilder { sourceSetTreeName = "test" }.configure {}
    }
    listOf(iosArm64(), iosSimulatorArm64()).forEach {
        it.binaries.framework {
            baseName = "PullyShared"
            isStatic = true
        }
    }
    sourceSets {
        commonMain.dependencies {
            implementation(project(":shared-domain"))
            implementation(project(":shared-client"))
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.ui)
            implementation(compose.material3)
            implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
            implementation("io.coil-kt.coil3:coil-compose:3.3.0")
        }
        commonMain { kotlin.srcDir(layout.buildDirectory.dir("generated/vectorSources")) }
        androidMain.dependencies {
            implementation("androidx.activity:activity-compose:1.13.0")
            implementation("com.caverock:androidsvg-aar:1.4")
        }
        iosMain.dependencies {
            implementation("io.coil-kt.coil3:coil-svg:3.3.0")
            implementation("io.coil-kt.coil3:coil-network-ktor3:3.3.0")
            implementation("io.ktor:ktor-client-darwin:3.2.3")
        }
        commonTest {
            kotlin.srcDir(layout.buildDirectory.dir("generated/soundFixtures"))
            dependencies {
                implementation(kotlin("test"))
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
            }
        }
        getByName("androidHostTest").dependencies {
            implementation(kotlin("test-junit"))
        }
    }
}

// Android and Native run the existing Cuelume browser goldens and the same generated production recipes.
val soundFixtures = tasks.register("generateSoundFixtures") {
    val recipes = layout.projectDirectory.file("../../android/app/src/main/assets/cuelume/recipes.json")
    val reference = layout.projectDirectory.file("../../android/app/src/test/resources/fixtures/cuelume-reference.json")
    val output = layout.buildDirectory.file("generated/soundFixtures/com/octaviantocan/pulls/sound/SoundFixtures.kt")
    inputs.files(recipes, reference)
    outputs.file(output)
    doLast {
        fun chunks(text: String): String = text.chunked(4_000).joinToString(",\n") { chunk ->
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
            writeText(
                "package com.octaviantocan.pulls.sound\ninternal object SoundFixtures {\n" +
                    "val recipes = listOf(\n" + chunks(recipes.asFile.readText()) + "\n).joinToString(\"\")\n" +
                    "val reference = listOf(\n" + chunks(reference.asFile.readText()) + "\n).joinToString(\"\")\n}\n"
            )
        }
    }
}
tasks.matching { it.name.contains("compile", ignoreCase = true) && it.name.contains("test", ignoreCase = true) }.configureEach {
    dependsOn(soundFixtures)
}

tasks.withType<org.jetbrains.kotlin.gradle.targets.native.tasks.KotlinNativeSimulatorTest>().configureEach {
    providers.gradleProperty("pully.iosSimulatorId").orNull?.let { device.set(it) }
}

abstract class FontResources : DefaultTask() {
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val source: DirectoryProperty

    @get:OutputDirectory
    abstract val output: DirectoryProperty

    @TaskAction
    fun copyFonts() {
        val target = output.get().asFile
        target.deleteRecursively()
        source.get().asFile.copyRecursively(File(target, "font"), overwrite = true)
    }
}

val fontResources = tasks.register<FontResources>("fontResources") {
    source.set(layout.projectDirectory.dir("../../android/app/src/main/res/font"))
    output.set(layout.buildDirectory.dir("generated/fontResources"))
}
extensions.configure<KotlinMultiplatformAndroidComponentsExtension> {
    onVariants { variant ->
        variant.sources.res!!.addGeneratedSourceDirectory(fontResources, FontResources::output)
    }
}

/** Canonical checked-in XML paths also feed Compose Native; no second maintained icon set. */
abstract class VectorSources : DefaultTask() {
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val source: DirectoryProperty

    @get:OutputDirectory
    abstract val output: DirectoryProperty

    @TaskAction
    fun generate() {
        val namespace = "http://schemas.android.com/apk/res/android"
        val factory = javax.xml.parsers.DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            setFeature("http://xml.org/sax/features/external-general-entities", false)
            setFeature("http://xml.org/sax/features/external-parameter-entities", false)
        }
        fun quote(value: String) = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"").replace("$", "\\$") + "\""
        val vectors = source.get().asFile.listFiles().orEmpty().filter {
            it.name.startsWith("ic_ph_") || it.name.startsWith("ic_cat_") || it.name in setOf("ic_si_claude.xml", "ic_si_openai.xml", "ic_si_google.xml", "ic_stat_pulls.xml", "ic_carbon_logo_github.xml")
        }.filter { it.extension == "xml" }.sortedBy { it.name }
        require(vectors.isNotEmpty()) { "Missing canonical icon vectors." }
        val code = buildString {
            appendLine("package com.octaviantocan.pulls.ui.design")
            appendLine("import androidx.compose.ui.graphics.Color")
            appendLine("import androidx.compose.ui.graphics.PathFillType")
            appendLine("import androidx.compose.ui.graphics.SolidColor")
            appendLine("import androidx.compose.ui.graphics.StrokeCap")
            appendLine("import androidx.compose.ui.graphics.StrokeJoin")
            appendLine("import androidx.compose.ui.graphics.vector.ImageVector")
            appendLine("import androidx.compose.ui.graphics.vector.PathParser")
            appendLine("import androidx.compose.ui.unit.dp")
            appendLine("// Generated from canonical Android XML. Do not edit.")
            appendLine("internal fun createSharedVector(name: String): ImageVector? = when (name) {")
            for (file in vectors) {
                val root = factory.newDocumentBuilder().parse(file).documentElement
                require(root.tagName == "vector") { "Unsupported vector root: ${file.name}" }
                for (a in 0 until root.attributes.length) {
                    val attribute = root.attributes.item(a)
                    require(attribute.namespaceURI == "http://www.w3.org/2000/xmlns/" ||
                        (attribute.namespaceURI == namespace && attribute.localName in setOf("width", "height", "viewportWidth", "viewportHeight"))) {
                        "Unsupported vector root attribute in ${file.name}"
                    }
                }
                fun attr(name: String) = root.getAttributeNS(namespace, name)
                fun number(name: String, dp: Boolean = false): String {
                    val raw = attr(name).let { if (dp) it.removeSuffix("dp") else it }
                    require(raw.toFloatOrNull() != null) { "Invalid $name in ${file.name}" }
                    return "${raw.toFloat()}f"
                }
                appendLine("    ${quote(file.nameWithoutExtension)} -> ImageVector.Builder(${quote(file.nameWithoutExtension)}, ${number("width", true)}.dp, ${number("height", true)}.dp, ${number("viewportWidth")}, ${number("viewportHeight")}).apply {")
                fun emitElement(path: org.w3c.dom.Element) {
                    if (path.tagName == "group") {
                        for (a in 0 until path.attributes.length) {
                            val attribute = path.attributes.item(a)
                            require(attribute.namespaceURI == namespace && attribute.localName in setOf("translateX", "translateY")) {
                                "Unsupported vector group attribute in ${file.name}"
                            }
                        }
                        fun translation(name: String): String {
                            val value = path.getAttributeNS(namespace, name).ifBlank { "0" }.toFloat()
                            require(value.isFinite()) { "Invalid vector group translation in ${file.name}" }
                            return "${value}f"
                        }
                        appendLine("        addGroup(translationX = ${translation("translateX")}, translationY = ${translation("translateY")})")
                        for (index in 0 until path.childNodes.length) {
                            val child = path.childNodes.item(index) as? org.w3c.dom.Element ?: continue
                            emitElement(child)
                        }
                        appendLine("        clearGroup()")
                        return
                    }
                    require(path.tagName == "path") { "Unsupported vector node in ${file.name}" }
                    val supported = setOf("pathData", "fillColor", "fillAlpha", "fillType", "strokeColor", "strokeWidth", "strokeLineCap", "strokeLineJoin")
                    for (a in 0 until path.attributes.length) {
                        require(path.attributes.item(a).localName in supported) { "Unsupported vector attribute in ${file.name}" }
                    }
                    val data = path.getAttributeNS(namespace, "pathData")
                    require(data.isNotEmpty()) { "Missing vector path in ${file.name}" }
                    val color = path.getAttributeNS(namespace, "fillColor")
                    require(color.matches(Regex("#[0-9A-Fa-f]{8}"))) { "Unsupported vector color in ${file.name}" }
                    val alpha = path.getAttributeNS(namespace, "fillAlpha").ifBlank { "1" }.toFloat()
                    val rule = when (path.getAttributeNS(namespace, "fillType")) {
                        "", "nonZero" -> "NonZero"
                        "evenOdd" -> "EvenOdd"
                        else -> error("Unsupported vector fill rule in ${file.name}")
                    }
                    val stroke = path.getAttributeNS(namespace, "strokeColor")
                    val strokeArguments = if (stroke.isEmpty()) "" else {
                        require(stroke.matches(Regex("#[0-9A-Fa-f]{8}"))) { "Unsupported vector stroke color in ${file.name}" }
                        val width = path.getAttributeNS(namespace, "strokeWidth").ifBlank { "0" }.toFloat()
                        require(width.isFinite() && width >= 0) { "Invalid vector stroke width in ${file.name}" }
                        val cap = when (path.getAttributeNS(namespace, "strokeLineCap")) {
                            "", "butt" -> "Butt"
                            "round" -> "Round"
                            "square" -> "Square"
                            else -> error("Unsupported vector stroke cap in ${file.name}")
                        }
                        val join = when (path.getAttributeNS(namespace, "strokeLineJoin")) {
                            "", "miter" -> "Miter"
                            "round" -> "Round"
                            "bevel" -> "Bevel"
                            else -> error("Unsupported vector stroke join in ${file.name}")
                        }
                        ", stroke = SolidColor(Color(0x${stroke.drop(1)})), strokeLineWidth = ${width}f, strokeLineCap = StrokeCap.$cap, strokeLineJoin = StrokeJoin.$join"
                    }
                    appendLine("        addPath(pathData = PathParser().parsePathString(${quote(data)}).toNodes(), fill = SolidColor(Color(0x${color.drop(1)})), fillAlpha = ${alpha}f, pathFillType = PathFillType.$rule$strokeArguments)")
                }
                for (index in 0 until root.childNodes.length) {
                    val element = root.childNodes.item(index) as? org.w3c.dom.Element ?: continue
                    emitElement(element)
                }
                appendLine("    }.build()")
            }
            appendLine("    else -> null")
            appendLine("}")
        }
        val target = output.get().file("com/octaviantocan/pulls/ui/design/GeneratedVectors.kt").asFile
        target.parentFile.mkdirs()
        target.writeText(code)
    }
}

val vectorSources = tasks.register<VectorSources>("vectorSources") {
    source.set(layout.projectDirectory.dir("../../android/app/src/main/res/drawable"))
    output.set(layout.buildDirectory.dir("generated/vectorSources"))
}
tasks.matching { it.name.startsWith("compile", ignoreCase = true) }.configureEach { dependsOn(vectorSources) }
