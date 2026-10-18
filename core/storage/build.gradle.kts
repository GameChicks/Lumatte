plugins {
    id("com.android.library")
    kotlin("android")
    id("com.google.devtools.ksp")
    id("androidx.room")
}
android {
    namespace = "com.lumixfilmmirror.storage"
    compileSdk = 36
    defaultConfig { minSdk = 30 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
kotlin { jvmToolchain(17) }
room { schemaDirectory("$projectDir/schemas") }
dependencies {
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    api(project(":core:media"))
    api(project(":core:camera"))
    implementation("androidx.room:room-runtime:2.8.5")
    ksp("androidx.room:room-compiler:2.8.5")
    implementation("androidx.datastore:datastore-preferences:1.1.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
}
