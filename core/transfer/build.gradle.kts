plugins {
    id("com.android.library")
    kotlin("android")
}
android {
    namespace = "com.lumixfilmmirror.transfer"
    compileSdk = 36
    defaultConfig { minSdk = 30 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
kotlin { jvmToolchain(17) }
dependencies {
    api(project(":core:storage"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
}
