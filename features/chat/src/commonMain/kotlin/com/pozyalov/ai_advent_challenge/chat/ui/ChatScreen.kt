@file:OptIn(ExperimentalTime::class)

package com.pozyalov.ai_advent_challenge.chat.ui

import ai_advent_challenge.features.chat.generated.resources.Res
import ai_advent_challenge.features.chat.generated.resources.chat_api_key_missing
import ai_advent_challenge.features.chat.generated.resources.chat_empty_state
import ai_advent_challenge.features.chat.generated.resources.chat_error_generic
import ai_advent_challenge.features.chat.generated.resources.chat_input_placeholder
import ai_advent_challenge.features.chat.generated.resources.chat_send
import ai_advent_challenge.features.chat.generated.resources.chat_title
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
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
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.pozyalov.ai_advent_challenge.chat.component.ChatComponent
import com.pozyalov.ai_advent_challenge.chat.component.ConversationError
import com.pozyalov.ai_advent_challenge.chat.component.ConversationMessage
import com.pozyalov.ai_advent_challenge.chat.component.MessageAuthor
import com.pozyalov.ai_advent_challenge.chat.model.ChatRoleOption
import com.pozyalov.ai_advent_challenge.chat.model.LlmModelOption
import com.pozyalov.ai_advent_challenge.chat.model.ReasoningOption
import com.pozyalov.ai_advent_challenge.chat.model.TemperatureOption
import kotlinx.datetime.TimeZone
import kotlinx.datetime.number
import kotlinx.datetime.toLocalDateTime
import org.jetbrains.compose.resources.stringResource
import kotlin.time.ExperimentalTime

