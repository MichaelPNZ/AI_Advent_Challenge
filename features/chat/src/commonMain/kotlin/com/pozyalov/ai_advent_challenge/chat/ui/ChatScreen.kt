@file:OptIn(ExperimentalTime::class)

package com.pozyalov.ai_advent_challenge.chat.ui

import ai_advent_challenge.features.chat.generated.resources.Res
import ai_advent_challenge.features.chat.generated.resources.chat_api_key_missing
import ai_advent_challenge.features.chat.generated.resources.chat_empty_state
import ai_advent_challenge.features.chat.generated.resources.chat_error_generic
import ai_advent_challenge.features.chat.generated.resources.chat_input_placeholder
import ai_advent_challenge.features.chat.generated.resources.chat_send
import ai_advent_challenge.features.chat.generated.resources.chat_title
import ai_advent_challenge.features.chat.generated.resources.ic_dark_mode
import ai_advent_challenge.features.chat.generated.resources.ic_light_mode
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Button
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.pozyalov.ai_advent_challenge.chat.component.ChatComponent
import com.pozyalov.ai_advent_challenge.chat.component.ConversationError
import com.pozyalov.ai_advent_challenge.chat.component.ConversationMessage
import com.pozyalov.ai_advent_challenge.chat.component.MessageAuthor
import com.pozyalov.ai_advent_challenge.chat.model.LlmModelOption
import kotlinx.datetime.TimeZone
import kotlinx.datetime.number
import kotlinx.datetime.toLocalDateTime
import org.jetbrains.compose.resources.stringResource
import org.jetbrains.compose.resources.vectorResource
import kotlin.time.ExperimentalTime

@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun ChatScreen(
    component: ChatComponent,
    isDark: Boolean,
    onToggleTheme: () -> Unit,
) {
    val model by component.model.collectAsState()
    val focusManager = LocalFocusManager.current
    val listState = rememberLazyListState()

    val emptyStateText = stringResource(Res.string.chat_empty_state)
    val missingKeyText = stringResource(Res.string.chat_api_key_missing)
    val errorText = stringResource(Res.string.chat_error_generic)
    val inputPlaceholder = stringResource(Res.string.chat_input_placeholder)
    val sendLabel = stringResource(Res.string.chat_send)

    LaunchedEffect(model.messages.size) {
        if (model.messages.isNotEmpty()) {
            listState.animateScrollToItem(model.messages.lastIndex)
        }
    }

    fun submitMessage() {
        if (model.input.isBlank() || model.isSending) return
        component.onSend()
        focusManager.clearFocus(force = true)
    }

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing),
        topBar = {
            val icon = if (isDark) Res.drawable.ic_light_mode else Res.drawable.ic_dark_mode
            CenterAlignedTopAppBar(
                navigationIcon = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        IconButton(onClick = component::onBack) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = null
                            )
                        }
                        ModelMenuButton(
                            models = model.availableModels,
                            selectedModelId = model.selectedModelId,
                            onSelect = component::onModelSelected
                        )
                    }
                },
                title = { Text(text = stringResource(Res.string.chat_title)) },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                ),
                actions = {
                    IconButton(onClick = onToggleTheme) {
                        Icon(
                            imageVector = vectorResource(icon),
                            contentDescription = null
                        )
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (!model.isConfigured) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                    shape = MaterialTheme.shapes.medium
                ) {
                    Text(
                        text = missingKeyText,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(12.dp)
                    )
                }
            }

            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentAlignment = Alignment.TopCenter
            ) {
                if (model.messages.isEmpty()) {
                    Text(
                        text = emptyStateText,
                        textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 24.dp, vertical = 32.dp)
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        state = listState
                    ) {
                        items(model.messages, key = { it.id }) { message ->
                            ChatMessageBubble(
                                message = message,
                                missingKeyText = missingKeyText,
                                defaultErrorText = errorText,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                }
            }

            if (model.isSending) {
                LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(4.dp)
                )
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                OutlinedTextField(
                    value = model.input,
                    onValueChange = component::onInputChange,
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    placeholder = { Text(text = inputPlaceholder) },
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.Sentences,
                        imeAction = ImeAction.Send
                    ),
                    keyboardActions = KeyboardActions(onSend = { submitMessage() }),
                    enabled = !model.isSending
                )

                Button(
                    onClick = { submitMessage() },
                    enabled = model.input.isNotBlank() && !model.isSending
                ) {
                    Text(text = sendLabel)
                }
            }
        }
    }
}

