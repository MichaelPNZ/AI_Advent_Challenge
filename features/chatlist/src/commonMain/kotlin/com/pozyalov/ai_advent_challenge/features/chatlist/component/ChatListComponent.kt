@file:OptIn(ExperimentalTime::class)

package com.pozyalov.ai_advent_challenge.features.chatlist.component

import com.arkivanov.decompose.ComponentContext
import com.arkivanov.essenty.lifecycle.doOnDestroy
import com.pozyalov.ai_advent_challenge.core.database.chat.data.ChatThreadDataSource
import com.pozyalov.ai_advent_challenge.core.database.chat.data.StoredChatThread
import com.pozyalov.ai_advent_challenge.features.chatlist.model.ChatListItem
import com.pozyalov.ai_advent_challenge.chat.pipeline.EmbeddingIndexExecutor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.plus
import kotlin.time.ExperimentalTime

interface ChatListComponent {
    val model: StateFlow<Model>
    fun onChatSelected(id: Long)
    fun onNewChat()
    fun onDeleteRequested(id: Long)
    fun onDeleteConfirmed()
    fun onDeleteCanceled()
    fun onIndexDirectory(path: String?, outputPath: String?)

    data class Model(
        val chats: List<ChatListItem>,
        val isLoading: Boolean,
        val pendingDeletionId: Long? = null,
        val isIndexing: Boolean = false,
        val lastIndexPath: String? = null,
        val indexError: String? = null
    )
}

class ChatListComponentImpl(
    componentContext: ComponentContext,
    private val threads: ChatThreadDataSource,
    private val indexExecutor: EmbeddingIndexExecutor,
    private val onOpenChat: (Long) -> Unit
) : ChatListComponent, ComponentContext by componentContext {

    private val scope: CoroutineScope = CoroutineScope(SupervisorJob()) + Dispatchers.Main.immediate

    private val _model = MutableStateFlow(
        ChatListComponent.Model(
            chats = emptyList(),
            isLoading = true
        )
    )
    override val model: StateFlow<ChatListComponent.Model> = _model.asStateFlow()

    init {
        observeThreads()
        lifecycle.doOnDestroy { scope.cancel() }
    }

    override fun onChatSelected(id: Long) {
        onOpenChat(id)
    }

    override fun onNewChat() {
        scope.launch {
            val thread = threads.createThread()
            onOpenChat(thread.id)
        }
    }

    override fun onDeleteRequested(id: Long) {
        _model.update { it.copy(pendingDeletionId = id) }
    }

    override fun onDeleteConfirmed() {
        val id = _model.value.pendingDeletionId ?: return
        scope.launch(Dispatchers.Default) {
            runCatching { threads.deleteThread(id) }
            _model.update { it.copy(pendingDeletionId = null) }
        }
    }

    override fun onDeleteCanceled() {
        _model.update { it.copy(pendingDeletionId = null) }
    }

    override fun onIndexDirectory(path: String?, outputPath: String?) {
        if (!indexExecutor.isAvailable || path.isNullOrBlank() || _model.value.isIndexing) return
        _model.update { it.copy(isIndexing = true, indexError = null) }
        scope.launch(Dispatchers.Default) {
            val result = indexExecutor.buildIndex(path, outputPath)
            _model.update {
                it.copy(
                    isIndexing = false,
                    lastIndexPath = result.getOrNull(),
                    indexError = result.exceptionOrNull()?.message
                )
            }
        }
    }

    private fun observeThreads() {
        threads.observeThreads()
            .onEach { stored ->
                _model.update {
                    it.copy(
                        chats = stored.map { item -> item.toItem() },
                        isLoading = false
                    )
                }
            }
            .catch {
                _model.update { current -> current.copy(isLoading = false) }
            }
            .launchIn(scope)
    }
}

private fun StoredChatThread.toItem(): ChatListItem =
    ChatListItem(
        id = id,
        title = title,
        preview = lastMessagePreview,
        updatedAt = updatedAt
    )
