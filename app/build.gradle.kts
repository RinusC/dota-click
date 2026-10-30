import org.jetbrains.kotlin.compose.compiler.gradle.ComposeFeatureFlag

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.dotaidle.ancientwar"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.dotaidle.ancientwar"
        minSdk = 26 // Android 8.0
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Prototype: release is signed with the debug key so it installs without a keystore.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

composeCompiler {
    // The engine state is plain (non-snapshot) data refreshed by a version counter, so
    // composables taking the ViewModel must not be skipped when their parent recomposes.
    featureFlags.add(ComposeFeatureFlag.StrongSkipping.disabled())
}

dependencies {
    implementation("com.dotaidle:core:1.0") // included build ../core

    implementation(platform("androidx.compose:compose-bom:2024.10.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.core:core-ktx:1.13.1")
}
