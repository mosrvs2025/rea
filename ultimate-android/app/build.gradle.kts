plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "app.ultimate"
    compileSdk = 35

    defaultConfig {
        applicationId = "app.ultimate"
        minSdk = 29
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"
    }

    // One codebase, several apps. Each edition switches tools on with HAS_* flags; constants let R8 drop the others entirely.
    // A single-tool edition makes that tool its own home screen.
    // Build one with e.g. `gradle :app:assembleSqueezeRelease`. Add an edition by copying a block and its src/<edition>/ folder.
    flavorDimensions += "edition"
    productFlavors {
        create("ultimate") {
            dimension = "edition"
            applicationId = "app.ultimate"
            resValue("string", "app_name", "Ultimate")
            buildConfigField("boolean", "HAS_SQUEEZE", "true")
            buildConfigField("boolean", "HAS_AUDIO", "true")
            buildConfigField("boolean", "HAS_PLUCK", "true")
        }
        create("squeeze") {
            dimension = "edition"
            applicationId = "app.squeeze"
            resValue("string", "app_name", "Squeeze")
            buildConfigField("boolean", "HAS_SQUEEZE", "true")
            buildConfigField("boolean", "HAS_AUDIO", "false")
            buildConfigField("boolean", "HAS_PLUCK", "false")
        }
        create("audio") {
            dimension = "edition"
            applicationId = "app.ultimate.audio"
            resValue("string", "app_name", "Video to Audio")
            buildConfigField("boolean", "HAS_SQUEEZE", "false")
            buildConfigField("boolean", "HAS_AUDIO", "true")
            buildConfigField("boolean", "HAS_PLUCK", "false")
        }
        create("pluck") {
            dimension = "edition"
            applicationId = "app.pluck"
            resValue("string", "app_name", "Pluck")
            buildConfigField("boolean", "HAS_SQUEEZE", "false")
            buildConfigField("boolean", "HAS_AUDIO", "false")
            buildConfigField("boolean", "HAS_PLUCK", "true")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Sideload convenience: signs with the debug key. Supply your own keystore for distribution.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")

    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    val media3 = "1.5.1"
    implementation("androidx.media3:media3-transformer:$media3")
    implementation("androidx.media3:media3-effect:$media3")
    implementation("androidx.media3:media3-common:$media3")
    implementation("androidx.media3:media3-exoplayer:$media3")

    // Pure-Java port of the LAME MP3 encoder (LGPL 2.1+). See app/src/main/java/javax/sound for the Android shim it needs.
    implementation("de.sciss:jump3r:1.0.5")

    testImplementation("junit:junit:4.13.2")
}
