import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.seadash"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.seadash"
        minSdk = 28
        // DiLink 5.0 is Android 12; targetSdk 33 avoids newer background/foreground restrictions on the head unit.
        targetSdk = 33
        versionCode = 1
        versionName = "0.1"
    }

    buildTypes {
        // The helper process is started from the APK by class name, so code shrinking stays off.
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    lint {
        disable += "ExpiredTargetSdkVersion"
        checkReleaseBuilds = false
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    // Connects to the head unit's own adbd (127.0.0.1:5555) to start the shell-identity helper.
    implementation(libs.dadb)
}
