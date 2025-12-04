@file:OptIn(ExperimentalTime::class)

package com.pozyalov.ai_advent_challenge.chat.component

import com.aallam.openai.api.model.ModelId
import com.arkivanov.decompose.ComponentContext
import com.arkivanov.essenty.backhandler.BackCallback
import com.arkivanov.essenty.lifecycle.doOnDestroy
import com.pozyalov.ai_advent_challenge.chat.data.ChatHistoryDataSource
import com.pozyalov.ai_advent_challenge.chat.data.ChatHistoryExporter
import com.pozyalov.ai_advent_challenge.chat.data.memory.AgentMemoryEntry
import com.pozyalov.ai_advent_challenge.chat.data.memory.AgentMemoryStore
import com.pozyalov.ai_advent_challenge.chat.pipeline.DocPipelineExecutor
import com.pozyalov.ai_advent_challenge.chat.pipeline.DocPipelineExecutor.Match as DocMatch
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
import com.pozyalov.ai_advent_challenge.chat.pipeline.TripBriefingExecutor
import com.pozyalov.ai_advent_challenge.chat.ui.formatForDisplay
import com.pozyalov.ai_advent_challenge.chat.util.chatLog
import com.pozyalov.ai_advent_challenge.core.database.chat.data.ChatThreadDataSource
import com.pozyalov.ai_advent_challenge.network.api.ContextLengthExceededException
import com.pozyalov.ai_advent_challenge.network.api.RateLimitExceededException
import com.pozyalov.ai_advent_challenge.chat.model.ChatToolOption
import com.pozyalov.ai_advent_challenge.network.mcp.ToolSelector
import com.pozyalov.ai_advent_challenge.network.mcp.ToolSelectorOption
import com.pozyalov.ai_advent_challenge.chat.pipeline.RagComparisonResult
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
import kotlinx.coroutines.withTimeoutOrNull
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
    fun onToolToggle(toolId: String, enabled: Boolean)
    fun onExportChat(directoryPath: String?)
    fun onPipelineSearch(query: String)
    fun onRunPipeline(matchIndex: Int)
    fun onRunTripBriefing(locationQuery: String, departureDate: String?)
    fun onConfirmTripBriefing(saveToFile: Boolean)
    fun onCancelTripBriefing()
    fun onBuildEmbeddingIndex(directoryPath: String?)
    fun onToggleRag(enabled: Boolean)
    fun onRunRagComparison()
    fun onRagThresholdChange(value: Double)
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
        val contextLimitInput: String,
        val availableTools: List<ChatToolOption>,
        val memories: List<AgentMemoryEntry>,
        val exportState: ExportState,
        val isPipelineAvailable: Boolean,
        val isPipelineSearching: Boolean,
        val isPipelineRunning: Boolean,
        val pipelineMatches: List<DocMatch>,
        val pipelineError: String?,
        val isTripAvailable: Boolean,
        val isTripRunning: Boolean,
        val tripError: String?,
        val tripPrepared: TripBriefingExecutor.PreparedTrip?,
        val isIndexingEmbeddings: Boolean,
        val lastIndexPath: String?,
        val indexError: String?,
        val isRagAvailable: Boolean,
        val isRagEnabled: Boolean,
        val isRagRunning: Boolean,
        val ragThreshold: Double
    )

    data class ExportState(
        val isInProgress: Boolean = false,
        val lastExportPath: String? = null,
        val error: String? = null
    )
}

