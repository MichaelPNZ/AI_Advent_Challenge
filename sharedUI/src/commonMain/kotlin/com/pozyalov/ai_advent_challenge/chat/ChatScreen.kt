package com.pozyalov.ai_advent_challenge.chat

import ai_advent_challenge.sharedui.generated.resources.Res
import ai_advent_challenge.sharedui.generated.resources.chat_api_key_missing
import ai_advent_challenge.sharedui.generated.resources.chat_empty_state
import ai_advent_challenge.sharedui.generated.resources.chat_error_generic
import ai_advent_challenge.sharedui.generated.resources.chat_input_placeholder
import ai_advent_challenge.sharedui.generated.resources.chat_send
import ai_advent_challenge.sharedui.generated.resources.chat_title
import ai_advent_challenge.sharedui.generated.resources.ic_dark_mode
import ai_advent_challenge.sharedui.generated.resources.ic_light_mode
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
import androidx.compose.material3.Button
import androidx.compose.material3.CenterAlignedTopAppBar
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.pozyalov.ai_advent_challenge.theme.LocalThemeIsDark
import org.jetbrains.compose.resources.stringResource
import org.jetbrains.compose.resources.vectorResource

@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun ChatScreen(component: ChatComponent) {
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
            val themeState = LocalThemeIsDark.current
            val isDark by themeState
            val icon = if (isDark) Res.drawable.ic_light_mode else Res.drawable.ic_dark_mode
            CenterAlignedTopAppBar(
                title = { Text(text = stringResource(Res.string.chat_title)) },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                ),
                actions = {
                    IconButton(onClick = { themeState.value = !isDark }) {
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
    modifier: Modifier = Modifier
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
        Surface(
            modifier = Modifier.widthIn(max = 520.dp),
            color = containerColor,
            contentColor = contentColor,
            shape = MaterialTheme.shapes.large,
            tonalElevation = if (message.author == MessageAuthor.User) 2.dp else 0.dp
        ) {
            Text(
                text = displayText,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
            )
        }
    }
}
