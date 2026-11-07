plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.wildan.adzancountdown"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.wildan.adzancountdown"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            // R8 shrinking/obfuscation; no reflection in the app, so no extra keep rules needed.
            isMinifyEnabled = true
            isShrinkResources = true
            isDebuggable = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
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
        viewBinding = true
    }
}

dependencies {
    // core 1.17+ is required for NotificationCompat Live Update APIs
    // (setRequestPromotedOngoing, setShortCriticalText, ProgressStyle).
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("com.google.android.material:material:1.12.0")

    testImplementation("junit:junit:4.13.2")
    // Real org.json for JVM unit tests (the Android one is a stub there).
    testImplementation("org.json:json:20250517")
}
