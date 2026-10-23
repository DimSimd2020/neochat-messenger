import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// The local Firebase configuration is supplied separately, including on CI.
if (file("google-services.json").isFile) apply(plugin = "com.google.gms.google-services")

// Release signing material lives outside the repository. Point NEOCHAT_SIGNING
// (env or -P) at a properties file with storeFile, storePassword, keyAlias, keyPassword.
val signingProps = (System.getenv("NEOCHAT_SIGNING") ?: findProperty("NEOCHAT_SIGNING") as String?)
    ?.let { path -> Properties().apply { file(path).inputStream().use { load(it) } } }

android {
    namespace = "app.neochat"
    compileSdk = 35

    defaultConfig {
        applicationId = "app.neochat"
        minSdk = 26
        targetSdk = 35
        testInstrumentationRunner = "app.neochat.ui.InteractionInstrumentation"
        versionCode = 31
        versionName = "0.6.9.7"
    }

    signingConfigs {
        create("release") {
            if (signingProps != null) {
                storeFile = file(signingProps.getProperty("storeFile"))
                storePassword = signingProps.getProperty("storePassword")
                keyAlias = signingProps.getProperty("keyAlias")
                keyPassword = signingProps.getProperty("keyPassword")
            } else {
                // Sideload keystore so CI/local release APKs install when NEOCHAT_SIGNING is unset.
                storeFile = file("${rootDir}/keystore/sideload.keystore")
                storePassword = "android"
                keyAlias = "sideload"
                keyPassword = "android"
            }
        }
    }

    buildTypes {
        debug { ndk { abiFilters += listOf("arm64-v8a", "x86_64") } }
        release {
            // CI builds the Rust core for arm64; WebRTC must use the same ABI.
            ndk { abiFilters += listOf("arm64-v8a") }
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    packaging { jniLibs { useLegacyPackaging = false } }
}

dependencies {
    implementation(platform("com.google.firebase:firebase-bom:35.0.2"))
    implementation("com.google.firebase:firebase-messaging")
    implementation("androidx.recyclerview:recyclerview:1.3.0")
    // QR decoding for device linking; Apache-2.0.
    implementation("com.google.zxing:core:3.5.3")
    // WebRTC for calls (libwebrtc M150 prebuilt by the webrtc-sdk project); BSD-3-Clause.
    implementation("io.github.webrtc-sdk:android:150.7871.01")
}
