import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

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
        versionCode = 11
        versionName = "0.6.1"
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
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
        release {
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
    implementation("androidx.recyclerview:recyclerview:1.3.0")
    // QR decoding for device linking; Apache-2.0.
    implementation("com.google.zxing:core:3.5.3")
}
