plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.jetbrains.kotlin.android)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.kotlin.serialization)
}

import java.util.Properties
import java.io.FileInputStream
import java.io.File

val localProperties = Properties()
val localPropertiesFile = rootProject.file("local.properties")
if (localPropertiesFile.exists()) {
    localProperties.load(FileInputStream(localPropertiesFile))
}

android {
    namespace = "com.livetracker.controller"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.livetracker.controller"
        minSdk = 26
        targetSdk = 34
        versionCode = 2
        versionName = "0.2.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
        
        manifestPlaceholders["MAPS_API_KEY"] = localProperties.getProperty("MAPS_API_KEY") ?: ""
    }

    signingConfigs {
        create("debugConfig") {
            storeFile = file("${rootDir}/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
        create("release") {
            val keystorePath = project.findProperty("RELEASE_STORE_FILE") as? String
                ?: System.getenv("RELEASE_STORE_FILE")
                ?: localProperties.getProperty("RELEASE_STORE_FILE")
                ?: if (rootProject.file(".keys/release.keystore").exists()) rootProject.file(".keys/release.keystore").absolutePath else null

            val storePass = project.findProperty("RELEASE_STORE_PASSWORD") as? String
                ?: System.getenv("RELEASE_STORE_PASSWORD")
                ?: localProperties.getProperty("RELEASE_STORE_PASSWORD")

            val keyUser = project.findProperty("RELEASE_KEY_ALIAS") as? String
                ?: System.getenv("RELEASE_KEY_ALIAS")
                ?: localProperties.getProperty("RELEASE_KEY_ALIAS")

            val keyPass = project.findProperty("RELEASE_KEY_PASSWORD") as? String
                ?: System.getenv("RELEASE_KEY_PASSWORD")
                ?: localProperties.getProperty("RELEASE_KEY_PASSWORD")

            val keystoreFile = if (keystorePath.isNullOrBlank()) null
                else if (File(keystorePath).isAbsolute) File(keystorePath)
                else rootProject.file(keystorePath)

            if (keystoreFile != null && keystoreFile.exists() &&
                !storePass.isNullOrBlank() && !keyUser.isNullOrBlank() && !keyPass.isNullOrBlank()
            ) {
                storeFile = keystoreFile
                storePassword = storePass
                keyAlias = keyUser
                keyPassword = keyPass
            } else {
                val isReleaseBuild = project.gradle.startParameter.taskNames.any {
                    it.contains("Release", ignoreCase = true)
                }
                if (isReleaseBuild) {
                    throw GradleException(
                        "Release build requires secure signing configuration. Define RELEASE_STORE_FILE, " +
                        "RELEASE_STORE_PASSWORD, RELEASE_KEY_ALIAS, and RELEASE_KEY_PASSWORD in local.properties or environment variables."
                    )
                }
            }
            enableV1Signing = true
            enableV2Signing = true
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            buildConfigField("String", "UPDATE_MANIFEST_URL", "\"https://raw.githubusercontent.com/DreamHAX444/Controller-App/main/release/update-manifest.json\"")
            buildConfigField("String", "UPDATE_CHANNEL", "\"stable\"")
            buildConfigField("String", "GOOGLE_WEB_CLIENT_ID", "\"${localProperties.getProperty("GOOGLE_WEB_CLIENT_ID") ?: ""}\"")
        }
        debug {
            signingConfig = signingConfigs.getByName("debugConfig")
            isMinifyEnabled = false
            buildConfigField("String", "UPDATE_MANIFEST_URL", "\"https://raw.githubusercontent.com/DreamHAX444/Controller-App/main/release/update-manifest.json\"")
            buildConfigField("String", "UPDATE_CHANNEL", "\"stable\"")
            buildConfigField("String", "GOOGLE_WEB_CLIENT_ID", "\"${localProperties.getProperty("GOOGLE_WEB_CLIENT_ID") ?: ""}\"")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
        jniLibs {
            useLegacyPackaging = true
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
    
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.core)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.kotlinx.serialization.json)
    
    // WebRTC
    implementation(libs.stream.webrtc)
    
    // Maps
    implementation(libs.maps.compose)
    implementation(libs.play.services.maps)
    implementation(libs.osmdroid.android)

    // Google Sign-In & Credentials Manager
    implementation(libs.androidx.credentials)
    implementation(libs.androidx.credentials.play.services.auth)
    implementation(libs.google.identity.googleid)
    implementation(libs.play.services.auth)
    
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
}
