plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.example.autoskip"
    compileSdk {
        version = release(36)
    }

    defaultConfig {
        applicationId = "com.example.autoskip"
        // API 31 is the floor because AccessibilityServiceInfo#isAccessibilityTool
        // only exists from Android 12. Without it the system shows a permanent
        // privacy warning and may auto-disable the service.
        minSdk = 31
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    buildFeatures {
        viewBinding = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.google.material)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.recyclerview)
}
