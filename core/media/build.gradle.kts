plugins {
    id("com.android.library")
    kotlin("android")
}
android {
    namespace = "com.lumixfilmmirror.media"
    compileSdk = 36
    ndkVersion = "28.2.13676358"
    defaultConfig {
        minSdk = 30
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
        consumerProguardFiles("consumer-rules.pro")
        externalNativeBuild {
            cmake { arguments += "-DANDROID_STL=c++_shared" }
        }
    }
    externalNativeBuild {
        cmake {
            path = file("../../native/raw-preview/CMakeLists.txt")
            version = "3.22.1"
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    sourceSets.getByName("main").assets.srcDir("../../third_party/spektralab/upstream/engine/resources")
}
kotlin { jvmToolchain(17) }
dependencies {
    api(project(":core:model"))
    implementation("androidx.exifinterface:exifinterface:1.4.2")
    implementation("androidx.palette:palette-ktx:1.0.0")
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.documentfile:documentfile:1.1.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
}
