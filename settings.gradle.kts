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
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // Sumsub's identity verification SDK is only published here, not on Maven Central.
        maven { url = uri("https://maven.sumsub.com/repository/maven-public/") }
    }
}

rootProject.name = "Cosmo"
include(":app")
