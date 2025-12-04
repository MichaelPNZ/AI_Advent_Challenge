package com.pozyalov.ai_advent_challenge.di

import android.content.Context
import com.pozyalov.ai_advent_challenge.core.database.factory.createChatDatabase
import com.pozyalov.ai_advent_challenge.network.mcp.TaskToolClient
import com.pozyalov.ai_advent_challenge.network.mcp.TaskToolClientFactory
import com.pozyalov.ai_advent_challenge.network.mcp.McpClientConfig
import com.pozyalov.ai_advent_challenge.network.mcp.ToolSelector
import com.pozyalov.ai_advent_challenge.network.mcp.ToolSelectorStub
import io.ktor.client.*
import io.ktor.client.engine.okhttp.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.koin.core.module.Module
import org.koin.dsl.module
import org.koin.dsl.binds

fun androidAppModule(appContext: Context): Module = module {
    single<Context> { appContext }
    single {
        createChatDatabase(
            androidContext = appContext,
            name = "chat_history.db",
            fallbackToDestructiveMigration = true
        )
    }

    // HttpClient для MCP HTTP режима
    single {
        HttpClient(OkHttp) {
            install(ContentNegotiation) {
                json(Json {
                    ignoreUnknownKeys = true
                    explicitNulls = false
                })
            }
        }
    }

    // TaskToolClient через MCP HTTP Proxy
    single {
        // Для Android эмулятора: 10.0.2.2:8080
        // Для реального устройства: укажите IP вашего компьютера
        val proxyUrl = System.getProperty("mcp.proxy.url")
            ?: "http://10.0.2.2:8080"

        val config = McpClientConfig(McpClientConfig.Mode.HTTP_PROXY, proxyUrl)

        println("🤖 Android MCP Mode: HTTP_PROXY")
        println("🌐 MCP Proxy URL: $proxyUrl")
        println("💡 Убедитесь, что proxy запущен: ./mcp/proxyServer/run-proxy-server.sh")

        runBlocking {
            TaskToolClientFactory.create(config, get())
        }
    } binds arrayOf(TaskToolClient::class, ToolSelector::class)
}