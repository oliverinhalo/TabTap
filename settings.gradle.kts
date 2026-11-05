// A Maven Central mirror is declared ahead of the canonical host because some build
// environments are rate limited by repo1.maven.org. Both serve identical artifacts.
val mavenCentralMirror = "https://maven-central.storage-download.googleapis.com/maven2"

pluginManagement {
    repositories {
        google()
        maven(url = "https://maven-central.storage-download.googleapis.com/maven2")
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        maven(url = mavenCentralMirror)
        mavenCentral()
    }
}

rootProject.name = "TabTap"
include(":app", ":core-music", ":core-io")
