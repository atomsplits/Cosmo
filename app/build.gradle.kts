import java.util.Base64
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// Supabase connection details, kept out of git: set supabase.url and supabase.anonKey in
// local.properties (or SUPABASE_URL / SUPABASE_ANON_KEY in the environment, e.g. on CI).
// Without them the app keeps accounts on the device only.
val localProperties = Properties().apply {
    rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}
fun buildSetting(property: String, environment: String): String =
    (localProperties.getProperty(property) ?: System.getenv(environment) ?: "").trim().replace("\"", "")

/**
 * The project's API address. Supabase also shows a Postgres connection string
 * (postgresql://postgres:…@db.<ref>.supabase.co:5432/postgres), which is easy to paste here by
 * mistake: the app can't use it, and it may contain the database password. So it's turned into
 * the API address (https://<ref>.supabase.co), and nothing else from it is kept.
 */
fun supabaseApiUrl(setting: String): String {
    if (setting.isEmpty()) return ""
    if (setting.startsWith("postgres://") || setting.startsWith("postgresql://")) {
        val ref = Regex("""@db\.([a-z0-9]+)\.supabase\.co""").find(setting)?.groupValues?.get(1)
            ?: throw GradleException("supabase.url is a database connection string. Use the project URL from Project Settings → API (https://<ref>.supabase.co).")
        logger.warn("supabase.url is the database connection string; using the API URL https://$ref.supabase.co instead. Please put that in local.properties, and don't keep the database password there.")
        return "https://$ref.supabase.co"
    }
    if (!setting.startsWith("https://")) throw GradleException("supabase.url must be the project URL, like https://<ref>.supabase.co")
    return setting.trimEnd('/')
}

/** Refuses keys that must never ship in an app, and checks the anon key belongs to this project. */
fun checkSupabaseKey(key: String, url: String) {
    if (key.isEmpty()) return
    if (key.startsWith("sb_secret_")) throw GradleException("supabase.anonKey is a secret key. Use the anon (or publishable) key: secret keys bypass all security and must never be in the app.")
    val parts = key.split('.')
    if (parts.size != 3) return // a new-style publishable key (sb_publishable_…)
    val payload = runCatching { String(Base64.getUrlDecoder().decode(parts[1])) }.getOrNull()
        ?: throw GradleException("supabase.anonKey isn't a valid key. Copy the anon key from Project Settings → API.")
    val role = Regex(""""role"\s*:\s*"([^"]+)"""").find(payload)?.groupValues?.get(1)
    if (role != "anon") throw GradleException("supabase.anonKey has role '$role'. Use the anon key, never service_role: it bypasses all security and must never be in the app.")
    val ref = Regex(""""ref"\s*:\s*"([^"]+)"""").find(payload)?.groupValues?.get(1)
    if (ref != null && url.isNotEmpty() && !url.startsWith("https://$ref.")) {
        throw GradleException("supabase.anonKey is for project '$ref', but supabase.url is $url.")
    }
}

val supabaseUrl = supabaseApiUrl(buildSetting("supabase.url", "SUPABASE_URL"))
val supabaseAnonKey = buildSetting("supabase.anonKey", "SUPABASE_ANON_KEY").also { checkSupabaseKey(it, supabaseUrl) }

android {
    namespace = "com.rivers.cosmo"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "com.rivers.cosmo"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        buildConfigField("String", "SUPABASE_URL", "\"$supabaseUrl\"")
        buildConfigField("String", "SUPABASE_ANON_KEY", "\"$supabaseAnonKey\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging {
        resources {
            // Several libraries (Bouncy Castle, Gson, commons-codec, SLF4J) ship these same paths,
            // which would otherwise clash when packaged. None of them are needed at runtime.
            excludes += setOf(
                "/META-INF/versions/*/OSGI-INF/MANIFEST.MF",
                "/META-INF/versions/9/module-info.class",
                "/META-INF/LICENSE*",
                "/META-INF/NOTICE*"
            )
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.pgpainless.core)
    implementation(libs.okhttp)
    implementation(libs.stellar.sdk)
    // Reads anchors' stellar.toml files. The Stellar SDK already uses it, but only at runtime.
    implementation(libs.toml4j)
    // Decompresses .woff2 fonts users add in Settings.
    implementation(libs.brotli.dec)
    implementation(libs.rhino)
    implementation(libs.zxing.core)
    testImplementation(libs.junit)
    // Android's built-in org.json only exists on devices, so local unit tests need the real library.
    testImplementation(libs.org.json)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}