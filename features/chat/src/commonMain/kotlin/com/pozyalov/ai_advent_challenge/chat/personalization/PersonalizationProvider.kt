package com.pozyalov.ai_advent_challenge.chat.personalization

/**
 * Provides optional user-specific context that can be appended to the system prompt
 * so the agent remembers habits, tone, and preferences.
 */
interface PersonalizationProvider {
    /**
     * @return multi-line text with profile details or null if personalization is disabled.
     */
    fun personalPrompt(): String?

    object Empty : PersonalizationProvider {
        override fun personalPrompt(): String? = null
    }
}
