plugins {
    id("com.android.application")
}

android {
    namespace = "com.sampmobile.fork"
    compileSdk = 34

    // NDK r27 is used for the native client. AGP downloads it automatically
    // when it is missing from the SDK; you can also point ANDROID_NDK_HOME
    // at an existing install.
    ndkVersion = "27.0.12077973"

    defaultConfig {
        applicationId = "com.sampmobile.fork"
        minSdk = 21
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    // The SAMP native client (the C++ sources at the repository root) is
    // built with the existing ndk-build files. app/src/main/jni/Android.mk
    // and Application.mk delegate to the shared ones at the repo root, so
    // there is a single source of truth for the ABI list and flags.
    externalNativeBuild {
        ndkBuild {
            path = file("src/main/jni/Android.mk")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
