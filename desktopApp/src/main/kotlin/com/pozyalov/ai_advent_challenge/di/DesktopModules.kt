package com.pozyalov.ai_advent_challenge.di

import com.pozyalov.ai_advent_challenge.core.database.factory.createChatDatabase
import com.pozyalov.ai_advent_challenge.network.mcp.MultiTaskToolClient
import com.pozyalov.ai_advent_challenge.network.mcp.TaskToolClient
import com.pozyalov.ai_advent_challenge.network.mcp.ToolClientEntry
import com.pozyalov.ai_advent_challenge.network.mcp.ToolSelector
import com.pozyalov.ai_advent_challenge.network.mcp.WeatherTaskToolClient
import com.pozyalov.ai_advent_challenge.network.mcp.WorldBankTaskToolClient
import org.koin.core.module.Module
import org.koin.dsl.module
import java.io.File
import org.koin.dsl.binds

fun desktopAppModule(): Module = module {
    single {
        createChatDatabase(
            filePath = desktopChatDatabasePath(),
            fallbackToDestructiveMigration = true
        )
    }
    single {
        MultiTaskToolClient(
            entries = listOf(
                ToolClientEntry(
                    id = "worldbank",
                    title = "World Bank (страны)",
                    description = "Список стран по данным World Bank API.",
                    client = WorldBankTaskToolClient(),
                    defaultEnabled = true
                ),
                ToolClientEntry(
                    id = "weather",
                    title = "Weather.gov (прогноз)",
                    description = "Краткий прогноз погоды для указанных координат.",
                    client = WeatherTaskToolClient(),
                    defaultEnabled = true
                )
            )
        )
    } binds arrayOf(TaskToolClient::class, ToolSelector::class)
}

private fun desktopChatDatabasePath(): String {
    val userHome = System.getProperty("user.home").orEmpty().ifBlank { "." }
    val directory = File(userHome, ".ai_advent")
    if (!directory.exists()) {
        directory.mkdirs()
    }
    return File(directory, "chat_history.db").absolutePath
}
