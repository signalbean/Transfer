plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)

    kotlin("plugin.serialization") version "1.8.0"
}

android {
    namespace = "com.matanh.transfer"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.matanh.transfer"
        minSdk = 29
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
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
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
    buildFeatures {
        viewBinding = true
    }

    signingConfigs {
        create("release") {
            storeFile = file("keystore/transfer-key.jks")
            storePassword = System.getenv("KEYSTORE_PASSWORD")
            keyAlias = System.getenv("KEY_ALIAS")
            keyPassword = System.getenv("KEY_PASSWORD")
        }
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
        }
    }

}

dependencies {

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.navigation.fragment.ktx)
    implementation(libs.androidx.navigation.ui.ktx)

//    implementation(libs.nanohttpd)
    implementation(libs.androidx.preference.ktx)
    implementation(libs.ktor.server.core.jvm)
    implementation(libs.ktor.server.cio.jvm)

    // Ktor Features
    implementation(libs.ktor.server.content.negotiation.jvm)
    implementation(libs.ktor.serialization.kotlinx.json.jvm) // For JSON
    implementation(libs.ktor.server.html.builder.jvm) // Optional: if you build HTML with Ktor DSL
    implementation(libs.ktor.server.host.common.jvm) // For serving static resources
    implementation(libs.ktor.server.status.pages.jvm)
    implementation(libs.ktor.server.auth.jvm) // For Basic Auth
    implementation(libs.ktor.server.cors.jvm)
    implementation(libs.ktor.server.call.logging.jvm) // Optional logging

    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)

    implementation(libs.nanohttpd)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.swiperefreshlayout)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}