@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun ChatScreen(
    component: ChatComponent,
    isDark: Boolean,
    onThemeChange: (Boolean) -> Unit,
) {
    val model by component.model.collectAsState()
    val focusManager = LocalFocusManager.current
    val listState = rememberLazyListState()
    var showSettings by remember { mutableStateOf(false) }

    val emptyStateText = stringResource(Res.string.chat_empty_state)
    val missingKeyText = stringResource(Res.string.chat_api_key_missing)
    val errorText = stringResource(Res.string.chat_error_generic)
    val inputPlaceholder = stringResource(Res.string.chat_input_placeholder)
    val sendLabel = stringResource(Res.string.chat_send)
    val roleTitleLookup = remember(model.availableRoles) {
        model.availableRoles.associate { it.id to it.displayName }
    }

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

    if (showSettings) {
        ChatSettingsDialog(
            isDark = isDark,
            onThemeChange = onThemeChange,
            onDismiss = { showSettings = false },
            models = model.availableModels,
            selectedModelId = model.selectedModelId,
            onModelSelected = component::onModelSelected,
            roles = model.availableRoles,
            selectedRoleId = model.selectedRoleId,
            onRoleSelected = component::onRoleSelected,
            temperatures = model.availableTemperatures,
            selectedTemperatureId = model.selectedTemperatureId,
            isTemperatureLocked = model.isTemperatureLocked,
            lockedTemperatureValue = model.lockedTemperatureValue,
            onTemperatureSelected = component::onTemperatureSelected,
            reasoningOptions = model.availableReasoning,
            selectedReasoningId = model.selectedReasoningId,
            onReasoningSelected = component::onReasoningSelected
        )
    }

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing),
        topBar = {
            CenterAlignedTopAppBar(
                navigationIcon = {
                    IconButton(onClick = component::onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = null
                        )
                    }
                },
                title = { Text(text = stringResource(Res.string.chat_title)) },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                ),
                actions = {
                    IconButton(onClick = { showSettings = true }) {
                        Icon(
                            imageVector = Icons.Filled.Settings,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurface
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
                            val temperatureLabel = message.temperature?.let { "T=${it.formatTemperatureValue()}" }
                            val roleLabel = message.roleId?.let { roleTitleLookup[it] ?: it }
                            ChatMessageBubble(
                                message = message,
                                missingKeyText = missingKeyText,
                                defaultErrorText = errorText,
                                temperatureLabel = temperatureLabel,
                                roleLabel = roleLabel,
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
    temperatureLabel: String?,
    roleLabel: String?,
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
        val metaLine = message.metaLine(
            temperatureLabel = temperatureLabel,
            roleLabel = roleLabel
        )
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
private fun ChatSettingsDialog(
    isDark: Boolean,
    onThemeChange: (Boolean) -> Unit,
    onDismiss: () -> Unit,
    models: List<LlmModelOption>,
    selectedModelId: String,
    onModelSelected: (String) -> Unit,
    roles: List<ChatRoleOption>,
    selectedRoleId: String,
    onRoleSelected: (String) -> Unit,
    temperatures: List<TemperatureOption>,
    selectedTemperatureId: String,
    isTemperatureLocked: Boolean,
    lockedTemperatureValue: Double?,
    onTemperatureSelected: (String) -> Unit,
    reasoningOptions: List<ReasoningOption>,
    selectedReasoningId: String,
    onReasoningSelected: (String) -> Unit,
) {
    val selectedThemeId = if (isDark) THEME_DARK_ID else THEME_LIGHT_ID
    val selectedModelName = models.firstOrNull { it.id == selectedModelId }?.displayName ?: "выбранной модели"

    AlertDialog(
        modifier = Modifier.padding(20.dp),
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(text = "Готово")
            }
        },
        title = { Text(text = "Настройки чата") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                SettingsDropdown(
                    label = "Тема",
                    selectedId = selectedThemeId,
                    options = chatThemeOptions,
                    optionId = { it.id },
                    optionTitle = { it.title },
                    optionDescription = { it.description },
                    onSelect = { option ->
                        if (option.isDark != isDark) {
                            onThemeChange(option.isDark)
                        }
                    }
                )
                SettingsDropdown(
                    label = "Модель",
                    selectedId = selectedModelId,
                    options = models,
                    optionId = { it.id },
                    optionTitle = { it.displayName },
                    optionDescription = { it.description },
                    onSelect = { onModelSelected(it.id) }
                )
                SettingsDropdown(
                    label = "Роль",
                    selectedId = selectedRoleId,
                    options = roles,
                    optionId = { it.id },
                    optionTitle = { it.displayName },
                    optionDescription = { it.description },
                    onSelect = { onRoleSelected(it.id) }
                )
                if (isTemperatureLocked) {
                    LockedSetting(
                        label = "Температура",
                        value = lockedTemperatureValue?.let { "T=${it.formatTemperatureValue()}" } ?: "T=—",
                        description = "Температура фиксирована для $selectedModelName"
                    )
                } else {
                    SettingsDropdown(
                        label = "Температура",
                        selectedId = selectedTemperatureId,
                        options = temperatures,
                        optionId = { it.id },
                        optionTitle = { it.displayName },
                        optionDescription = { it.description },
                        onSelect = { onTemperatureSelected(it.id) }
                    )
                }
                SettingsDropdown(
                    label = "Reasoning effort",
                    selectedId = selectedReasoningId,
                    options = reasoningOptions,
                    optionId = { it.id },
                    optionTitle = { it.displayName },
                    optionDescription = { it.description },
                    onSelect = { onReasoningSelected(it.id) }
                )
            }
        }
    )
}

@Composable
private fun <T> SettingsDropdown(
    label: String,
    selectedId: String,
    options: List<T>,
    optionId: (T) -> String,
    optionTitle: (T) -> String,
    optionDescription: (T) -> String?,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier
) {
    if (options.isEmpty()) return
    val selected = options.firstOrNull { optionId(it) == selectedId } ?: options.first()
    var expanded by remember { mutableStateOf(false) }
    LaunchedEffect(selectedId) { expanded = false }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Box {
            Surface(
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.surfaceVariant,
                tonalElevation = 0.dp
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { expanded = true }
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        Text(
                            text = optionTitle(selected),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        optionDescription(selected)
                            ?.takeIf { it.isNotBlank() }
                            ?.let { desc ->
                                Text(
                                    text = desc,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                    }
                    Icon(
                        imageVector = Icons.Filled.ExpandMore,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false }
            ) {
                options.forEach { option ->
                    DropdownMenuItem(
                        text = {
                            Column(
                                modifier = Modifier.padding(vertical = 4.dp),
                                verticalArrangement = Arrangement.spacedBy(2.dp)
                            ) {
                                Text(
                                    text = optionTitle(option),
                                    style = MaterialTheme.typography.bodyMedium
                                )
                                optionDescription(option)
                                    ?.takeIf { it.isNotBlank() }
                                    ?.let { desc ->
                                        Text(
                                            text = desc,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                            }
                        },
                        onClick = {
                            expanded = false
                            onSelect(option)
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun LockedSetting(
    label: String,
    value: String,
    description: String,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Surface(
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.surfaceVariant,
            tonalElevation = 0.dp
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    text = value,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

private data class ChatThemeOption(
    val id: String,
    val title: String,
    val description: String,
    val isDark: Boolean
)

private const val THEME_LIGHT_ID = "theme_light"
private const val THEME_DARK_ID = "theme_dark"

private val chatThemeOptions = listOf(
    ChatThemeOption(
        id = THEME_LIGHT_ID,
        title = "Светлая тема",
        description = "Светлая палитра и стандартный контраст",
        isDark = false
    ),
    ChatThemeOption(
        id = THEME_DARK_ID,
        title = "Тёмная тема",
        description = "Глубокие оттенки для работы ночью",
        isDark = true
    )
)

private fun ConversationMessage.metaLine(
    temperatureLabel: String?,
    roleLabel: String?
): String {
    val local = timestamp.toLocalDateTime(TimeZone.currentSystemDefault())
    val parts = buildList {
        modelId?.let { add(it) }
        temperatureLabel?.takeIf { it.isNotBlank() }?.let { add(it) }
        roleLabel?.takeIf { it.isNotBlank() }?.let { add(it) }
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

private fun Double.formatTemperatureValue(): String {
    val raw = this.toString()
    return if (raw.contains('E') || raw.contains('e')) {
        raw
    } else {
        raw.trimEnd('0').trimEnd('.')
    }
}
