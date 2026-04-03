plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.ksp)
}

android {
    namespace = "org.eastercon2026.prog"
    compileSdk = 35

    defaultConfig {
        applicationId = "org.eastercon2026.prog"
        minSdk = 26
        targetSdk = 35
        versionCode = 6
        versionName = "1.5.0"
    }

    signingConfigs {
        // Stable app keystore committed to the repo – ensures consistent APK signing
        // so users can install updates over existing installs without uninstalling first.
        create("appRelease") {
            storeFile = file("app-release.keystore")
            storePassword = "eastercon2026"
            keyAlias = "ec2026prog"
            keyPassword = "eastercon2026"
        }
        // Optional override via CI secrets for a production release key
        val keystoreFile = System.getenv("KEYSTORE_FILE")
        if (!keystoreFile.isNullOrEmpty()) {
            create("release") {
                storeFile = file(keystoreFile)
                storePassword = System.getenv("KEYSTORE_PASSWORD") ?: ""
                keyAlias = System.getenv("KEY_ALIAS") ?: ""
                keyPassword = System.getenv("KEY_PASSWORD") ?: ""
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            val keystoreFile = System.getenv("KEYSTORE_FILE")
            signingConfig = if (!keystoreFile.isNullOrEmpty()) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("appRelease")
            }
        }
        debug {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.recyclerview)
    implementation(libs.okhttp)
    implementation(libs.jsoup)
    implementation(libs.gson)
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.lifecycle.viewmodel.ktx)
    implementation(libs.lifecycle.runtime.ktx)
}
