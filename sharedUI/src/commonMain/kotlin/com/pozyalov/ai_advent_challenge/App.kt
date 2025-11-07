package com.pozyalov.ai_advent_challenge

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import com.arkivanov.decompose.DefaultComponentContext
import com.arkivanov.decompose.extensions.compose.subscribeAsState
import com.arkivanov.essenty.backhandler.BackDispatcher
import com.arkivanov.essenty.instancekeeper.InstanceKeeperDispatcher
import com.arkivanov.essenty.lifecycle.LifecycleRegistry
import com.arkivanov.essenty.lifecycle.destroy
import com.arkivanov.essenty.lifecycle.resume
import com.arkivanov.essenty.lifecycle.stop
import com.arkivanov.essenty.statekeeper.StateKeeperDispatcher
import com.pozyalov.ai_advent_challenge.chat.ui.ChatScreen
import com.pozyalov.ai_advent_challenge.features.chatlist.ui.ChatListScreen
import com.pozyalov.ai_advent_challenge.root.RootComponent
import com.pozyalov.ai_advent_challenge.root.RootComponentImpl
import com.pozyalov.ai_advent_challenge.theme.AppTheme
import com.pozyalov.ai_advent_challenge.theme.LocalThemeIsDark
import org.jetbrains.compose.ui.tooling.preview.Preview

@Preview
@Composable
fun App(
    onThemeChanged: @Composable (isDark: Boolean) -> Unit = {}
) = AppTheme(onThemeChanged) {
    val rootComponent = rememberAppRootComponent()
    RootContent(rootComponent)
}

@Composable
private fun RootContent(component: RootComponent) {
    val childStack by component.childStack.subscribeAsState()
    val themeState = LocalThemeIsDark.current
    when (val child = childStack.active.instance) {
        is RootComponent.Child.ChatList -> ChatListScreen(child.component)
        is RootComponent.Child.Chat -> {
            val isDark by themeState
            ChatScreen(
                component = child.component,
                isDark = isDark,
                onToggleTheme = { themeState.value = !themeState.value }
            )
        }
    }
}

@Composable
private fun rememberAppRootComponent(): RootComponent {

    val lifecycle: LifecycleRegistry = remember { LifecycleRegistry() }
    val stateKeeper: StateKeeperDispatcher = remember { StateKeeperDispatcher() }
    val instanceKeeper: InstanceKeeperDispatcher = remember { InstanceKeeperDispatcher() }
    val backDispatcher: BackDispatcher = remember { BackDispatcher() }

    val componentContext = remember {
        DefaultComponentContext(
            lifecycle = lifecycle,
            stateKeeper = stateKeeper,
            instanceKeeper = instanceKeeper,
            backHandler = backDispatcher
        )
    }

    DisposableEffect(componentContext) {
        lifecycle.resume()
        onDispose {
            lifecycle.stop()
            lifecycle.destroy()
        }
    }

    return remember(componentContext) {
        RootComponentImpl(
            componentContext = componentContext
        )
    }
}
