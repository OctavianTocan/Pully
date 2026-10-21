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

rootProject.name = "pulls"
include(":core")
include(":app")
include(":shared-domain", ":shared-client", ":shared-ui")
project(":shared-domain").projectDir = file("../mobile/shared-domain")
project(":shared-ui").projectDir = file("../mobile/shared-ui")
project(":shared-client").projectDir = file("../mobile/shared-client")
