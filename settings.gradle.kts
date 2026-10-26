pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}
plugins {
    // Lets Gradle download the JDK the build asks for (see app/build.gradle.kts) when it isn't
    // installed, so the build doesn't depend on which Java the IDE happens to run Gradle with.
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "Cosmo"
include(":app")

// The chat/marketplace server is its own Gradle build (with its own Kotlin plugin). Including it
// lets the IDE import it alongside the app, so its Kotlin code is set up without adding Kotlin to
// the app's build. It still runs on its own: ./gradlew :server:run (or ./gradlew -p server run).
includeBuild("server")
