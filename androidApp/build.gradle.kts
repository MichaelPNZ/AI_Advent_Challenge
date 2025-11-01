plugins {
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.pozyalov.ai_advent_challenge"
    compileSdk = 36

    defaultConfig {
        minSdk = 23
        targetSdk = 36

        applicationId = "com.pozyalov.ai_advent_challenge.androidApp"
        versionCode = 1
        versionName = "1.0.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
}

dependencies {
    implementation(project(":sharedUI"))
    implementation(libs.androidx.activityCompose)
    implementation(libs.koin.core)
}
