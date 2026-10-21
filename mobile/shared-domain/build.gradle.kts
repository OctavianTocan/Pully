plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("org.jetbrains.kotlin.plugin.serialization")
}

kotlin {
    jvmToolchain(21)
    jvm()
    iosArm64()
    iosSimulatorArm64()
    sourceSets {
        commonMain.dependencies {
            api("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
        }
        commonTest {
            kotlin.srcDir(layout.buildDirectory.dir("generated/briefFixture"))
            dependencies { implementation(kotlin("test")) }
        }
    }
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
