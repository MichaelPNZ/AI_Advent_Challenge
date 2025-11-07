import org.gradle.declarative.dsl.schema.FqName.Empty.packageName

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.android.kmp.library)
    alias(libs.plugins.kotlinx.serialization)
}

kotlin {
    android {
        namespace = "com.pozyalov.ai_advent_challenge.features.chat"
        compileSdk = 36
        minSdk = 23
        androidResources.enable = true
    }

    jvm()
    iosX64()
    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        commonMain.dependencies {
            implementation(compose.runtime)
            implementation(compose.ui)
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation(compose.components.resources)
            implementation(compose.materialIconsExtended)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.kotlinx.datetime)
            implementation(libs.koin.core)
            implementation(libs.napier)
            implementation(libs.openai.client)
            implementation(libs.decompose)
            implementation(libs.decompose.compose)
            implementation(project(":core:network"))
            implementation(project(":core:database"))
        }

        androidMain.dependencies {
            implementation(compose.uiTooling)
        }

        jvmMain.dependencies {
            implementation(compose.desktop.currentOs)
        }

        iosMain.dependencies { }
    }
}