package com.pozyalov.ai_advent_challenge.root

import com.arkivanov.decompose.ComponentContext
import com.arkivanov.decompose.router.stack.ChildStack
import com.arkivanov.decompose.router.stack.StackNavigation
import com.arkivanov.decompose.router.stack.push
import com.arkivanov.decompose.router.stack.pop
import com.arkivanov.decompose.router.stack.childStack
import com.arkivanov.decompose.value.Value
import com.pozyalov.ai_advent_challenge.chat.component.ChatComponent
import com.pozyalov.ai_advent_challenge.chat.di.ChatComponentFactory
import com.pozyalov.ai_advent_challenge.features.chatlist.component.ChatListComponent
import com.pozyalov.ai_advent_challenge.features.chatlist.di.ChatListComponentFactory
import kotlinx.serialization.Serializable
import org.koin.core.component.KoinComponent
import org.koin.core.component.get

interface RootComponent {
    val childStack: Value<ChildStack<Config, Child>>

    sealed class Child {
        data class ChatList(val component: ChatListComponent) : Child()
        data class Chat(val component: ChatComponent) : Child()
    }

    @Serializable
    sealed class Config {
        @Serializable
        data object ChatList : Config()

        @Serializable
        data class Chat(val threadId: Long) : Config()
    }
}

class RootComponentImpl(
    componentContext: ComponentContext
) : RootComponent, ComponentContext by componentContext, KoinComponent {

    private val navigation = StackNavigation<RootComponent.Config>()

    override val childStack: Value<ChildStack<RootComponent.Config, RootComponent.Child>> =
        childStack(
            source = navigation,
            initialConfiguration = RootComponent.Config.ChatList,
            handleBackButton = true,
            serializer = RootComponent.Config.serializer(),
            childFactory = ::createChild
        )

    private fun createChild(
        config: RootComponent.Config,
        componentContext: ComponentContext
    ): RootComponent.Child =
        when (config) {
            RootComponent.Config.ChatList -> RootComponent.Child.ChatList(
                get<ChatListComponentFactory>().create(
                    componentContext = componentContext,
                    onOpenChat = { threadId -> navigation.push(RootComponent.Config.Chat(threadId)) }
                )
            )

            is RootComponent.Config.Chat -> RootComponent.Child.Chat(
                get<ChatComponentFactory>().create(
                    componentContext = componentContext,
                    threadId = config.threadId,
                    onClose = { navigation.pop() }
                )
            )
        }
}
