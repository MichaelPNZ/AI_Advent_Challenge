package com.pozyalov.ai_advent_challenge.network.di

import com.pozyalov.ai_advent_challenge.network.api.AiApi
import org.koin.core.module.Module
import org.koin.core.qualifier.named
import org.koin.dsl.module

private const val OPEN_AI_KEY_QUALIFIER = "openAiKey"

fun networkModule(apiKey: String): Module = module {
    single(named(OPEN_AI_KEY_QUALIFIER)) { apiKey }
    factory { AiApi(apiKey = get(named(OPEN_AI_KEY_QUALIFIER))) }
}
