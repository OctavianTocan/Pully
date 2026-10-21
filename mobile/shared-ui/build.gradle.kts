import com.android.build.api.variant.KotlinMultiplatformAndroidComponentsExtension

plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("com.android.kotlin.multiplatform.library")
    id("org.jetbrains.kotlin.plugin.compose")
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
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.ui)
            implementation(compose.material3)
            implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
        getByName("androidHostTest").dependencies {
            implementation(kotlin("test-junit"))
        }
    }
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
