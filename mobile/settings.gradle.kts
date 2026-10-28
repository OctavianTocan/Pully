pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "pully-mobile"
include(":shared-domain", ":shared-client", ":shared-ui")
// The Claude Code and Codex models and sign-in logic Pully shares with ThyMind.
include(":ai-kit")
project(":ai-kit").projectDir = file("../ai-kit/kotlin")
