@file:OptIn(ExperimentalTime::class)

package com.pozyalov.ai_advent_challenge.chat.component

import com.aallam.openai.api.model.ModelId
import com.arkivanov.decompose.ComponentContext
import com.arkivanov.essenty.backhandler.BackCallback
import com.arkivanov.essenty.lifecycle.doOnDestroy
import com.pozyalov.ai_advent_challenge.chat.data.ChatHistoryDataSource
import com.pozyalov.ai_advent_challenge.chat.model.ChatRoleCatalog
import com.pozyalov.ai_advent_challenge.chat.model.ChatRoleOption
import com.pozyalov.ai_advent_challenge.chat.model.ContextLimitCatalog
import com.pozyalov.ai_advent_challenge.chat.model.ContextLimitOption
import com.pozyalov.ai_advent_challenge.chat.model.LlmModelCatalog
import com.pozyalov.ai_advent_challenge.chat.model.LlmModelOption
import com.pozyalov.ai_advent_challenge.chat.model.ReasoningCatalog
import com.pozyalov.ai_advent_challenge.chat.model.ReasoningOption
import com.pozyalov.ai_advent_challenge.chat.model.TemperatureCatalog
import com.pozyalov.ai_advent_challenge.chat.model.TemperatureOption
import com.pozyalov.ai_advent_challenge.chat.ui.formatForDisplay
import com.pozyalov.ai_advent_challenge.chat.util.chatLog
import com.pozyalov.ai_advent_challenge.core.database.chat.data.ChatThreadDataSource
import com.pozyalov.ai_advent_challenge.network.api.ContextLengthExceededException
import com.pozyalov.ai_advent_challenge.network.api.RateLimitExceededException
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
    fun onComparisonModelSelected(modelId: String?)
    fun onRoleSelected(roleId: String)
    fun onTemperatureSelected(optionId: String)
    fun onReasoningSelected(optionId: String)
    fun onContextLimitSelected(optionId: String)
    fun onContextLimitInputChange(value: String)
    fun onBack()

    data class Model(
        val messages: List<ConversationMessage>,
        val input: String,
        val isSending: Boolean,
        val isConfigured: Boolean,
        val availableModels: List<LlmModelOption>,
        val selectedModelId: String,
        val comparisonModelId: String?,
        val availableRoles: List<ChatRoleOption>,
        val selectedRoleId: String,
        val availableTemperatures: List<TemperatureOption>,
        val selectedTemperatureId: String,
        val availableReasoning: List<ReasoningOption>,
        val selectedReasoningId: String,
        val isTemperatureLocked: Boolean,
        val lockedTemperatureValue: Double?,
        val availableContextLimits: List<ContextLimitOption>,
        val selectedContextLimitId: String,
        val contextLimitInput: String
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
    private val defaultModelOption: LlmModelOption = modelOptions.first { it.id == LlmModelCatalog.DefaultModelId }
    private val roleOptions: List<ChatRoleOption> = ChatRoleCatalog.roles
    private val temperatureOptions: List<TemperatureOption> = TemperatureCatalog.options
    private val reasoningOptions: List<ReasoningOption> = ReasoningCatalog.options
    private val contextLimitOptions: List<ContextLimitOption> = ContextLimitCatalog.options

    private val _model = MutableStateFlow(
        ChatComponent.Model(
            messages = emptyList(),
            input = "",
            isSending = false,
            isConfigured = agent.isConfigured,
            availableModels = modelOptions,
            selectedModelId = LlmModelCatalog.DefaultModelId,
            comparisonModelId = null,
            availableRoles = roleOptions,
            selectedRoleId = ChatRoleCatalog.defaultRole.id,
            availableTemperatures = temperatureOptions,
            selectedTemperatureId = TemperatureCatalog.default.id,
            availableReasoning = reasoningOptions,
            selectedReasoningId = ReasoningCatalog.default.id,
            isTemperatureLocked = defaultModelOption.temperatureLocked,
            lockedTemperatureValue = defaultModelOption.temperature.takeIf { defaultModelOption.temperatureLocked },
            availableContextLimits = contextLimitOptions,
            selectedContextLimitId = ContextLimitCatalog.default.id,
            contextLimitInput = ""
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
        val selected = modelOptions.firstOrNull { it.id == modelId } ?: return
        _model.update { current ->
            current.copy(
                selectedModelId = selected.id,
                comparisonModelId = current.comparisonModelId?.takeUnless { it == selected.id },
                isConfigured = agent.isConfigured,
                isTemperatureLocked = selected.temperatureLocked,
                lockedTemperatureValue = selected.temperature.takeIf { selected.temperatureLocked }
            )
        }
    }

    override fun onComparisonModelSelected(modelId: String?) {
        _model.update { current ->
            val normalized = modelId
                ?.takeIf { candidate ->
                    candidate != current.selectedModelId && modelOptions.any { it.id == candidate }
                }
            current.copy(comparisonModelId = normalized)
        }
    }

    override fun onRoleSelected(roleId: String) {
        if (roleOptions.none { it.id == roleId }) return
        _model.update { current -> current.copy(selectedRoleId = roleId) }
    }

    override fun onTemperatureSelected(optionId: String) {
        if (_model.value.isTemperatureLocked) return
        if (temperatureOptions.none { it.id == optionId }) return
        _model.update { current -> current.copy(selectedTemperatureId = optionId) }
    }

    override fun onReasoningSelected(optionId: String) {
        if (reasoningOptions.none { it.id == optionId }) return
        _model.update { current -> current.copy(selectedReasoningId = optionId) }
    }

    override fun onContextLimitSelected(optionId: String) {
        if (contextLimitOptions.none { it.id == optionId }) return
        _model.update { current ->
            val option = contextLimitOptions.first { it.id == optionId }
            current.copy(
                selectedContextLimitId = optionId,
                contextLimitInput = current.contextLimitInput.takeIf { option.requiresCustomValue } ?: ""
            )
        }
    }

    override fun onContextLimitInputChange(value: String) {
        val filtered = value.filter { it.isDigit() }
        _model.update { current -> current.copy(contextLimitInput = filtered) }
    }

    override fun onSend() {
        data class TargetRequest(
            val option: LlmModelOption,
            val temperature: Double
        )

        var shouldSend = false
        var requestHistory: List<ConversationMessage>? = null
        var userMessageToPersist: ConversationMessage? = null
        var errorMessageToPersist: ConversationMessage? = null
        var selectedRoleOption: ChatRoleOption? = null
        var activeTemperatureValue: Double? = null
        var selectedReasoningOption: ReasoningOption? = null
        var primaryModelOption: LlmModelOption? = null
        var comparisonModelId: String? = null
        var resolvedContextPaddingTokens: Int? = null

        _model.update { state ->
            val trimmed = state.input.trim()
            if (trimmed.isEmpty() || state.isSending) {
                return@update state
            }

            val roleOption = roleOptions.firstOrNull { it.id == state.selectedRoleId } ?: ChatRoleCatalog.defaultRole
            val selectedModel = modelOptions.firstOrNull { it.id == state.selectedModelId } ?: defaultModelOption
            val temperatureValue = if (selectedModel.temperatureLocked) {
                selectedModel.temperature
            } else {
                temperatureOptions.firstOrNull { it.id == state.selectedTemperatureId }?.value
                    ?: TemperatureCatalog.default.value
            }
            val reasoningOption = reasoningOptions.firstOrNull { it.id == state.selectedReasoningId }
                ?: ReasoningCatalog.default
            val contextOption = contextLimitOptions.firstOrNull { it.id == state.selectedContextLimitId }
                ?: ContextLimitCatalog.default
            selectedRoleOption = roleOption
            activeTemperatureValue = temperatureValue
            selectedReasoningOption = reasoningOption
            primaryModelOption = selectedModel
            comparisonModelId = state.comparisonModelId
                ?.takeIf { candidate -> candidate != selectedModel.id && modelOptions.any { it.id == candidate } }
            resolvedContextPaddingTokens = when {
                contextOption.requiresCustomValue -> state.contextLimitInput.toIntOrNull()
                    ?.coerceIn(0, MAX_PADDING_TOKENS)
                else -> contextOption.paddingTokens
            }

            val userMessage = ConversationMessage(
                threadId = threadId,
                author = MessageAuthor.User,
                text = trimmed,
                roleId = roleOption.id,
                temperature = temperatureValue
            )
            userMessageToPersist = userMessage
            val updatedMessages = state.messages + userMessage

            if (!agent.isConfigured) {
                val failureMessage = ConversationMessage(
                    threadId = threadId,
                    author = MessageAuthor.Agent,
                    text = "",
                    error = ConversationError.MissingApiKey,
                    roleId = roleOption.id,
                    temperature = temperatureValue
                )
                errorMessageToPersist = failureMessage
                return@update state.copy(
                    messages = updatedMessages + failureMessage,
                    input = "",
                    isSending = false,
                    isConfigured = agent.isConfigured
                )
            }

            val sanitizedHistory = updatedMessages.filterNot {
                (it.error != null && it.author == MessageAuthor.Agent) || it.isArchived
            }

            requestHistory = sanitizedHistory
            shouldSend = true

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
        val rolePrompt = selectedRoleOption ?: ChatRoleCatalog.defaultRole
        val reasoningOption = selectedReasoningOption ?: ReasoningCatalog.default
        val baseTemperature = activeTemperatureValue ?: TemperatureCatalog.default.value
        val primary = primaryModelOption ?: defaultModelOption
        val comparison = comparisonModelId?.let { id -> modelOptions.firstOrNull { it.id == id } }

        chatLog("Prepared request history=${history.size}, paddingTokens=${resolvedContextPaddingTokens ?: 0}")

        val targets = buildList {
            add(
                TargetRequest(
                    option = primary,
                    temperature = if (primary.temperatureLocked) primary.temperature else baseTemperature
                )
            )
            comparison?.let { option ->
                add(
                    TargetRequest(
                        option = option,
                        temperature = if (option.temperatureLocked) option.temperature else baseTemperature
                    )
                )
            }
        }

        coroutineScope.launch {
            val effectiveHistory = runCatching {
                appendPadding(history, resolvedContextPaddingTokens)
            }.getOrElse { error ->
                chatLog("Failed to apply padding: ${error.message.orEmpty()}")
                val failureMessage = ConversationMessage(
                    threadId = threadId,
                    author = MessageAuthor.Agent,
                    text = error.displayMessage("Не удалось подготовить запрос: "),
                    error = ConversationError.Failure,
                    roleId = rolePrompt.id,
                    temperature = baseTemperature
                )
                emitAgentMessage(failureMessage, finalizeSending = true)
                return@launch
            }
            chatLog("Effective request history size=${effectiveHistory.size}")
            try {
                for (target in targets) {
                    chatLog("Sending request via ${target.option.id} (temperature=${target.temperature})")
                    val modelId = ModelId(target.option.id)
                    val thinkingMessage = ConversationMessage(
                        threadId = threadId,
                        author = MessageAuthor.Agent,
                        text = "Думаю",
                        roleId = rolePrompt.id,
                        temperature = target.temperature,
                        modelId = modelId.id,
                        isThinking = true
                    )
                    emitAgentMessage(thinkingMessage, finalizeSending = false)

                    val result = agent.reply(
                        history = effectiveHistory,
                        model = modelId,
                        temperature = target.temperature,
                        systemPrompt = rolePrompt.systemPrompt,
                        reasoningEffort = reasoningOption.effort
                    )
                    val responseMessage = result.fold(
                        onSuccess = { reply ->
                            ConversationMessage(
                                threadId = threadId,
                                author = MessageAuthor.Agent,
                                text = reply.structured.formatForDisplay(),
                                structured = reply.structured,
                                modelId = modelId.id,
                                roleId = rolePrompt.id,
                                temperature = target.temperature,
                                responseTimeMillis = reply.metrics.durationMillis,
                                promptTokens = reply.metrics.promptTokens,
                                completionTokens = reply.metrics.completionTokens,
                                totalTokens = reply.metrics.totalTokens,
                                costUsd = reply.metrics.costUsd
                            )
                        },
                        onFailure = { throwable ->
                            chatLog("Request via ${modelId.id} failed: ${throwable.message.orEmpty()}")
                            val errorType = when (throwable) {
                                is ContextLengthExceededException -> ConversationError.ContextLimit
                                is RateLimitExceededException -> ConversationError.RateLimit
                                else -> ConversationError.Failure
                            }
                            ConversationMessage(
                                threadId = threadId,
                                author = MessageAuthor.Agent,
                                text = throwable.displayMessage(),
                                error = errorType,
                                modelId = modelId.id,
                                roleId = rolePrompt.id,
                                temperature = target.temperature
                            )
                        }
                    )

                    val finalMessage = responseMessage.copy(
                        id = thinkingMessage.id,
                        isThinking = false
                    )
                    val shouldStop = finalMessage.error != null
                    replaceAgentMessage(
                        message = finalMessage,
                        finalizeSending = shouldStop
                    )
                    if (shouldStop) {
                        break
                    }
                }
            } finally {
                _model.update { state ->
                    state.copy(
                        isSending = false,
                        isConfigured = agent.isConfigured
                    )
                }
            }
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
                if (message.isThinking) {
                    return@runCatching
                }
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

    private fun emitAgentMessage(
        message: ConversationMessage,
        finalizeSending: Boolean
    ) {
        _model.update { state ->
            state.copy(
                messages = state.messages + message,
                isConfigured = agent.isConfigured,
                isSending = if (finalizeSending) false else state.isSending
            )
        }
        persistMessage(message)
    }

    private fun replaceAgentMessage(
        message: ConversationMessage,
        finalizeSending: Boolean
    ) {
        _model.update { state ->
            val updatedMessages = state.messages.map { current ->
                if (current.id == message.id) message else current
            }
            state.copy(
                messages = updatedMessages,
                isConfigured = agent.isConfigured,
                isSending = if (finalizeSending) false else state.isSending
            )
        }
        persistMessage(message)
    }

    private fun Throwable?.displayMessage(prefix: String? = null): String {
        val text = when (this) {
            null -> null
            else -> this.message?.takeIf { it.isNotBlank() } ?: this.cause?.message
        }
        val base = text?.takeIf { it.isNotBlank() } ?: "Произошла ошибка при обращении к модели."
        return prefix?.let { it + base } ?: base
    }

    private fun appendPadding(
        history: List<ConversationMessage>,
        paddingTokens: Int?
    ): List<ConversationMessage> {
        val tokens = paddingTokens?.coerceIn(0, MAX_PADDING_TOKENS) ?: return history
        if (tokens <= 0) return history
        val paddingMessage = createPaddingMessage(tokens) ?: return history
        return history + paddingMessage
    }

    private fun createPaddingMessage(tokens: Int): ConversationMessage? {
        if (tokens <= 0) return null
        val desiredChars = (tokens.toLong() * APPROX_CHARS_PER_TOKEN)
            .coerceAtMost(MAX_PADDING_CHARS.toLong())
            .toInt()
        if (desiredChars <= 0) return null
        val chunk = "CONTEXT_PADDING_SEQUENCE_0123456789 "
        val builder = StringBuilder(desiredChars)
        while (builder.length < desiredChars) {
            builder.append(chunk)
        }
        if (builder.length > desiredChars) {
            builder.setLength(desiredChars)
        }
        return ConversationMessage(
            threadId = threadId,
            author = MessageAuthor.User,
            text = builder.toString()
        )
    }

    private companion object {
        private const val APPROX_CHARS_PER_TOKEN = 4
        private const val MAX_PADDING_CHARS = 2_000_000
        private const val MAX_PADDING_TOKENS = 50_000
    }
}
