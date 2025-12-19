plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

import java.util.Properties

android {
    namespace = "com.smartpierre.espflasher"
    compileSdk = 34

    signingConfigs {
        create("release") {
            storeFile = file("../pierre-flasher-keystore.jks")
            storePassword = "Pierre2024!"
            keyAlias = "pierre-flasher"
            keyPassword = "Pierre2024!"
        }
    }

    defaultConfig {
        applicationId = "com.smartpierre.espflasher"
        minSdk = 26
        targetSdk = 34
        
        // Auto-increment version from version.txt file
        val versionFile = file("version.txt")
        val currentVersionString = if (versionFile.exists()) {
            versionFile.readText().trim()
        } else {
            "2.0.0"
        }

        // Parse current version string (e.g., "2.0.0")
        val versionParts = currentVersionString.split(".")
        val major = versionParts.getOrNull(0)?.toIntOrNull() ?: 2
        val minor = versionParts.getOrNull(1)?.toIntOrNull() ?: 0
        val patch = versionParts.getOrNull(2)?.toIntOrNull() ?: 0

        // Increment patch version for each build (limit to 99)
        val newPatch = if (patch >= 99) 99 else patch + 1
        val newVersionString = "$major.$minor.$newPatch"

        // Write new version to file
        versionFile.writeText(newVersionString)

        versionCode = major * 10000 + minor * 100 + newPatch // e.g., 2.0.1 -> 20001
        versionName = newVersionString
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            isMinifyEnabled = false
            isDebuggable = true
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
    composeOptions { kotlinCompilerExtensionVersion = "1.4.8" }
}

kotlin { jvmToolchain(17) }

dependencies {
    implementation(platform("androidx.compose:compose-bom:2023.06.01"))
    implementation("androidx.activity:activity-compose:1.7.2")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation("com.github.mik3y:usb-serial-for-android:3.9.0")
    implementation("androidx.core:core-ktx:1.10.1")

    // MQTT for HiveMQ Cloud
    implementation("org.eclipse.paho:org.eclipse.paho.client.mqttv3:1.2.5")
    implementation("org.eclipse.paho:org.eclipse.paho.android.service:1.1.1")
}
