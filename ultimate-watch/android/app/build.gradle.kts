plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }
android {
 namespace = "app.ultimate.watch"
 compileSdk = 35
 defaultConfig { applicationId = "app.ultimate.watch"; minSdk = 29; targetSdk = 35; versionCode = 1; versionName = "0.1.0" }
 compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
 kotlinOptions { jvmTarget = "17" }
 testOptions { unitTests.all { it.workingDir = projectDir } }
}
dependencies {
 implementation("androidx.activity:activity-ktx:1.9.3")
 implementation("androidx.lifecycle:lifecycle-service:2.8.7")
 implementation("androidx.camera:camera-camera2:1.4.1")
 implementation("androidx.camera:camera-lifecycle:1.4.1")
 implementation("androidx.camera:camera-video:1.4.1")
 implementation("androidx.core:core-ktx:1.15.0")
 implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
 testImplementation("junit:junit:4.13.2")
}
