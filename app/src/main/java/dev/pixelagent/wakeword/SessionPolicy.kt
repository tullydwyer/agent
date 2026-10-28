package dev.pixelagent.wakeword

import dev.pixelagent.conversation.AgentState
import dev.pixelagent.conversation.AudioState
import dev.pixelagent.conversation.TurnStatus

/** State predicates shared with the service. Model completion alone is not playback completion. */
object SessionPolicy {
    const val MAX_DURATION_MS = 8 * 60 * 60 * 1000L
    const val DEFAULT_DURATION_MS = 60 * 60 * 1000L
    const val COOLDOWN_MS = 1500L
    const val LISTEN_WINDOW_MS = 12_000L
    fun duration(requested: Long) = requested.coerceIn(1000L, MAX_DURATION_MS)
    fun turnFinished(state: AgentState) = !state.awaitingTranscript && !state.busy && state.audio == AudioState.IDLE
    fun failed(state: AgentState) = state.turnStatus in setOf(TurnStatus.ACCESS_ERROR,
        TurnStatus.NETWORK_ERROR, TurnStatus.TIMEOUT, TurnStatus.FAILED)
    fun hasReply(previousTurnId: Long, state: AgentState) =
        state.turnId != previousTurnId && state.turnStatus == TurnStatus.COMPLETE
}
