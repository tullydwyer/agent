package dev.pixelagent.model

/** Cloud access is a separate experiment. Never fake an AI response in diagnostics. */
interface ModelProvider {
    val displayName: String
    val isConfigured: Boolean
    suspend fun reply(message: String): String
}

class UnconfiguredModelProvider : ModelProvider {
    override val displayName = "Not connected"
    override val isConfigured = false
    override suspend fun reply(message: String): String =
        error("Model authentication has not been configured.")
}

