import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "dev.stagelab.nativehook"
    compileSdk = 37
    buildToolsVersion = "37.0.0"

    defaultConfig {
        applicationId = "io.github.luckylca.stagelab"
        minSdk = 33
        targetSdk = 36
        versionCode = 4
        versionName = "1.1.1-native"
    }

    signingConfigs {
        create("release") {
            val keystoreProps = Properties()
            val propsFile = rootProject.file("keystore.properties").takeIf { it.exists() }
                ?: rootProject.file("../keystore.properties").takeIf { it.exists() }
            if (propsFile != null) {
                propsFile.inputStream().use { keystoreProps.load(it) }
            }
            val storePath = System.getenv("STAGELAB_KEYSTORE_FILE")
                ?: keystoreProps.getProperty("storeFile")
            val storeFileObj = storePath?.let { path ->
                file(path).takeIf { it.exists() } ?: rootProject.file("../$path").takeIf { it.exists() }
            }
            val storePass = System.getenv("STAGELAB_KEYSTORE_PASSWORD")
                ?: keystoreProps.getProperty("storePassword")
            val alias = System.getenv("STAGELAB_KEY_ALIAS")
                ?: keystoreProps.getProperty("keyAlias")
            val keyPass = System.getenv("STAGELAB_KEY_PASSWORD")
                ?: keystoreProps.getProperty("keyPassword")

            if (storeFileObj != null && !storePass.isNullOrBlank() && !alias.isNullOrBlank() && !keyPass.isNullOrBlank()) {
                storeFile = storeFileObj
                storePassword = storePass
                keyAlias = alias
                keyPassword = keyPass
            } else {
                // Allow forks/CI PR checks without release secrets to compile an APK cleanly
                initWith(getByName("debug"))
            }
        }
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
            signingConfig = signingConfigs.getByName("release")
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
