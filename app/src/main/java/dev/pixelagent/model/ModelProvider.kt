package dev.pixelagent.model

/** Model access stays separate from local speech and keyword capture. */
interface ModelProvider {
    val displayName: String
    val isConfigured: Boolean
    suspend fun reply(message: String): String
}

