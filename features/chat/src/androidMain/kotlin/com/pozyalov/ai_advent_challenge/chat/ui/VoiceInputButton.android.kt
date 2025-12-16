package com.pozyalov.ai_advent_challenge.chat.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

@Composable
internal actual fun VoiceInputButton(
    enabled: Boolean,
    onRecordingStateChange: (Boolean) -> Unit,
    onPartialText: (String) -> Unit,
    onResultText: (String) -> Unit,
    modifier: Modifier,
) {
    // Desktop-only for now.
}
