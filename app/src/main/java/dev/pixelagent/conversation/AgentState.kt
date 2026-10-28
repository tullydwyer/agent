package dev.pixelagent.conversation

enum class AudioState(val label: String) {
    IDLE("Idle"), LISTENING("Listening locally"), TRANSCRIBING("Transcribing locally"), SPEAKING("Speaking locally"),
}

data class ChatMessage(val role: String, val text: String)

enum class TurnStatus(val label: String) {
    IDLE("Ready"), REQUESTING("Thinking online"), TOOLS("Loading skill"), COMPLETE("Complete"),
    CANCELLED("Cancelled"), TIMEOUT("Timed out"), NETWORK_ERROR("Network interrupted"),
    ACCESS_ERROR("Account access required"), FAILED("Turn failed"),
}

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
    val awaitingTranscript: Boolean = false,
    val partial: String = "",
    val latency: String = "Voice latency: no samples yet.",
    val textLatency: String = "Text latency: no samples yet.",
    val turnStatus: TurnStatus = TurnStatus.IDLE,
    val turnId: Long = 0,
    val skillsAllowed: Boolean = true,
    val skillCatalog: List<dev.pixelagent.skills.Skill> = emptyList(),
    val skillIssues: List<dev.pixelagent.skills.SkillIssue> = emptyList(),
    val activeSkills: List<String> = emptyList(),
    val toolEvents: List<String> = emptyList(),
)
