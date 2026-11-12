plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.wani.terminal"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.wani.terminal"
        minSdk = 26
        // If proot cannot exec files inside the rootfs on your phone, try 28 (what Termux uses).
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"
        ndk { abiFilters += "arm64-v8a" }
    }

    packaging {
        // Needed so libproot.so is extracted to nativeLibraryDir and can be executed.
        jniLibs { useLegacyPackaging = true }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    // Termux terminal engine + view (GPLv3). Pin to a tag.
    implementation("com.github.termux.termux-app:terminal-view:v0.118.0")
    implementation("org.apache.commons:commons-compress:1.26.1")
}
