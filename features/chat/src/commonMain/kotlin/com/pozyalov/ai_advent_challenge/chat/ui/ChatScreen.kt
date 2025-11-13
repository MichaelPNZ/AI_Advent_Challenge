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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
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
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
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
import com.pozyalov.ai_advent_challenge.chat.model.ChatRoleOption
import com.pozyalov.ai_advent_challenge.chat.model.ContextLimitOption
import com.pozyalov.ai_advent_challenge.chat.model.LlmModelOption
import com.pozyalov.ai_advent_challenge.chat.model.ReasoningOption
import com.pozyalov.ai_advent_challenge.chat.model.TemperatureOption
import kotlinx.datetime.TimeZone
import kotlinx.datetime.number
import kotlinx.datetime.toLocalDateTime
import org.jetbrains.compose.resources.stringResource
import kotlin.math.round
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

    val promptTokenDeltas = remember(model.messages) {
        calculatePromptTokenDeltas(model.messages)
    }

    val contextUsageInfo = remember(
        model.messages,
        model.availableContextLimits,
        model.selectedContextLimitId,
        model.contextLimitInput
    ) {
        calculateContextUsage(
            messages = model.messages,
            contextLimits = model.availableContextLimits,
            selectedContextLimitId = model.selectedContextLimitId,
            contextLimitInput = model.contextLimitInput
        )
    }

    if (showSettings) {
        ChatSettingsDialog(
            isDark = isDark,
            onThemeChange = onThemeChange,
            onDismiss = { showSettings = false },
            models = model.availableModels,
            selectedModelId = model.selectedModelId,
            onModelSelected = component::onModelSelected,
            comparisonModelId = model.comparisonModelId,
            onComparisonModelSelected = component::onComparisonModelSelected,
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
            onReasoningSelected = component::onReasoningSelected,
            contextLimits = model.availableContextLimits,
            selectedContextLimitId = model.selectedContextLimitId,
            onContextLimitSelected = component::onContextLimitSelected,
            contextLimitInput = model.contextLimitInput,
            onContextLimitInputChange = component::onContextLimitInputChange
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

            contextUsageInfo?.let { usage ->
                ContextUsageIndicator(
                    info = usage,
                    modifier = Modifier.fillMaxWidth()
                )
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
                            val promptTokensActual = promptTokenDeltas[message.id]
                            ChatMessageBubble(
                                message = message,
                                missingKeyText = missingKeyText,
                                defaultErrorText = errorText,
                                temperatureLabel = temperatureLabel,
                                roleLabel = roleLabel,
                                promptTokensActual = promptTokensActual,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                }
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
    promptTokensActual: Long?,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val displayText = when {
        message.isThinking -> message.text.ifBlank { "Думаю" }
        else -> when (message.error) {
            ConversationError.MissingApiKey -> missingKeyText
            ConversationError.RateLimit -> message.text.ifBlank {
                "Превышен лимит запросов OpenAI. Подождите и попробуйте снова."
            }
            ConversationError.ContextLimit -> message.text.ifBlank {
                "Превышен выбранный лимит контекста. Уменьшите историю или снижайте нагрузку."
            }
            ConversationError.Failure -> message.text.ifBlank { defaultErrorText }
            null -> message.text
            else -> message.text
        }
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
        val metaEntries = message.metaEntries(
            temperatureLabel = temperatureLabel,
            roleLabel = roleLabel,
            promptTokensActual = promptTokensActual
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
                if (message.isThinking) {
                    ThinkingMessageContent(
                        label = displayText,
                        dotColor = contentColor,
                        textColor = contentColor
                    )
                } else {
                    SelectionContainer {
                        Text(
                            text = displayText,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                    if (metaEntries.isNotEmpty()) {
                        Column(
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            metaEntries.forEach { entry ->
                                Text(
                                    text = "${entry.label}: ${entry.value}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = metaColor
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ThinkingMessageContent(
    label: String,
    dotColor: Color,
    textColor: Color
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = textColor
        )
        CircularProgressIndicator(
            modifier = Modifier.size(20.dp),
            color = dotColor,
            strokeWidth = 2.dp
        )
    }
}

@Composable
private fun ContextUsageIndicator(
    info: ContextUsageInfo,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.surfaceVariant,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        shape = MaterialTheme.shapes.medium,
        tonalElevation = 1.dp
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "Контекст",
                style = MaterialTheme.typography.labelMedium
            )
            Text(
                text = "${info.percent.formatDecimal(1)}% (${info.usedTokens} / ${info.limitTokens} ток.)",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            LinearProgressIndicator(
                progress = (info.percent.coerceAtMost(100.0) / 100.0).toFloat(),
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

private data class ContextUsageInfo(
    val usedTokens: Long,
    val limitTokens: Long,
    val percent: Double
)

private fun calculateContextUsage(
    messages: List<ConversationMessage>,
    contextLimits: List<ContextLimitOption>,
    selectedContextLimitId: String,
    contextLimitInput: String
): ContextUsageInfo? {
    val usedTokens = messages.asReversed()
        .firstOrNull { message ->
            message.author == MessageAuthor.Agent && !message.isThinking && message.totalTokens != null
        }
        ?.totalTokens
        ?: return null
    val selectedOption = contextLimits.firstOrNull { it.id == selectedContextLimitId }
    val customLimit = selectedOption
        ?.takeIf { it.requiresCustomValue }
        ?.let { contextLimitInput.toLongOrNull()?.takeIf { amount -> amount > 0 } }
    val limitTokens = customLimit
        ?: selectedOption?.paddingTokens?.toLong()
        ?: DEFAULT_CONTEXT_LIMIT_TOKENS
    if (limitTokens <= 0L) return null
    val percent = if (limitTokens == 0L) 0.0 else (usedTokens.toDouble() / limitTokens.toDouble()) * 100.0
    return ContextUsageInfo(
        usedTokens = usedTokens,
        limitTokens = limitTokens,
        percent = percent
    )
}

@Composable
private fun ChatSettingsDialog(
    isDark: Boolean,
    onThemeChange: (Boolean) -> Unit,
    onDismiss: () -> Unit,
    models: List<LlmModelOption>,
    selectedModelId: String,
    onModelSelected: (String) -> Unit,
    comparisonModelId: String?,
    onComparisonModelSelected: (String?) -> Unit,
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
    contextLimits: List<ContextLimitOption>,
    selectedContextLimitId: String,
    onContextLimitSelected: (String) -> Unit,
    contextLimitInput: String,
    onContextLimitInputChange: (String) -> Unit,
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
                val comparisonSelectedId = comparisonModelId ?: COMPARISON_DISABLED_ID
                val comparisonOptions = buildList {
                    add(
                        ComparisonModelOption(
                            id = COMPARISON_DISABLED_ID,
                            title = "Без сравнения",
                            description = "Отправлять запрос только в выбранную модель"
                        )
                    )
                    models.filter { it.id != selectedModelId }
                        .forEach { option ->
                            add(
                                ComparisonModelOption(
                                    id = option.id,
                                    title = option.displayName,
                                    description = option.description
                                )
                            )
                        }
                }
                SettingsDropdown(
                    label = "Сравнение моделей",
                    selectedId = comparisonSelectedId,
                    options = comparisonOptions,
                    optionId = { it.id },
                    optionTitle = { it.title },
                    optionDescription = { it.description },
                    onSelect = { option ->
                        if (option.id == COMPARISON_DISABLED_ID) {
                            onComparisonModelSelected(null)
                        } else {
                            onComparisonModelSelected(option.id)
                        }
                    }
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
                SettingsDropdown(
                    label = "Ограничение контекста",
                    selectedId = selectedContextLimitId,
                    options = contextLimits,
                    optionId = { it.id },
                    optionTitle = { it.displayName },
                    optionDescription = { it.description },
                    onSelect = { onContextLimitSelected(it.id) }
                )
                val selectedContextOption = contextLimits.firstOrNull { it.id == selectedContextLimitId }
                if (selectedContextOption?.requiresCustomValue == true) {
                    OutlinedTextField(
                        value = contextLimitInput,
                        onValueChange = onContextLimitInputChange,
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Свое значение (токены)") },
                        placeholder = { Text("например, 150000") },
                        singleLine = true
                    )
                }
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

private data class ComparisonModelOption(
    val id: String,
    val title: String,
    val description: String?
)

private const val THEME_LIGHT_ID = "theme_light"
private const val THEME_DARK_ID = "theme_dark"
private const val COMPARISON_DISABLED_ID = "comparison_none"
private const val DEFAULT_CONTEXT_LIMIT_TOKENS = 128_000L

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

private data class MessageMetaEntry(
    val label: String,
    val value: String
)

private fun ConversationMessage.metaEntries(
    temperatureLabel: String?,
    roleLabel: String?,
    promptTokensActual: Long?
): List<MessageMetaEntry> {
    val local = timestamp.toLocalDateTime(TimeZone.currentSystemDefault())
    return buildList {
        modelId?.let { add(MessageMetaEntry(label = "Модель", value = it)) }
        responseTimeMillis
            ?.takeIf { it > 0 }
            ?.let { add(MessageMetaEntry(label = "Время", value = it.formatMillisAsSeconds())) }
        tokensLabel(promptTokensActual)
            ?.let { add(MessageMetaEntry(label = "Токены", value = it)) }
        costUsd
            ?.takeIf { it > 0.0 }
            ?.let { add(MessageMetaEntry(label = "Стоимость", value = "$${it.formatCurrency()}")) }
        temperatureLabel
            ?.takeIf { it.isNotBlank() }
            ?.let { add(MessageMetaEntry(label = "Температура", value = it)) }
        roleLabel
            ?.takeIf { it.isNotBlank() }
            ?.let { add(MessageMetaEntry(label = "Роль", value = it)) }
        add(MessageMetaEntry(label = "Дата", value = local.formatDate()))
        add(MessageMetaEntry(label = "Время", value = local.formatTime()))
    }
}

private fun ConversationMessage.tokensLabel(promptTokensActual: Long?): String? {
    val prompt = promptTokensActual ?: promptTokens
    val completion = completionTokens
    val total = when {
        promptTokensActual != null && completion != null -> promptTokensActual + completion
        totalTokens != null -> totalTokens
        prompt != null && completion != null -> prompt + completion
        else -> null
    }
    return when {
        prompt != null && completion != null -> "prompt=$prompt · completion=$completion ток."
        total != null -> "$total ток."
        prompt != null -> "prompt=$prompt ток."
        completion != null -> "completion=$completion ток."
        else -> null
    }
}

private fun Long.formatMillisAsSeconds(): String {
    val seconds = this / 1_000.0
    return "${seconds.formatDecimal(2)}s"
}

private fun Double.formatCurrency(): String = formatDecimal(4)

private fun Double.formatDecimal(decimals: Int): String {
    if (decimals <= 0) {
        return toLong().toString()
    }
    val scale = pow10(decimals)
    val rounded = round(this * scale) / scale
    val raw = rounded.toString()
    return if (raw.contains('.')) {
        raw.trimEnd('0').trimEnd('.')
    } else {
        raw
    }
}

private fun pow10(exp: Int): Double {
    var result = 1.0
    repeat(exp) { result *= 10.0 }
    return result
}

private fun calculatePromptTokenDeltas(
    messages: List<ConversationMessage>
): Map<Long, Long> {
    if (messages.isEmpty()) return emptyMap()
    val sorted = messages.sortedBy { it.timestamp }
    val lastPromptTokens = mutableMapOf<String, Long>()
    val deltas = mutableMapOf<Long, Long>()
    for (message in sorted) {
        if (message.author != MessageAuthor.Agent || message.isThinking) continue
        val model = message.modelId ?: continue
        val rawPrompt = message.promptTokens ?: continue
        val previous = lastPromptTokens[model]
        val delta = if (previous == null) {
            rawPrompt
        } else {
            val diff = rawPrompt - previous
            if (diff > 0) diff else rawPrompt
        }
        deltas[message.id] = delta
        lastPromptTokens[model] = rawPrompt
    }
    return deltas
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