class ChatComponentImpl(
    componentContext: ComponentContext,
    chatAgent: ChatAgent,
    chatHistory: ChatHistoryDataSource,
    chatThreads: ChatThreadDataSource,
    private val historyExporter: ChatHistoryExporter,
    private val toolSelector: ToolSelector,
    agentMemoryStore: AgentMemoryStore,
    private val docPipelineExecutor: DocPipelineExecutor,
    private val tripBriefingExecutor: TripBriefingExecutor,
    private val ragExecutor: com.pozyalov.ai_advent_challenge.chat.pipeline.RagComparisonExecutor,
    private val embeddingIndexExecutor: com.pozyalov.ai_advent_challenge.chat.pipeline.EmbeddingIndexExecutor,
    private val threadId: Long,
    private val onClose: () -> Unit
) : ChatComponent, ComponentContext by componentContext {

    private val coroutineScope: CoroutineScope = CoroutineScope(SupervisorJob()) + Dispatchers.Main.immediate
    private val agent = chatAgent
    private val historyStorage = chatHistory
    private val threadStorage = chatThreads
    private val memoryStore = agentMemoryStore
    private val pendingTrip = MutableStateFlow<TripBriefingExecutor.PreparedTrip?>(null)
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
            contextLimitInput = "",
            availableTools = emptyList(),
            memories = emptyList(),
            exportState = ChatComponent.ExportState(),
            isPipelineAvailable = docPipelineExecutor.isAvailable,
            isPipelineSearching = false,
            isPipelineRunning = false,
            pipelineMatches = emptyList(),
            pipelineError = null,
            isTripAvailable = tripBriefingExecutor.isAvailable,
            isTripRunning = false,
            tripError = null,
            tripPrepared = null,
            isIndexingEmbeddings = false,
            lastIndexPath = null,
            indexError = null,
            isRagAvailable = ragExecutor.isAvailable,
            isRagEnabled = false,
            isRagRunning = false,
            ragThreshold = 0.25
        )
    )
    override val model: StateFlow<ChatComponent.Model> = _model.asStateFlow()

    private val backCallback = BackCallback { onClose() }

    init {
        ensureThreadExists()
        collectHistory()
        collectMemories()
        observeToolSelector()
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

    override fun onToolToggle(toolId: String, enabled: Boolean) {
        toolSelector.setToolEnabled(toolId, enabled)
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

    private fun observeToolSelector() {
        coroutineScope.launch {
            toolSelector.state.collect { state ->
                _model.update { current ->
                    current.copy(
                        availableTools = state.options.map { it.toChatToolOption() },
                        isPipelineAvailable = docPipelineExecutor.isAvailable
                    )
                }
            }
        }
    }

    private fun ToolSelectorOption.toChatToolOption(): ChatToolOption =
        ChatToolOption(
            id = id,
            title = title,
            description = description?.takeIf { it.isNotBlank() },
            toolNames = toolNames,
            isAvailable = isAvailable,
            isEnabled = isEnabled
        )

    override fun onContextLimitInputChange(value: String) {
        val filtered = value.filter { it.isDigit() }
        _model.update { current -> current.copy(contextLimitInput = filtered) }
    }

    override fun onExportChat(directoryPath: String?) {
        coroutineScope.launch(Dispatchers.Default) {
            val currentMessages = _model.value.messages
            val currentMemories = _model.value.memories
            _model.update { state ->
                state.copy(exportState = state.exportState.copy(isInProgress = true, error = null))
            }
            runCatching {
                historyExporter.export(
                    threadId = threadId,
                    messages = currentMessages,
                    memories = currentMemories,
                    directoryOverride = directoryPath
                )
            }.onSuccess { result ->
                _model.update { state ->
                    state.copy(
                        exportState = ChatComponent.ExportState(
                            isInProgress = false,
                            lastExportPath = result.path,
                            error = null
                        )
                    )
                }
            }.onFailure { failure ->
                val message = failure.message ?: "Не удалось сохранить чат"
                _model.update { state ->
                    state.copy(
                        exportState = ChatComponent.ExportState(
                            isInProgress = false,
                            lastExportPath = null,
                            error = message
                        )
                    )
                }
                chatLog("Failed to export chat: $message")
            }
        }
    }

    override fun onPipelineSearch(query: String) {
        if (!docPipelineExecutor.isAvailable) return
        val cleaned = query.trim()
        if (cleaned.isEmpty()) return
        coroutineScope.launch(Dispatchers.Default) {
            _model.update { it.copy(isPipelineSearching = true, pipelineError = null) }
            val result = docPipelineExecutor.search(cleaned)
            _model.update {
                result.fold(
                    onSuccess = { matches ->
                        it.copy(
                            pipelineMatches = matches,
                            isPipelineSearching = false,
                            pipelineError = if (matches.isEmpty()) "Совпадения не найдены" else null
                        )
                    },
                    onFailure = { error ->
                        it.copy(
                            pipelineMatches = emptyList(),
                            isPipelineSearching = false,
                            pipelineError = error.message ?: "Не удалось выполнить поиск"
                        )
                    }
                )
            }
        }
    }

    override fun onRunPipeline(matchIndex: Int) {
        if (!docPipelineExecutor.isAvailable || _model.value.isPipelineRunning) return
        val match = _model.value.pipelineMatches.getOrNull(matchIndex) ?: return
        val thinkingMessage = ConversationMessage(
            threadId = threadId,
            author = MessageAuthor.Agent,
            text = "Готовлю сводку по \"${match.fileName}\"",
            modelId = "doc-pipeline",
            isThinking = true
        )
        emitAgentMessage(thinkingMessage, finalizeSending = false)
        _model.update { it.copy(isPipelineRunning = true, pipelineError = null) }
        coroutineScope.launch(Dispatchers.Default) {
            try {
                val result = docPipelineExecutor.summarize(match)
                val messageText = result.fold(
                    onSuccess = { payload ->
                        buildString {
                            appendLine("Doc pipeline summary")
                            appendLine()
                            appendLine(payload.summary)
                            payload.savedPath?.let {
                                appendLine()
                                appendLine("Сохранено в: $it")
                            }
                        }.trim()
                    },
                    onFailure = { failure ->
                        "Doc pipeline завершился ошибкой: ${failure.message ?: "неизвестная ошибка"}"
                    }
                )
                val finalMessage = thinkingMessage.copy(
                    text = messageText,
                    isThinking = false
                )
                replaceAgentMessage(finalMessage, finalizeSending = false)
            } catch (error: Throwable) {
                val fallback = thinkingMessage.copy(
                    text = "Doc pipeline завершился ошибкой: ${error.message ?: "неизвестная ошибка"}",
                    isThinking = false
                )
                replaceAgentMessage(fallback, finalizeSending = false)
            } finally {
                _model.update { it.copy(isPipelineRunning = false) }
            }
        }
    }

    override fun onRunTripBriefing(locationQuery: String, departureDate: String?) {
        if (!tripBriefingExecutor.isAvailable || _model.value.isTripRunning) return
        val trimmed = locationQuery.trim()
        if (trimmed.isEmpty()) return
        pendingTrip.value = null
        _model.update { it.copy(tripPrepared = null, tripError = null) }
        val thinking = ConversationMessage(
            threadId = threadId,
            author = MessageAuthor.Agent,
            text = "Готовлю сводку для поездки…",
            modelId = "trip-briefing",
            isThinking = true
        )
        emitAgentMessage(thinking, finalizeSending = false)
        _model.update { it.copy(isTripRunning = true, tripError = null) }

        coroutineScope.launch(Dispatchers.Default) {
            try {
                val result = tripBriefingExecutor.prepareBriefing(trimmed, departureDate)
                val messageText = result.fold(
                    onSuccess = { payload ->
                        buildString {
                            appendLine("Предварительная сводка для поездки — ${payload.locationName}")
                            appendLine()
                            appendLine("Погода:")
                            appendLine(payload.forecast.trim())
                            appendLine()
                            appendLine("Предлагаю добавить напоминания:")
                            payload.tasks.forEachIndexed { idx, task ->
                                appendLine("${idx + 1}. ${task.title}" + task.dueDate?.let { " — до $it" }.orEmpty())
                            }
                            appendLine()
                            appendLine("Подтвердите, чтобы добавить напоминания в задачи.")
                        }.trim()
                    },
                    onFailure = { failure ->
                        failure.message ?: "Не удалось подготовить сводку."
                    }
                )
                val finalMessage = thinking.copy(
                    text = messageText,
                    isThinking = false
                )
                replaceAgentMessage(finalMessage, finalizeSending = false)
                result.onSuccess { prepared ->
                    pendingTrip.value = prepared
                    _model.update { it.copy(tripPrepared = prepared) }
                }
            } catch (error: Throwable) {
                val fallback = thinking.copy(
                    text = "Не удалось подготовить сводку: ${error.message ?: "неизвестная ошибка"}",
                    isThinking = false
                )
                replaceAgentMessage(fallback, finalizeSending = false)
                pendingTrip.value = null
                _model.update { it.copy(tripError = error.message, tripPrepared = null) }
            } finally {
                _model.update { it.copy(isTripRunning = false) }
            }
        }
    }

    override fun onConfirmTripBriefing(saveToFile: Boolean) {
        val prepared = pendingTrip.value ?: return
        if (!tripBriefingExecutor.isAvailable || _model.value.isTripRunning) return
        val thinking = ConversationMessage(
            threadId = threadId,
            author = MessageAuthor.Agent,
            text = "Добавляю напоминания по поездке…",
            modelId = "trip-briefing",
            isThinking = true
        )
        emitAgentMessage(thinking, finalizeSending = false)
        _model.update { it.copy(isTripRunning = true, tripError = null) }
        coroutineScope.launch(Dispatchers.Default) {
            try {
                val result = tripBriefingExecutor.confirmTasks(prepared, saveToFile)
                val messageText = result.fold(
                    onSuccess = { tasks ->
                        buildString {
                            appendLine("Сводка для поездки — ${prepared.locationName}")
                            appendLine()
                            appendLine("Погода:")
                            appendLine(prepared.forecast.trim())
                            appendLine()
                            appendLine("Добавленные напоминания:")
                            if (tasks.createdTasks.isEmpty()) {
                                appendLine("— Не удалось добавить задачи.")
                            } else {
                                tasks.createdTasks.forEach { appendLine("• $it") }
                            }
                            appendLine()
                            appendLine(tasks.summaryText.trim())
                            tasks.savedPath?.let {
                                appendLine()
                                appendLine("Сохранено в: $it")
                            }
                        }.trim()
                    },
                    onFailure = { failure ->
                        failure.message ?: "Не удалось добавить напоминания."
                    }
                )
                val finalMessage = thinking.copy(
                    text = messageText,
                    isThinking = false
                )
                replaceAgentMessage(finalMessage, finalizeSending = false)
                pendingTrip.value = null
                _model.update { it.copy(tripPrepared = null, tripError = null) }
            } catch (error: Throwable) {
                val fallback = thinking.copy(
                    text = "Не удалось добавить напоминания: ${error.message ?: "неизвестная ошибка"}",
                    isThinking = false
                )
                replaceAgentMessage(fallback, finalizeSending = false)
                _model.update { it.copy(tripError = error.message) }
            } finally {
                _model.update { it.copy(isTripRunning = false) }
            }
        }
    }

    override fun onCancelTripBriefing() {
        pendingTrip.value = null
        _model.update { it.copy(tripPrepared = null, tripError = null) }
    }

    override fun onBuildEmbeddingIndex(directoryPath: String?) {
        if (!embeddingIndexExecutor.isAvailable || directoryPath.isNullOrBlank() || _model.value.isIndexingEmbeddings) return
        _model.update { it.copy(isIndexingEmbeddings = true, indexError = null) }
        coroutineScope.launch(Dispatchers.Default) {
            val result = embeddingIndexExecutor.buildIndex(directoryPath)
            val messageText = result.fold(
                onSuccess = { path ->
                    "Индексирование завершено.\nИндекс: $path"
                },
                onFailure = { error ->
                    _model.update { it.copy(indexError = error.message) }
                    "Не удалось построить индекс: ${error.message ?: "неизвестная ошибка"}"
                }
            )
            historyStorage.saveMessage(
                ConversationMessage(
                    threadId = threadId,
                    author = MessageAuthor.Agent,
                    text = messageText,
                    modelId = "embedding-index"
                )
            )
            _model.update { it.copy(isIndexingEmbeddings = false, lastIndexPath = result.getOrNull()) }
        }
    }

    override fun onToggleRag(enabled: Boolean) {
        _model.update { it.copy(isRagEnabled = enabled) }
    }

    override fun onRunRagComparison() {
        if (!_model.value.isRagAvailable || _model.value.isRagRunning || _model.value.input.isBlank()) return
        val question = _model.value.input
        val userMessage = ConversationMessage(
            threadId = threadId,
            author = MessageAuthor.User,
            text = question
        )
        emitAgentMessage(userMessage, finalizeSending = false)
        val thinking = ConversationMessage(
            threadId = threadId,
            author = MessageAuthor.Agent,
            text = "RAG: думаю...",
            modelId = "rag-compare",
            isThinking = true
        )
        emitAgentMessage(thinking, finalizeSending = false)
        _model.update { it.copy(input = "") }
        runRagComparison(question, thinking)
    }

    override fun onRagThresholdChange(value: Double) {
        _model.update { it.copy(ragThreshold = value.coerceIn(0.0, 1.0)) }
    }

    private fun runRagComparison(question: String, thinkingMessage: ConversationMessage? = null) {
        _model.update { it.copy(isRagRunning = true) }
        coroutineScope.launch(Dispatchers.Default) {
            try {
                val result: Result<RagComparisonResult> = withTimeoutOrNull(RAG_TIMEOUT_MS) {
                    ragExecutor.compare(
                        question,
                        topK = 3,
                        minScore = _model.value.ragThreshold
                    )
                } ?: Result.failure(IllegalStateException("RAG сравнение заняло больше ${RAG_TIMEOUT_MS / 1000} секунд и было отменено."))

                val text = result.fold(
                    onSuccess = { rag ->
                        buildString {
                            appendLine("Режим RAG включен. Ответ по индексу:")
                            appendLine(rag.withRagFiltered.ifBlank { rag.withRag })
                            if (rag.contextChunks.isNotEmpty()) {
                                appendLine()
                                appendLine("Использованные чанки:")
                                val chunks = rag.filteredChunks.ifEmpty { rag.contextChunks }
                                chunks.forEachIndexed { idx, chunk ->
                                    appendLine("${idx + 1}. ${chunk.file}")
                                }
                            }
                        }.trim()
                    },
                    onFailure = { error ->
                        "RAG сравнение завершилось ошибкой: ${error.message ?: "неизвестная ошибка"}"
                    }
                )
                val finalMessage = thinkingMessage?.copy(
                    text = text,
                    isThinking = false
                ) ?: ConversationMessage(
                    threadId = threadId,
                    author = MessageAuthor.Agent,
                    text = text,
                    modelId = "rag-compare"
                )
                if (thinkingMessage != null) {
                    replaceAgentMessage(finalMessage, finalizeSending = true)
                } else {
                    historyStorage.saveMessage(finalMessage)
                }
            } catch (error: Throwable) {
                val fallbackText = "RAG сравнение завершилось ошибкой: ${error.message ?: "неизвестная ошибка"}"
                val fallback = thinkingMessage?.copy(
                    text = fallbackText,
                    isThinking = false
                ) ?: ConversationMessage(
                    threadId = threadId,
                    author = MessageAuthor.Agent,
                    text = fallbackText,
                    modelId = "rag-compare"
                )
                if (thinkingMessage != null) {
                    replaceAgentMessage(fallback, finalizeSending = true)
                } else {
                    historyStorage.saveMessage(fallback)
                }
            } finally {
                _model.update { it.copy(isRagRunning = false, isSending = false) }
            }
        }
    }

    override fun onSend() {
        val inputText = _model.value.input
        if (inputText.trimStart().startsWith("/help", ignoreCase = true) && _model.value.isRagAvailable && !_model.value.isRagRunning) {
            val question = inputText.removePrefix("/help").trim().ifBlank { "Что есть в проекте?" }
            _model.update { it.copy(input = "", isSending = true) }
            val userMessage = ConversationMessage(
                threadId = threadId,
                author = MessageAuthor.User,
                text = "/help $question"
            )
            emitAgentMessage(userMessage, finalizeSending = false)
            val thinking = ConversationMessage(
                threadId = threadId,
                author = MessageAuthor.Agent,
                text = "RAG: думаю...",
                modelId = "rag-compare",
                isThinking = true
            )
            emitAgentMessage(thinking, finalizeSending = false)
            runRagComparison(question, thinking)
            return
        }
        if (_model.value.isRagEnabled && _model.value.isRagAvailable) {
            if (_model.value.input.isNotBlank() && !_model.value.isRagRunning) {
                val question = _model.value.input
                _model.update { it.copy(input = "", isSending = true) }
                val userMessage = ConversationMessage(
                    threadId = threadId,
                    author = MessageAuthor.User,
                    text = question
                )
                emitAgentMessage(userMessage, finalizeSending = false)
                val thinking = ConversationMessage(
                    threadId = threadId,
                    author = MessageAuthor.Agent,
                    text = "RAG: думаю...",
                    modelId = "rag-compare",
                    isThinking = true
                )
                emitAgentMessage(thinking, finalizeSending = false)
                runRagComparison(question, thinking)
            }
            return
        }
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
                for ((index, target) in targets.withIndex()) {
                    val isLastTarget = index == targets.lastIndex
                    chatLog("Sending request via ${target.option.id} (temperature=${target.temperature}), isLastTarget=$isLastTarget")
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
                    chatLog("agent.reply() returned, processing result...")
                    val responseMessage = result.fold(
                        onSuccess = { reply ->
                            chatLog("Reply successful, creating ConversationMessage...")
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

                    chatLog("Creating finalMessage from responseMessage...")
                    val finalMessage = responseMessage.copy(
                        id = thinkingMessage.id,
                        isThinking = false
                    )
                    val shouldStop = finalMessage.error != null
                    val shouldFinalize = shouldStop || isLastTarget
                    chatLog("Calling replaceAgentMessage, shouldStop=$shouldStop, isLastTarget=$isLastTarget, shouldFinalize=$shouldFinalize")
                    replaceAgentMessage(
                        message = finalMessage,
                        finalizeSending = shouldFinalize
                    )
                    chatLog("replaceAgentMessage completed")
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

    private fun collectMemories() {
        coroutineScope.launch {
            memoryStore.observe(threadId)
                .catch { chatLog("Failed to observe memory: ${it.message.orEmpty()}") }
                .collect { memories ->
                    _model.update { it.copy(memories = memories) }
                }
        }
    }

    private fun persistMessage(message: ConversationMessage) {
        coroutineScope.launch(Dispatchers.Default) {
            chatLog("persistMessage: saving message id=${message.id} to storage...")
            runCatching {
                historyStorage.saveMessage(message)
                chatLog("persistMessage: message saved successfully")
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
        chatLog("replaceAgentMessage: messageId=${message.id}, finalizeSending=$finalizeSending, text.length=${message.text.length}")
        _model.update { state ->
            val updatedMessages = state.messages.map { current ->
                if (current.id == message.id) message else current
            }
            chatLog("Updated ${updatedMessages.size} messages, isSending will be ${if (finalizeSending) false else state.isSending}")
            state.copy(
                messages = updatedMessages,
                isConfigured = agent.isConfigured,
                isSending = if (finalizeSending) false else state.isSending
            )
        }
        chatLog("Calling persistMessage...")
        persistMessage(message)
        chatLog("persistMessage completed")
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
        private const val RAG_TIMEOUT_MS = 120_000L
    }
}
