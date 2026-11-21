plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.gms.google-services")
}
android {
    namespace = "hu.fokusz"
    compileSdk = 35
    defaultConfig { applicationId = "hu.fokusz"; minSdk = 26; targetSdk = 35; versionCode = 15; versionName = "2.9" }
    buildFeatures { compose = true }
    // A nagy, helyi ML Kit modellhez tartozó natív könyvtárakat tömörítve
    // csomagoljuk, hogy mobilon/tableten kisebb legyen az APK.
    packaging { jniLibs { useLegacyPackaging = true } }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
}
dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.09.03"))
    implementation(platform("com.google.firebase:firebase-bom:34.4.0"))
    implementation("com.google.firebase:firebase-auth")
    implementation("com.google.firebase:firebase-firestore")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.9.0")
    implementation("com.google.guava:guava:33.3.1-android")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.camera:camera-core:1.4.2")
    implementation("androidx.camera:camera-camera2:1.4.2")
    implementation("androidx.camera:camera-lifecycle:1.4.2")
    implementation("androidx.camera:camera-view:1.4.2")
    implementation("androidx.webkit:webkit:1.13.0")
    implementation("com.google.mlkit:pose-detection:18.0.0-beta5")
    implementation("com.google.android.gms:play-services-nearby:19.3.0")
    implementation("com.google.android.gms:play-services-code-scanner:16.1.0")
    implementation("com.google.zxing:core:3.5.4")
}
