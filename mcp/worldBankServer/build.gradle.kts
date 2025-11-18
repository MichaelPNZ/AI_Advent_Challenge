plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}

application {
    mainClass.set("com.pozyalov.ai_advent_challenge.mcp.worldbank.WorldBankServerKt")
}

dependencies {
    implementation(libs.mcpKotlinServer)
    implementation(libs.mcpKotlinCore)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.cio)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.serialization.json)
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.sse)
}

kotlin {
    jvmToolchain(17)
}
