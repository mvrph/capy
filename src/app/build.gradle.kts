import java.io.FileInputStream
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

// Release signing + secrets — loaded from keystore.properties, which is
// GITIGNORED and lives only on the build machine (never committed). The file
// holds the keystore passwords AND the pulsar bearer token, so the build works
// locally without any secret ever entering git. On a machine without it,
// release signing is skipped and the pulsar token defaults to empty.
val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.exists()) load(FileInputStream(keystorePropertiesFile))
}

// Pulsar bearer token: gitignored keystore.properties first, then a PULSAR_TOKEN
// env var (for CI secrets), else empty. Baked into BuildConfig so no token is
// ever hardcoded in source. An empty token just means telemetry auth is
// unavailable in that build (calls fail gracefully) — see PulsarClient.
val pulsarToken = (keystoreProperties["pulsarToken"] as String?)
    ?: System.getenv("PULSAR_TOKEN")
    ?: ""

android {
    namespace = "com.custom.minimizer"
    compileSdk {
        version = release(36)
    }

    defaultConfig {
        applicationId = "com.custom.minimizer"
        minSdk = 27
        targetSdk = 36
        versionCode = 2
        versionName = "1.1"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Injected at build time (see pulsarToken above) — never committed.
        buildConfigField("String", "PULSAR_TOKEN", "\"$pulsarToken\"")
    }

    buildFeatures {
        buildConfig = true
    }

    signingConfigs {
        create("release") {
            if (keystorePropertiesFile.exists()) {
                storeFile = file(keystoreProperties["storeFile"] as String)
                storePassword = keystoreProperties["storePassword"] as String
                keyAlias = keystoreProperties["keyAlias"] as String
                keyPassword = keystoreProperties["keyPassword"] as String
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.lifecycle.service)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}