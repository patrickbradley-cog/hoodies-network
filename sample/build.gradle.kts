import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// AGP and KGP are already on the build classpath via the root project, so they are applied without a version.
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.gap.hoodies_network.sample"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.gap.hoodies_network.sample"
        minSdk = 28
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    sourceSets {
        getByName("main") {
            java.srcDir("src/main/kotlin")
        }
        getByName("androidTest") {
            java.srcDir("src/androidTest/kotlin")
        }
    }

    buildFeatures {
        compose = true
    }

    lint {
        sarifReport = true
        // compileSdk/targetSdk follow the library's SDK level, which is pinned by the migration foundation.
        disable += setOf("OldTargetApi", "GradleDependency")
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":Hoodies-Network"))
    implementation(libs.androidx.core.ktx)
    // HttpCall.getHeaders() exposes com.sun.net.httpserver.Headers from a jar the library keeps on its
    // implementation classpath; the classes are packaged in the library AAR, so compile against them only.
    compileOnly(files("../Hoodies-Network/libs/http-2.2.1.jar"))

    val composeBom = platform(libs.androidx.compose.bom)
    implementation(composeBom)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.kotlinx.coroutines.android)

    androidTestImplementation(composeBom)
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
}
