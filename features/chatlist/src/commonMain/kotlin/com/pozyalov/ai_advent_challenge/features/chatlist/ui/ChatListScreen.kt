@file:OptIn(ExperimentalTime::class, ExperimentalMaterial3Api::class)

package com.pozyalov.ai_advent_challenge.features.chatlist.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pozyalov.ai_advent_challenge.features.chatlist.component.ChatListComponent
import com.pozyalov.ai_advent_challenge.features.chatlist.model.ChatListItem
import kotlinx.datetime.TimeZone
import kotlinx.datetime.number
import kotlinx.datetime.toLocalDateTime
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

@Composable
fun ChatListScreen(component: ChatListComponent, modifier: Modifier = Modifier) {
    val model by component.model.collectAsState()
    val pendingDeletion = model.pendingDeletionId
    Scaffold(
        modifier = modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing),
        bottomBar = {
            Surface(
                tonalElevation = 2.dp,
                color = Color.Transparent,
            ) {
                Button(
                    onClick = component::onNewChat,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp)
                ) {
                    Text(text = "Новый чат")
                }
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            if (model.chats.isEmpty()) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    tonalElevation = 2.dp
                ) {
                    Text(
                        text = if (model.isLoading) "Загрузка..." else "Чатов пока нет",
                        modifier = Modifier.padding(24.dp),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(model.chats, key = { it.id }) { chat ->
                        val dismissState = rememberSwipeToDismissBoxState(
                            confirmValueChange = { value ->
                                if (value == SwipeToDismissBoxValue.EndToStart) {
                                    component.onDeleteRequested(chat.id)
                                }
                                false
                            }
                        )
                        SwipeToDismissBox(
                            state = dismissState,
                            backgroundContent = { DismissBackground() },
                            enableDismissFromStartToEnd = false,
                            enableDismissFromEndToStart = true,
                            content = {
                                ChatListRow(
                                    item = chat,
                                    onClick = { component.onChatSelected(chat.id) }
                                )
                            }
                        )
                    }
                }
            }
        }
    }
    if (pendingDeletion != null) {
        AlertDialog(
            onDismissRequest = component::onDeleteCanceled,
            title = { Text("Удалить чат?") },
            text = { Text("История чата будет удалена без возможности восстановления.") },
            confirmButton = {
                Button(onClick = component::onDeleteConfirmed) {
                    Text("Удалить")
                }
            },
            dismissButton = {
                Button(onClick = component::onDeleteCanceled) {
                    Text("Отмена")
                }
            }
        )
    }
}

@Composable
private fun ChatListRow(
    item: ChatListItem,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick),
        tonalElevation = 2.dp,
        shape = MaterialTheme.shapes.medium
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = item.title.ifBlank { "Без названия" },
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = true)
                )
                Text(
                    text = item.updatedAt.formatDateTime(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.End,
                    maxLines = 1,
                    modifier = Modifier.widthIn(min = 0.dp)
                )
            }
            if (!item.preview.isNullOrBlank()) {
                Text(
                    text = item.preview,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

@Composable
private fun DismissBackground() {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.errorContainer,
        modifier = Modifier.fillMaxSize()
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(end = 24.dp),
            contentAlignment = Alignment.CenterEnd
        ) {
            Icon(
                imageVector = Icons.Filled.Delete,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onErrorContainer
            )
        }
    }
}

private fun Instant.formatDateTime(): String {
    val local = kotlinx.datetime.Instant.fromEpochMilliseconds(this.toEpochMilliseconds())
        .toLocalDateTime(TimeZone.currentSystemDefault())
    val date = "${local.day.twoDigits()}.${local.month.number.twoDigits()}.${local.year}"
    val time = "${local.hour.twoDigits()}:${local.minute.twoDigits()}"
    return "$date $time"
}

private fun Int.twoDigits(): String = if (this < 10) "0$this" else "$this"
