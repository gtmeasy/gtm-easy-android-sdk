plugins {
    id("com.android.application")
    kotlin("android")
    kotlin("plugin.serialization")
}

android {
    namespace = "com.gtmeasy.twilarsample"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.gtmeasy.twilarsample"
        minSdk = 23
        targetSdk = 34
        versionCode = 1
        versionName = "0.2.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    kotlinOptions {
        jvmTarget = "11"
    }

    buildFeatures {
        compose = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14"
    }

    // The LAN staging endpoint used by the GTM Easy monorepo is HTTP, so the
    // sample ships with `usesCleartextTraffic=true` for development. Real
    // production deployments use https://www.gtmeasy.com (the SDK default).
    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = false
        }
    }
}

dependencies {
    // Composite-build dependency on the local SDK. settings.gradle.kts substitutes
    // `com.gtmeasy:growth` with the `:growth` project from the included build.
    implementation("com.gtmeasy:growth")

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.2")
    implementation("androidx.activity:activity-compose:1.9.0")
    implementation("androidx.compose.ui:ui:1.6.8")
    implementation("androidx.compose.ui:ui-tooling-preview:1.6.8")
    implementation("androidx.compose.material3:material3:1.2.1")
    implementation("androidx.compose.material:material-icons-extended:1.6.8")

    debugImplementation("androidx.compose.ui:ui-tooling:1.6.8")
    debugImplementation("androidx.compose.ui:ui-test-manifest:1.6.8")
}
