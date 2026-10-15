plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "com.octaviantocan.pulls"
    compileSdk {
        version = release(37) {
            minorApiLevel = 0
        }
    }
    defaultConfig {
        applicationId = "com.octaviantocan.pulls"
        minSdk = 26
        targetSdk = 36
        versionCode = 50
        versionName = "0.36.0"
    }
    signingConfigs {
        // The published APK is debug-signed; pin the key so every machine signs
        // identically and installed apps keep accepting updates.
        named("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }
    buildFeatures {
        compose = true
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

kotlin {
    jvmToolchain(21)
}

/** Copies `android/CHANGELOG.md` into the app's assets, where the What's new sheet reads it. */
abstract class ChangelogAsset : DefaultTask() {
    @get:InputFile
    abstract val source: RegularFileProperty

    @get:OutputDirectory
    abstract val output: DirectoryProperty

    @TaskAction
    fun copy() {
        source.get().asFile.copyTo(output.get().file("changelog.md").asFile, overwrite = true)
    }
}

val changelogAsset = tasks.register<ChangelogAsset>("changelogAsset") {
    source.set(rootProject.layout.projectDirectory.file("CHANGELOG.md"))
}

/**
 * Gathers every `CONTRACTS` file of the repository into one `contracts.txt` asset for the UI Book's
 * Contracts page: each file's text after a `== <repo-relative path> ==` line, root first, then by path.
 */
abstract class ContractsAsset : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sources: ConfigurableFileCollection

    @get:Input
    abstract val root: Property<String>

    @get:OutputDirectory
    abstract val output: DirectoryProperty

    @TaskAction
    fun bundle() {
        val base = File(root.get())
        val files = sources.files.map { it.relativeTo(base).invariantSeparatorsPath to it }.sortedBy { it.first }
        val text = buildString {
            for ((path, file) in files) {
                val body = file.readText().replace("\r\n", "\n")
                require(body.lines().none { it.matches(Regex("^== .+ ==$")) }) { "$path has a line that looks like a bundle separator" }
                append("== ").append(path).append(" ==\n").append(body)
                if (!body.endsWith("\n")) append('\n')
            }
        }
        output.get().file("contracts.txt").asFile.writeText(text)
    }
}

val repoRoot: Directory = rootProject.layout.projectDirectory.dir("..")

val contractsAsset = tasks.register<ContractsAsset>("contractsAsset") {
    root.set(repoRoot.asFile.canonicalPath)
    sources.from(
        fileTree(repoRoot) {
            include("**/CONTRACTS")
            exclude("**/node_modules", "**/node_modules/**", "**/build/**", "**/.gradle/**", "**/.git/**", "**/dist/**")
        },
    )
}

androidComponents {
    onVariants { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(changelogAsset, ChangelogAsset::output)
        variant.sources.assets?.addGeneratedSourceDirectory(contractsAsset, ContractsAsset::output)
    }
}

// The unit tests read the bundle the APK ships (ContractsBundleTest), so they build it first.
tasks.withType<Test>().configureEach {
    dependsOn(contractsAsset)
    val bundle = contractsAsset.flatMap { it.output.file("contracts.txt") }
    jvmArgumentProviders.add(CommandLineArgumentProvider { listOf("-Dpulls.contracts=${bundle.get().asFile.absolutePath}") })
}

dependencies {
    implementation(project(":core"))
    val composeBom = platform("androidx.compose:compose-bom:2026.09.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.navigation:navigation-compose:2.10.2")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.10.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.10.0")
    implementation("androidx.work:work-runtime:2.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation("io.coil-kt.coil3:coil-compose:3.3.0")
    implementation("io.coil-kt.coil3:coil-network-okhttp:3.3.0")
    implementation("io.coil-kt.coil3:coil-svg:3.3.0")
    implementation("com.caverock:androidsvg-aar:1.4")
    implementation("org.commonmark:commonmark:0.24.0")
    implementation("org.commonmark:commonmark-ext-gfm-tables:0.24.0")
    implementation("org.commonmark:commonmark-ext-gfm-strikethrough:0.24.0")
    implementation("org.commonmark:commonmark-ext-autolink:0.24.0")
    implementation("org.commonmark:commonmark-ext-task-list-items:0.24.0")
    testImplementation(kotlin("test-junit"))
    // Android's org.json is a stub in unit tests; the real one lets BookDataTest read the book the way the app does.
    testImplementation("org.json:json:20250517")
}
