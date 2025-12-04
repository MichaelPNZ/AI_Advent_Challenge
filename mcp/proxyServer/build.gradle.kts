plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlinx.serialization)
    application
}

application {
    mainClass.set("com.pozyalov.ai_advent_challenge.mcp.proxy.MainKt")
}

dependencies {
    // Используем McpToolInspector из core:network
    implementation(project(":core:network"))

    // Ktor Server
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.sse)
    implementation("io.ktor:ktor-server-netty:${libs.versions.ktor.get()}")
    implementation("io.ktor:ktor-server-content-negotiation:${libs.versions.ktor.get()}")
    implementation("io.ktor:ktor-server-cors:${libs.versions.ktor.get()}")
    implementation("io.ktor:ktor-server-call-logging:${libs.versions.ktor.get()}")
    implementation(libs.ktor.serialization.json)

    // Kotlinx
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)

    // MCP SDK
    implementation(libs.mcpKotlinClient)
    implementation(libs.mcpKotlinCore)

    // Logging
    implementation(libs.slf4j.nop)

    // OpenAI client (для Tool типа)
    implementation(libs.openai.client)
}

kotlin {
    jvmToolchain(21)
}
