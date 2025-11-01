package com.pozyalov.ai_advent_challenge.root

import com.arkivanov.decompose.ComponentContext
import com.arkivanov.decompose.router.stack.ChildStack
import com.arkivanov.decompose.router.stack.StackNavigation
import com.arkivanov.decompose.router.stack.childStack
import com.arkivanov.decompose.value.Value
import com.pozyalov.ai_advent_challenge.chat.ChatAgent
import com.pozyalov.ai_advent_challenge.chat.ChatComponent
import com.pozyalov.ai_advent_challenge.chat.ChatComponentImpl
import kotlinx.serialization.Serializable
import org.koin.core.component.KoinComponent
import org.koin.core.component.get

interface RootComponent {
    val childStack: Value<ChildStack<Config, Child>>

    sealed class Child {
        data class Chat(val component: ChatComponent) : Child()
    }

    @Serializable
    sealed class Config {
        @Serializable
        data object Chat : Config()
    }
}

class RootComponentImpl(
    componentContext: ComponentContext
) : RootComponent, ComponentContext by componentContext, KoinComponent {

    private val navigation = StackNavigation<RootComponent.Config>()

    override val childStack: Value<ChildStack<RootComponent.Config, RootComponent.Child>> =
        childStack(
            source = navigation,
            initialConfiguration = RootComponent.Config.Chat,
            handleBackButton = true,
            serializer = RootComponent.Config.serializer(),
            childFactory = ::createChild
        )

    private fun createChild(
        config: RootComponent.Config,
        componentContext: ComponentContext
    ): RootComponent.Child =
        when (config) {
            RootComponent.Config.Chat -> RootComponent.Child.Chat(
                ChatComponentImpl(
                    componentContext = componentContext,
                    chatAgent = get<ChatAgent>()
                )
            )
        }
}
