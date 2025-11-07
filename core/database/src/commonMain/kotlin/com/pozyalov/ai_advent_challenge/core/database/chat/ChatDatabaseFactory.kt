package com.pozyalov.ai_advent_challenge.core.database.chat

/**
 * Creates a platform-specific instance of [ChatDatabase].
 *
 * @param androidContext Optional Android [Context] instance (ignored on non-Android targets).
 * @param name Logical database name (used on Android) or fallback file name.
 * @param filePath Absolute path to the database file (used on desktop/iOS targets).
 * @param fallbackToDestructiveMigration When true, Room may reset the schema if no migrations exist.
 */
expect fun createChatDatabase(
    androidContext: Any? = null,
    name: String = "chat_history.db",
    filePath: String? = null,
    fallbackToDestructiveMigration: Boolean = true,
): ChatDatabase
