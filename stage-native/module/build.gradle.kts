plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "dev.stagelab.nativehook"
    compileSdk = 37
    buildToolsVersion = "37.0.0"

    defaultConfig {
        applicationId = "dev.stagelab.nativehook"
        minSdk = 33
        targetSdk = 36
        versionCode = 3
        versionName = "1.1.0-native"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":core"))
    compileOnly(libs.xposed.api)

    implementation(libs.kotlinx.coroutines.android)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)

    debugImplementation(libs.androidx.compose.ui.tooling)
}
