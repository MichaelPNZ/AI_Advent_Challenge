package com.pozyalov.ai_advent_challenge.chat

import com.arkivanov.decompose.ComponentContext
import com.arkivanov.essenty.lifecycle.doOnDestroy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.plus

interface ChatComponent {
    val model: StateFlow<Model>
    fun onInputChange(text: String)
    fun onSend()

    data class Model(
        val messages: List<ConversationMessage>,
        val input: String,
        val isSending: Boolean,
        val isConfigured: Boolean
    )
}

class ChatComponentImpl(
    componentContext: ComponentContext,
    chatAgent: ChatAgent
) : ChatComponent, ComponentContext by componentContext {

    private val coroutineScope: CoroutineScope = CoroutineScope(SupervisorJob()) + Dispatchers.Main.immediate
    private val agent = chatAgent

    private val _model = MutableStateFlow(
        ChatComponent.Model(
            messages = emptyList(),
            input = "",
            isSending = false,
            isConfigured = agent.isConfigured
        )
    )
    override val model: StateFlow<ChatComponent.Model> = _model.asStateFlow()

    init {
        lifecycle.doOnDestroy {
            coroutineScope.cancel()
            agent.close()
        }
    }

    override fun onInputChange(text: String) {
        _model.update {
            it.copy(
                input = text,
                isConfigured = agent.isConfigured
            )
        }
    }

    override fun onSend() {
        var shouldSend = false
        var requestHistory: List<ConversationMessage>? = null

        _model.update { state ->
            val trimmed = state.input.trim()
            if (trimmed.isEmpty() || state.isSending) {
                return@update state
            }

            val userMessage = ConversationMessage(
                author = MessageAuthor.User,
                text = trimmed
            )
            val updatedMessages = state.messages + userMessage

            if (!agent.isConfigured) {
                return@update state.copy(
                    messages = updatedMessages + ConversationMessage(
                        author = MessageAuthor.Agent,
                        text = "",
                        error = ConversationError.MissingApiKey
                    ),
                    input = "",
                    isSending = false,
                    isConfigured = agent.isConfigured
                )
            }

            requestHistory = updatedMessages.filterNot { it.error != null && it.author == MessageAuthor.Agent }
            shouldSend = true

            state.copy(
                messages = updatedMessages,
                input = "",
                isSending = true,
                isConfigured = agent.isConfigured
            )
        }

        if (!shouldSend) return

        val history = requestHistory ?: return

        coroutineScope.launch {
            val result = agent.reply(history)
            val responseMessage = result.fold(
                onSuccess = { reply ->
                    ConversationMessage(
                        author = MessageAuthor.Agent,
                        text = reply
                    )
                },
                onFailure = { throwable ->
                    ConversationMessage(
                        author = MessageAuthor.Agent,
                        text = throwable.message.orEmpty(),
                        error = ConversationError.Failure
                    )
                }
            )

            _model.update { state ->
                state.copy(
                    messages = state.messages + responseMessage,
                    isSending = false,
                    isConfigured = agent.isConfigured
                )
            }
        }
    }
}
