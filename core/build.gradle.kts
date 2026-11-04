plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.jetbrains.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
}

import java.util.Properties
import java.io.FileInputStream

val localProperties = Properties()
val localPropertiesFile = rootProject.file("local.properties")
if (localPropertiesFile.exists()) {
    localProperties.load(FileInputStream(localPropertiesFile))
}

val defaultSupabaseUrl = "https://jyiqhqxjoahlxflaated.supabase.co"
val defaultSupabaseKey = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6Imp5aXFocXhqb2FobHhmbGFhdGVkIiwicm9sZSI6ImFub24iLCJpYXQiOjE3ODU0MzIwMjksImV4cCI6MjEwMTAwODAyOX0.Fel6E89P2A-RIhoHv3LYra1ycPxPZG8nYiet8IhDRzg"

val supabaseUrl = localProperties.getProperty("SUPABASE_URL")?.takeIf { it.isNotBlank() } ?: defaultSupabaseUrl
val supabaseKey = localProperties.getProperty("SUPABASE_KEY")?.takeIf { it.isNotBlank() } ?: defaultSupabaseKey

android {
    namespace = "com.livetracker.controller.core"
    compileSdk = 35

    buildFeatures {
        buildConfig = true
    }

    defaultConfig {
        minSdk = 26

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("proguard-rules.pro")

        buildConfigField("String", "SUPABASE_URL", "\"$supabaseUrl\"")
        buildConfigField("String", "SUPABASE_KEY", "\"$supabaseKey\"")
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

    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.security.crypto)
    implementation(libs.kotlinx.serialization.json)

    // Supabase & Realtime exposed as api for app and core
    api(platform(libs.supabase.bom))
    api(libs.supabase.postgrest)
    api(libs.supabase.realtime)
    api(libs.supabase.auth)
    api(libs.ktor.client.okhttp)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
