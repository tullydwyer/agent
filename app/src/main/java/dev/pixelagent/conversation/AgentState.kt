package dev.pixelagent.conversation

enum class AudioState(val label: String) {
    IDLE("Idle"), LISTENING("Listening locally"), TRANSCRIBING("Transcribing locally"), SPEAKING("Speaking locally"),
}

data class ChatMessage(val role: String, val text: String)

data class AgentState(
    val audio: AudioState = AudioState.IDLE,
    val localRecognitionAvailable: Boolean = false,
    val localVoice: String? = null,
    val ttsInitializing: Boolean = true,
    val transcript: String = "",
    val notice: String = "Select an account model to start a conversation.",
    val messages: List<ChatMessage> = emptyList(),
    val persona: String = "Be helpful, concise and conversational.",
    val busy: Boolean = false,
    val partial: String = "",
    val latency: String = "Voice latency: no samples yet.",
)
