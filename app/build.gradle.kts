plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "app.falconpilot"
    compileSdk = 34
    defaultConfig {
        applicationId = "app.falconpilot"
        minSdk = 26
        targetSdk = 34
        versionCode = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()
        versionName = "1.0." + (System.getenv("GITHUB_RUN_NUMBER") ?: "1")
        ndk { abiFilters += listOf("arm64-v8a") }
    }
    signingConfigs {
        create("rel") {
            val ks = System.getenv("KS_FILE")
            if (ks != null) {
                storeFile = file(ks); storeType = "pkcs12"
                storePassword = System.getenv("KS_PASS"); keyAlias = "falcon"; keyPassword = System.getenv("KS_PASS")
            }
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            if (System.getenv("KS_FILE") != null) signingConfig = signingConfigs.getByName("rel")
        }
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    androidResources { noCompress += "tflite" }
}
dependencies {
    implementation("com.google.mlkit:text-recognition:16.0.1")
    implementation("org.tensorflow:tensorflow-lite:2.14.0")
    implementation("org.tensorflow:tensorflow-lite-gpu:2.14.0")
    implementation("org.tensorflow:tensorflow-lite-gpu-api:2.14.0")
}
