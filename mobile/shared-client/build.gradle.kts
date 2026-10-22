plugins {
    id("org.jetbrains.kotlin.multiplatform")
}

kotlin {
    jvmToolchain(21)
    jvm()
    iosArm64()
    iosSimulatorArm64 {
        val mainCompilation = compilations.getByName("main")
        val integration = compilations.create("integrationTest") {
            associateWith(mainCompilation)
            defaultSourceSet {
                kotlin.srcDir("src/iosIntegrationTest/kotlin")
                dependencies {
                    implementation(kotlin("test"))
                    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
                }
            }
        }
        binaries.test("integration", listOf(DEBUG)) { compilation = integration }
        testRuns.create("integration") {
            setExecutionSourceFrom(binaries.getTest("integration", DEBUG))
        }
    }
    sourceSets {
        commonMain.dependencies {
            api(project(":shared-domain"))
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
        }
        jvmMain.dependencies {
            implementation("com.squareup.okhttp3:okhttp:4.12.0")
        }
    }
}

tasks.withType<org.jetbrains.kotlin.gradle.targets.native.tasks.KotlinNativeSimulatorTest>().configureEach {
    providers.gradleProperty("pully.iosSimulatorId").orNull?.let { device.set(it) }
}

tasks.named<org.jetbrains.kotlin.gradle.targets.native.tasks.KotlinNativeSimulatorTest>("iosSimulatorArm64IntegrationTest") {
    testLogging {
        events("failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
    val fixtureUrl = providers.gradleProperty("pully.rpcFixtureUrl")
    inputs.property("rpcFixtureUrl", fixtureUrl.orElse(""))
    doFirst {
        val url = fixtureUrl.orNull
        require(url != null && Regex("http://127\\.0\\.0\\.1:[0-9]+").matches(url)) {
            "Start ios/scripts/rpc-fixture.py and pass -Ppully.rpcFixtureUrl=http://127.0.0.1:<port>."
        }
        // simctl spawn only forwards explicit child environment variables to the simulator process.
        environment("SIMCTL_CHILD_PULLY_RPC_FIXTURE_URL", url)
    }
}
