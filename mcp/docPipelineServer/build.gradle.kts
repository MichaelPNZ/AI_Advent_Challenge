plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlinx.serialization)
    application
}

application {
    mainClass.set("com.pozyalov.ai_advent_challenge.mcp.docpipeline.DocPipelineServerKt")
}

dependencies {
    implementation(libs.mcpKotlinServer)
    implementation(libs.mcpKotlinCore)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.datetime)
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.sse)
    implementation(libs.slf4j.nop)
}

kotlin {
    jvmToolchain(17)
}
