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
        versionCode = 50
        versionName = "0.7.2.7"
    }

    signingConfigs {
        // Only a real key from NEOCHAT_SIGNING signs a release. There is deliberately no in-repository
        // fallback key: a committed keystore is public, and every build signed with it shares whatever
        // reputation that public key picks up. Without the secret the release APK comes out unsigned.
        if (signingProps != null) {
            create("release") {
                storeFile = file(signingProps.getProperty("storeFile"))
                storePassword = signingProps.getProperty("storePassword")
                keyAlias = signingProps.getProperty("keyAlias")
                keyPassword = signingProps.getProperty("keyPassword")
                // v3 proves the signing key was never rotated; v1 is off by itself at minSdk 26.
                enableV3Signing = true
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
            signingConfig = signingConfigs.findByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    // Animated emoji sprite sheets are shared by all three apps from the repository root.
    sourceSets.getByName("main").assets.srcDir("${rootDir}/../../assets")

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
    // Shrinks a video before it is sent (H.264 + AAC in MP4); Apache-2.0.
    implementation("androidx.media3:media3-transformer:1.5.1")
    implementation("androidx.media3:media3-effect:1.5.1")
    implementation("androidx.media3:media3-common:1.5.1")
}
