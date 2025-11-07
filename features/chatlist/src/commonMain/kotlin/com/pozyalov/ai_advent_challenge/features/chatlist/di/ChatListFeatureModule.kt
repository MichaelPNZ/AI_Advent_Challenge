package com.pozyalov.ai_advent_challenge.features.chatlist.di

import com.arkivanov.decompose.ComponentContext
import com.pozyalov.ai_advent_challenge.core.database.chat.data.ChatThreadDataSource
import com.pozyalov.ai_advent_challenge.features.chatlist.component.ChatListComponent
import com.pozyalov.ai_advent_challenge.features.chatlist.component.ChatListComponentImpl
import org.koin.core.module.Module
import org.koin.dsl.module

val chatListFeatureModule: Module = module {
    factory { ChatListComponentFactory(threads = get()) }
}

class ChatListComponentFactory(
    private val threads: ChatThreadDataSource
) {
    fun create(
        componentContext: ComponentContext,
        onOpenChat: (Long) -> Unit
    ): ChatListComponent = ChatListComponentImpl(
        componentContext = componentContext,
        threads = threads,
        onOpenChat = onOpenChat
    )
}
