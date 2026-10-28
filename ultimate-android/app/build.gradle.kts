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
        versionCode = System.getenv("COMPANION_VERSION_CODE")?.toIntOrNull() ?: 20
        versionName = "1.19.0-companion.1"
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
            buildConfigField("boolean", "HAS_SOUND", "true")
            buildConfigField("boolean", "HAS_TRAFFIC", "false")
            buildConfigField("boolean", "HAS_STUDIO", "true")
            buildConfigField("boolean", "HAS_CHECKUP", "true")
            buildConfigField("boolean", "HAS_AI", "false")
            buildConfigField("boolean", "HAS_NETWORK", "false")
            buildConfigField("boolean", "HAS_SENSORS", "true")
            buildConfigField("boolean", "HAS_RECORDER", "true")
            buildConfigField("boolean", "HAS_PHOTOS", "true")
            buildConfigField("boolean", "HAS_PROMPTER", "true")
            buildConfigField("boolean", "HAS_SEND", "false")
            buildConfigField("boolean", "HAS_SCREENTIME", "true")
        }
        // The full toolbox plus the traffic inspector. Needs the internet permission, so it is a separate app from the offline Ultimate.
        create("ultimateplus") {
            dimension = "edition"
            applicationId = if (providers.gradleProperty("companionBuild").orNull == "true") "app.ultimate.companion" else "app.ultimate.plus"
            resValue("string", "app_name", if (providers.gradleProperty("companionBuild").orNull == "true") "Ultimate Companion" else "Ultimate+")
            buildConfigField("boolean", "HAS_SQUEEZE", "true")
            buildConfigField("boolean", "HAS_AUDIO", "true")
            buildConfigField("boolean", "HAS_PLUCK", "true")
            buildConfigField("boolean", "HAS_SOUND", "true")
            buildConfigField("boolean", "HAS_TRAFFIC", "true")
            buildConfigField("boolean", "HAS_STUDIO", "true")
            buildConfigField("boolean", "HAS_CHECKUP", "true")
            buildConfigField("boolean", "HAS_AI", "true")
            buildConfigField("boolean", "HAS_NETWORK", "true")
            buildConfigField("boolean", "HAS_SENSORS", "true")
            buildConfigField("boolean", "HAS_RECORDER", "true")
            buildConfigField("boolean", "HAS_PHOTOS", "true")
            buildConfigField("boolean", "HAS_PROMPTER", "true")
            buildConfigField("boolean", "HAS_SEND", "true")
            buildConfigField("boolean", "HAS_SCREENTIME", "true")
        }
        create("studio") {
            dimension = "edition"
            applicationId = "app.ultimate.studio"
            resValue("string", "app_name", "Studio")
            buildConfigField("boolean", "HAS_SQUEEZE", "false")
            buildConfigField("boolean", "HAS_AUDIO", "false")
            buildConfigField("boolean", "HAS_PLUCK", "false")
            buildConfigField("boolean", "HAS_SOUND", "false")
            buildConfigField("boolean", "HAS_TRAFFIC", "false")
            buildConfigField("boolean", "HAS_STUDIO", "true")
            buildConfigField("boolean", "HAS_CHECKUP", "false")
            buildConfigField("boolean", "HAS_AI", "false")
            buildConfigField("boolean", "HAS_NETWORK", "false")
            buildConfigField("boolean", "HAS_SENSORS", "false")
            buildConfigField("boolean", "HAS_RECORDER", "false")
            buildConfigField("boolean", "HAS_PHOTOS", "false")
            buildConfigField("boolean", "HAS_PROMPTER", "false")
            buildConfigField("boolean", "HAS_SEND", "false")
            buildConfigField("boolean", "HAS_SCREENTIME", "false")
        }
        create("traffic") {
            dimension = "edition"
            applicationId = "app.ultimate.traffic"
            resValue("string", "app_name", "Traffic Inspector")
            buildConfigField("boolean", "HAS_SQUEEZE", "false")
            buildConfigField("boolean", "HAS_AUDIO", "false")
            buildConfigField("boolean", "HAS_PLUCK", "false")
            buildConfigField("boolean", "HAS_SOUND", "false")
            buildConfigField("boolean", "HAS_TRAFFIC", "true")
            buildConfigField("boolean", "HAS_STUDIO", "false")
            buildConfigField("boolean", "HAS_CHECKUP", "false")
            buildConfigField("boolean", "HAS_AI", "false")
            buildConfigField("boolean", "HAS_NETWORK", "false")
            buildConfigField("boolean", "HAS_SENSORS", "false")
            buildConfigField("boolean", "HAS_RECORDER", "false")
            buildConfigField("boolean", "HAS_PHOTOS", "false")
            buildConfigField("boolean", "HAS_PROMPTER", "false")
            buildConfigField("boolean", "HAS_SEND", "false")
            buildConfigField("boolean", "HAS_SCREENTIME", "false")
        }
        create("squeeze") {
            dimension = "edition"
            applicationId = "app.squeeze"
            resValue("string", "app_name", "Squeeze")
            buildConfigField("boolean", "HAS_SQUEEZE", "true")
            buildConfigField("boolean", "HAS_AUDIO", "false")
            buildConfigField("boolean", "HAS_PLUCK", "false")
            buildConfigField("boolean", "HAS_SOUND", "false")
            buildConfigField("boolean", "HAS_TRAFFIC", "false")
            buildConfigField("boolean", "HAS_STUDIO", "false")
            buildConfigField("boolean", "HAS_CHECKUP", "false")
            buildConfigField("boolean", "HAS_AI", "false")
            buildConfigField("boolean", "HAS_NETWORK", "false")
            buildConfigField("boolean", "HAS_SENSORS", "false")
            buildConfigField("boolean", "HAS_RECORDER", "false")
            buildConfigField("boolean", "HAS_PHOTOS", "false")
            buildConfigField("boolean", "HAS_PROMPTER", "false")
            buildConfigField("boolean", "HAS_SEND", "false")
            buildConfigField("boolean", "HAS_SCREENTIME", "false")
        }
        create("audio") {
            dimension = "edition"
            applicationId = "app.ultimate.audio"
            resValue("string", "app_name", "Video to Audio")
            buildConfigField("boolean", "HAS_SQUEEZE", "false")
            buildConfigField("boolean", "HAS_AUDIO", "true")
            buildConfigField("boolean", "HAS_PLUCK", "false")
            buildConfigField("boolean", "HAS_SOUND", "false")
            buildConfigField("boolean", "HAS_TRAFFIC", "false")
            buildConfigField("boolean", "HAS_STUDIO", "false")
            buildConfigField("boolean", "HAS_CHECKUP", "false")
            buildConfigField("boolean", "HAS_AI", "false")
            buildConfigField("boolean", "HAS_NETWORK", "false")
            buildConfigField("boolean", "HAS_SENSORS", "false")
            buildConfigField("boolean", "HAS_RECORDER", "false")
            buildConfigField("boolean", "HAS_PHOTOS", "false")
            buildConfigField("boolean", "HAS_PROMPTER", "false")
            buildConfigField("boolean", "HAS_SEND", "false")
            buildConfigField("boolean", "HAS_SCREENTIME", "false")
        }
        create("pluck") {
            dimension = "edition"
            applicationId = "app.pluck"
            resValue("string", "app_name", "Pluck")
            buildConfigField("boolean", "HAS_SQUEEZE", "false")
            buildConfigField("boolean", "HAS_AUDIO", "false")
            buildConfigField("boolean", "HAS_PLUCK", "true")
            buildConfigField("boolean", "HAS_SOUND", "false")
            buildConfigField("boolean", "HAS_TRAFFIC", "false")
            buildConfigField("boolean", "HAS_STUDIO", "false")
            buildConfigField("boolean", "HAS_CHECKUP", "false")
            buildConfigField("boolean", "HAS_AI", "false")
            buildConfigField("boolean", "HAS_NETWORK", "false")
            buildConfigField("boolean", "HAS_SENSORS", "false")
            buildConfigField("boolean", "HAS_RECORDER", "false")
            buildConfigField("boolean", "HAS_PHOTOS", "false")
            buildConfigField("boolean", "HAS_PROMPTER", "false")
            buildConfigField("boolean", "HAS_SEND", "false")
            buildConfigField("boolean", "HAS_SCREENTIME", "false")
        }
    }

    // Releases are signed with the project's permanent key when CI provides it (GitHub secrets), so every published
    // APK can update the last one. Local builds fall back to the debug key and cannot update a published install.
    signingConfigs {
        System.getenv("ULTIMATE_KEYSTORE_FILE")?.takeIf { it.isNotBlank() }?.let { path ->
            create("release") {
                storeFile = file(path)
                storePassword = System.getenv("ULTIMATE_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("ULTIMATE_KEY_ALIAS")
                keyPassword = System.getenv("ULTIMATE_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            // Companion previews keep readable crash stacks and avoid the slow R8 pass.
            isMinifyEnabled = providers.gradleProperty("companionBuild").orNull != "true"
            isShrinkResources = providers.gradleProperty("companionBuild").orNull != "true"
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
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

// Editions without the AI assistant get a stub, so the code that calls Anthropic exists only in Ultimate+.
android.sourceSets {
    listOf("ultimate", "studio", "traffic", "squeeze", "audio", "pluck").forEach { getByName(it).java.srcDir("src/noai/java") }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.exifinterface:exifinterface:1.3.7")
    implementation("androidx.work:work-runtime-ktx:2.10.0")
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
    implementation("androidx.media3:media3-ui:$media3")
    // Teleprompter: camera preview and recording.
    val camerax = "1.4.1"
    implementation("androidx.camera:camera-camera2:$camerax")
    implementation("androidx.camera:camera-lifecycle:$camerax")
    implementation("androidx.camera:camera-video:$camerax")
    implementation("androidx.camera:camera-view:$camerax")

    // Pure-Java port of the LAME MP3 encoder (LGPL 2.1+). See app/src/main/java/javax/sound for the Android shim it needs.
    implementation("de.sciss:jump3r:1.0.5")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
