import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.compose.hot.reload)
}

dependencies {
    implementation(project(":sharedUI"))
    implementation(project(":features:chat"))
    implementation(project(":core:database"))
    implementation(project(":core:network"))
    implementation(compose.ui)
    implementation(libs.kotlinx.datetime)
    implementation(libs.koin.core)
    implementation(libs.room.runtime)
    implementation(libs.sqlite.bundled)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.openai.client)
    implementation("org.apache.pdfbox:pdfbox:3.0.2")
    implementation("org.apache.poi:poi-ooxml:5.2.5")
}

compose.desktop {
    application {
        mainClass = "MainKt"

        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            packageName = "AI_Advent_Challenge_#4"
            packageVersion = "1.0.0"

            linux {
                iconFile.set(project.file("appIcons/LinuxIcon.png"))
            }
            windows {
                iconFile.set(project.file("appIcons/WindowsIcon.ico"))
            }
            macOS {
                iconFile.set(project.file("appIcons/MacosIcon.icns"))
                bundleID = "com.pozyalov.ai_advent_challenge.desktopApp"
            }
        }
    }
}

afterEvaluate {
    tasks.named("run") {
        dependsOn(":mcp:worldBankServer:installDist")
        dependsOn(":mcp:weatherServer:installDist")
        dependsOn(":mcp:reminderServer:installDist")
        dependsOn(":mcp:chatSummaryServer:installDist")
        dependsOn(":mcp:docPipelineServer:installDist")
    }
}
