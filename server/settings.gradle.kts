// The chat relay is its own Gradle build, separate from the Android app.
// Run it from the repository root with: ./gradlew -p server run
rootProject.name = "cosmo-chat-server"

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}
