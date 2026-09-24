plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.goral.museintegrator"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.goral.museintegrator"
        // API 30 is the floor: AccessibilityService.takeScreenshot() does not exist before it.
        minSdk = 30
        targetSdk = 35
        versionCode = 1
        versionName = "1.0-graph-v1"
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            // Left unminified deliberately: the release build is sideloaded, not shipped,
            // and R8 stripping reflective AccessibilityService entry points is a bad trade
            // for an app nobody downloads over the wire.
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    packaging {
        resources.excludes += setOf("META-INF/*.kotlin_module")
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.2.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.documentfile:documentfile:1.0.1")
    implementation("androidx.preference:preference-ktx:1.2.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
}
