package dev.pixelagent.conversation

enum class AudioState(val label: String) {
    IDLE("Idle"),
    LISTENING("Listening locally"),
    TRANSCRIBING("Transcribing locally"),
    SPEAKING("Speaking locally"),
}

data class AgentState(
    val audio: AudioState = AudioState.IDLE,
    val localRecognitionAvailable: Boolean = false,
    val localVoice: String? = null,
    val ttsInitializing: Boolean = true,
    val transcript: String = "",
    val notice: String = "Check local speech, then try a short phrase.",
)

