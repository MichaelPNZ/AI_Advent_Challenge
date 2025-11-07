@file:OptIn(ExperimentalTime::class)

package com.pozyalov.ai_advent_challenge.chat.component

import com.aallam.openai.api.model.ModelId
import com.arkivanov.decompose.ComponentContext
import com.arkivanov.essenty.backhandler.BackCallback
import com.arkivanov.essenty.lifecycle.doOnDestroy
import com.pozyalov.ai_advent_challenge.chat.data.ChatHistoryDataSource
import com.pozyalov.ai_advent_challenge.chat.model.LlmModelCatalog
import com.pozyalov.ai_advent_challenge.chat.model.LlmModelOption
import com.pozyalov.ai_advent_challenge.chat.ui.formatForDisplay
import com.pozyalov.ai_advent_challenge.chat.util.chatLog
import com.pozyalov.ai_advent_challenge.core.database.chat.data.ChatThreadDataSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.plus
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

interface ChatComponent {
    val model: StateFlow<Model>
    fun onInputChange(text: String)
    fun onSend()
    fun onModelSelected(modelId: String)
    fun onBack()

    data class Model(
        val messages: List<ConversationMessage>,
        val input: String,
        val isSending: Boolean,
        val isConfigured: Boolean,
        val availableModels: List<LlmModelOption>,
        val selectedModelId: String
    )
}

class ChatComponentImpl(
    componentContext: ComponentContext,
    chatAgent: ChatAgent,
    chatHistory: ChatHistoryDataSource,
    chatThreads: ChatThreadDataSource,
    private val threadId: Long,
    private val onClose: () -> Unit
) : ChatComponent, ComponentContext by componentContext {

    private val coroutineScope: CoroutineScope = CoroutineScope(SupervisorJob()) + Dispatchers.Main.immediate
    private val agent = chatAgent
    private val historyStorage = chatHistory
    private val threadStorage = chatThreads
    private val modelOptions: List<LlmModelOption> = LlmModelCatalog.models

    private val _model = MutableStateFlow(
        ChatComponent.Model(
            messages = emptyList(),
            input = "",
            isSending = false,
            isConfigured = agent.isConfigured,
            availableModels = modelOptions,
            selectedModelId = LlmModelCatalog.DefaultModelId
        )
    )
    override val model: StateFlow<ChatComponent.Model> = _model.asStateFlow()

    private val backCallback = BackCallback { onClose() }

    init {
        ensureThreadExists()
        collectHistory()
        backHandler.register(backCallback)
        lifecycle.doOnDestroy {
            coroutineScope.cancel()
            agent.close()
            backHandler.unregister(backCallback)
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

    override fun onModelSelected(modelId: String) {
        if (modelOptions.none { it.id == modelId }) return
        _model.update { current ->
            current.copy(
                selectedModelId = modelId,
                isConfigured = agent.isConfigured
            )
        }
    }

    override fun onSend() {
        var shouldSend = false
        var requestHistory: List<ConversationMessage>? = null
        var targetModelId: String? = null
        var userMessageToPersist: ConversationMessage? = null
        var errorMessageToPersist: ConversationMessage? = null

        _model.update { state ->
            val trimmed = state.input.trim()
            if (trimmed.isEmpty() || state.isSending) {
                return@update state
            }

            val userMessage = ConversationMessage(
                threadId = threadId,
                author = MessageAuthor.User,
                text = trimmed
            )
            userMessageToPersist = userMessage
            val updatedMessages = state.messages + userMessage

            if (!agent.isConfigured) {
                val failureMessage = ConversationMessage(
                    threadId = threadId,
                    author = MessageAuthor.Agent,
                    text = "",
                    error = ConversationError.MissingApiKey
                )
                errorMessageToPersist = failureMessage
                return@update state.copy(
                    messages = updatedMessages + failureMessage,
                    input = "",
                    isSending = false,
                    isConfigured = agent.isConfigured
                )
            }

            requestHistory = updatedMessages.filterNot { it.error != null && it.author == MessageAuthor.Agent }
            shouldSend = true
            targetModelId = state.selectedModelId

            state.copy(
                messages = updatedMessages,
                input = "",
                isSending = true,
                isConfigured = agent.isConfigured
            )
        }

        userMessageToPersist?.let { persistMessage(it) }
        errorMessageToPersist?.let { persistMessage(it) }

        if (!shouldSend) return

        val history = requestHistory ?: return
        val selectedModelId = targetModelId ?: LlmModelCatalog.DefaultModelId
        val selectedModel = modelOptions.firstOrNull { it.id == selectedModelId }
            ?: LlmModelCatalog.firstOrDefault(selectedModelId)
        val modelId = ModelId(selectedModel.id)
        val temperature = selectedModel.temperature

        coroutineScope.launch {
            val result = agent.reply(history, modelId, temperature)
            val responseMessage = result.fold(
                onSuccess = { reply ->
                    ConversationMessage(
                        threadId = threadId,
                        author = MessageAuthor.Agent,
                        text = reply.formatForDisplay(),
                        structured = reply,
                        modelId = modelId.id
                    )
                },
                onFailure = { throwable ->
                    ConversationMessage(
                        threadId = threadId,
                        author = MessageAuthor.Agent,
                        text = throwable.message.orEmpty(),
                        error = ConversationError.Failure,
                        modelId = modelId.id
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

            persistMessage(responseMessage)
        }
    }

    private fun collectHistory() {
        coroutineScope.launch {
            historyStorage.observeHistory(threadId)
                .catch { chatLog("Failed to observe history: ${it.message.orEmpty()}") }
                .collect { history ->
                    _model.update { it.copy(messages = history) }
                }
        }
    }

    private fun persistMessage(message: ConversationMessage) {
        coroutineScope.launch(Dispatchers.Default) {
            runCatching {
                historyStorage.saveMessage(message)
                val preview = message.text.take(160).takeIf { it.isNotBlank() }
                val titleUpdate = when {
                    message.author == MessageAuthor.User && message.text.isNotBlank() -> message.text.take(60)
                    else -> null
                }
                if (titleUpdate != null || preview != null) {
                    threadStorage.updateThread(
                        threadId = threadId,
                        title = titleUpdate,
                        lastMessagePreview = preview,
                        updatedAt = message.timestamp
                    )
                } else {
                    threadStorage.updateThread(
                        threadId = threadId,
                        updatedAt = message.timestamp
                    )
                }
            }
                .onFailure { chatLog("Failed to persist chat message: ${it.message.orEmpty()}") }
        }
    }

    private fun ensureThreadExists() {
        coroutineScope.launch(Dispatchers.Default) {
            runCatching {
                val existing = threadStorage.getThread(threadId)
                if (existing == null) {
                    threadStorage.updateThread(
                        threadId = threadId,
                        title = "Новый чат",
                        updatedAt = Clock.System.now()
                    )
                }
            }
        }
    }

    override fun onBack() {
        onClose()
    }
}