@Composable
private fun ChatMessageBubble(
    message: ConversationMessage,
    missingKeyText: String,
    defaultErrorText: String,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val displayText = when (message.error) {
        ConversationError.MissingApiKey -> missingKeyText
        ConversationError.Failure -> message.text.ifBlank { defaultErrorText }
        null -> message.text
    }
    val (containerColor, contentColor) = when {
        message.error != null -> colors.errorContainer to colors.onErrorContainer
        message.author == MessageAuthor.User -> colors.primaryContainer to colors.onPrimaryContainer
        else -> colors.surfaceVariant to colors.onSurfaceVariant
    }

    Row(
        modifier = modifier,
        horizontalArrangement = if (message.author == MessageAuthor.User) Arrangement.End else Arrangement.Start
    ) {
        val metaLine = message.metaLine()
        val metaColor = when {
            message.error != null -> colors.onErrorContainer
            message.author == MessageAuthor.User -> colors.onPrimaryContainer.copy(alpha = 0.9f)
            else -> colors.onSurfaceVariant
        }

        Surface(
            modifier = Modifier.widthIn(max = 520.dp),
            color = containerColor,
            contentColor = contentColor,
            shape = MaterialTheme.shapes.large,
            tonalElevation = if (message.author == MessageAuthor.User) 2.dp else 0.dp
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                SelectionContainer {
                    Text(
                        text = displayText,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
                Text(
                    text = metaLine,
                    style = MaterialTheme.typography.labelSmall,
                    color = metaColor
                )
            }
        }
    }
}

@Composable
private fun ModelMenuButton(
    models: List<LlmModelOption>,
    selectedModelId: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (models.isEmpty()) return
    val selected = models.firstOrNull { it.id == selectedModelId } ?: models.first()
    var expanded by remember { mutableStateOf(false) }
    LaunchedEffect(selectedModelId) { expanded = false }
    val buttonEnabled = models.size > 1

    Box(modifier = modifier.padding(start = 8.dp)) {
        Surface(
            onClick = { expanded = true },
            enabled = buttonEnabled,
            shape = MaterialTheme.shapes.small,
            color = Color.Transparent,//MaterialTheme.colorScheme.surfaceVariant,
            tonalElevation = 2.dp
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = selected.displayName,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Icon(
                    imageVector = Icons.Filled.ExpandMore,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurface
                )
            }
        }

        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            models.forEach { option ->
                DropdownMenuItem(
                    text = {
                        Column(
                            modifier = Modifier.padding(vertical = 4.dp),
                            verticalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            Text(
                                text = option.displayName,
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Text(
                                text = option.description,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    },
                    onClick = {
                        expanded = false
                        onSelect(option.id)
                    }
                )
            }
        }
    }
}

private fun ConversationMessage.metaLine(): String {
    val local = timestamp.toLocalDateTime(TimeZone.currentSystemDefault())
    val parts = buildList {
        modelId?.let { add(it) }
        add(local.formatDate())
        add(local.formatTime())
    }
    return parts.joinToString(separator = " · ")
}

private fun kotlinx.datetime.LocalDateTime.formatDate(): String {
    val day = day.twoDigits()
    val month = month.number.twoDigits()
    val year = year.toString()
    return "$day.$month.$year"
}

private fun kotlinx.datetime.LocalDateTime.formatTime(): String {
    val hour = hour.twoDigits()
    val minute = minute.twoDigits()
    return "$hour:$minute"
}

private fun Int.twoDigits(): String = if (this < 10) "0$this" else "$this"
