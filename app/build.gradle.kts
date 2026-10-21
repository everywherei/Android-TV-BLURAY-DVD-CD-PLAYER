plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "dev.discmount.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.discmount"
        minSdk = 26          // Android TV / Google TV con Android 8.0 o superior
        targetSdk = 34
        versionCode = 1
        versionName = "0.4.1"
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    externalNativeBuild {
        cmake { path = file("src/main/cpp/CMakeLists.txt") }
    }
    buildTypes {
        release { isMinifyEnabled = false }
    }
}

dependencies {
    implementation(project(":core"))
    implementation("org.videolan.android:libvlc-all:3.6.0")
}
