plugins {
    id("com.android.application")
    kotlin("android")
    id("org.jetbrains.kotlin.plugin.compose")
}
android {
    namespace = "com.lumixfilmmirror.app"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.lumixfilmmirror.app"
        minSdk = 30
        targetSdk = 36
        versionCode = 17
        versionName = "1.0.0"
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
    }
    buildFeatures { compose = true }
    packaging { jniLibs { useLegacyPackaging = false } }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
kotlin { jvmToolchain(17) }
dependencies {
    testImplementation("junit:junit:4.13.2")
    implementation(project(":core:model"))
    implementation(project(":core:design"))
    implementation(project(":core:media"))
    implementation(project(":core:camera"))
    implementation(project(":core:storage"))
    implementation(project(":core:transfer"))
    implementation("androidx.activity:activity-compose:1.11.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.4")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.4")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
}
