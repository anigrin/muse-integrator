plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// A stable signing key, restored by CI from a repository secret.
//
// Without this, every CI run generates a throwaway debug keystore, so build N+1 cannot be
// installed over build N — and the only way past a signature mismatch is to uninstall, which
// deletes the database and every preserved source.png. The key is what makes the history
// survive an update.
val stableKeystore = rootProject.file("signing/museIntegrator.jks")
val hasStableKeystore = stableKeystore.exists()

android {
    namespace = "com.goral.museintegrator"
    compileSdk = 35

    signingConfigs {
        if (hasStableKeystore) {
            create("stable") {
                storeFile = stableKeystore
                // The keystore itself is the secret; the password only guards the file.
                storePassword = System.getenv("KEYSTORE_PASSWORD") ?: "museIntegrator"
                // keytool normalises aliases to lower case, so this must match the stored form.
                keyAlias = "museintegrator"
                keyPassword = System.getenv("KEY_PASSWORD")
                    ?: System.getenv("KEYSTORE_PASSWORD") ?: "museIntegrator"
            }
        }
    }

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
            // Falls back to the default debug key when no keystore is present, so a local
            // build still works — but such an APK will not install over a CI-signed one.
            if (hasStableKeystore) signingConfig = signingConfigs.getByName("stable")
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
