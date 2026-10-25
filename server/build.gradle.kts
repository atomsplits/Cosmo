plugins {
    kotlin("jvm") version "2.2.21"
    kotlin("plugin.serialization") version "2.2.21"
    application
}

val ktorVersion = "3.6.0"

dependencies {
    implementation("io.ktor:ktor-server-netty:$ktorVersion")
    implementation("io.ktor:ktor-server-websockets:$ktorVersion")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
    implementation("org.pgpainless:pgpainless-core:1.7.8")
    implementation("ch.qos.logback:logback-classic:1.5.18")

    testImplementation(kotlin("test"))
    testImplementation("io.ktor:ktor-server-test-host:$ktorVersion")
    testImplementation("io.ktor:ktor-client-websockets:$ktorVersion")
}

// Runs on any JDK 17 or newer, including the one bundled with Android Studio.
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

application {
    mainClass.set("com.rivers.cosmo.server.MainKt")
}

tasks.test {
    useJUnitPlatform()
}

// The tests log in and chat using the Android app's own PGP code, so they also check that the app
// and the server agree on the protocol.
sourceSets.test {
    kotlin.srcDir("../app/src/main/java/com/rivers/cosmo/chat/crypto")
}
