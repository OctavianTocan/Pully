// The Kotlin half of the shared AI kit: Claude Code and Codex sign-in, model lists and efforts,
// with no UI, so Pully (Android and iOS) and ThyMind (Android) draw it each in their own look. The
// including build supplies the Kotlin plugin versions; see ../README.md.
plugins {
    id("org.jetbrains.kotlin.multiplatform")
}

kotlin {
    jvmToolchain(21)
    jvm()
    iosArm64()
    iosSimulatorArm64()
    sourceSets {
        commonMain.dependencies {
            api("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
            api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
        }
    }
}
