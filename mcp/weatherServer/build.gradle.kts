plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlinx.serialization)
    application
}

application {
    mainClass.set("com.pozyalov.ai_advent_challenge.mcp.weather.MainKt")
}

dependencies {
    implementation(libs.mcpKotlinServer)
    implementation(libs.mcpKotlinCore)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.cio)
    implementation(libs.ktor.client.logging)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.serialization.json)
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.sse)
    implementation(libs.slf4j.nop)
}

kotlin {
    jvmToolchain(17)
}
