// The chat relay is its own Gradle build, separate from the Android app (the root settings include
// it so the IDE imports it too). Run it from the repository root with: ./gradlew :server:run
rootProject.name = "cosmo-chat-server"

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}
