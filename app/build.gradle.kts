plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.chaquopy)
}

// Optional release signing, used by CI when the NB_KEYSTORE_* secrets are set.
// Without them, release builds fall back to the debug key.
val keystorePath: String? = System.getenv("NB_KEYSTORE_PATH")

android {
    namespace = "io.github.nainiglesias.nb"
    compileSdk = 37

    defaultConfig {
        applicationId = "io.github.nainiglesias.nb"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"

        ndk {
            // Phones. Add "x86_64" here to run on an emulator.
            abiFilters += listOf("arm64-v8a")
        }
    }

    signingConfigs {
        if (keystorePath != null) {
            create("release") {
                storeFile = file(keystorePath)
                storePassword = System.getenv("NB_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("NB_KEY_ALIAS")
                keyPassword = System.getenv("NB_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }
}

chaquopy {
    defaultConfig {
        // 3.10 is the newest Python that Chaquopy has scikit-learn and scipy builds for.
        // The build Python (from mise.toml / CI) must be the same minor version.
        version = "3.10"
        pip {
            // Packages available to notebooks. Only packages with Android wheels
            // (https://chaquo.com/pypi-13.1/) or pure-Python wheels work.
            // pip resolves numpy 1.23, matplotlib 3.6, scipy 1.8, scikit-learn 1.3.
            install("numpy")
            install("pandas")
            install("matplotlib")
            install("scikit-learn") // also pulls in scipy
            // The Android matplotlib builds call pyparsing's camelCase API, which
            // pyparsing 3.3+ flags with a warning printed in every plotting cell.
            install("pyparsing<3.3")
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.core)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.markdown.m3)
    debugImplementation(libs.compose.ui.tooling)

    testImplementation(libs.junit)
}
