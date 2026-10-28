package dev.pixelagent.conversation

import dev.pixelagent.wakeword.SessionPolicy
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class HandsFreeTest {
    @Test fun resultCallbackIdleDoesNotRearmBeforeTranscriptDispatchOrPlaybackEnd() = runTest {
        val state = MutableStateFlow(AgentState(audio = AudioState.LISTENING, awaitingTranscript = true))
        val finished = async { state.first(SessionPolicy::turnFinished) }
        runCurrent()
        // Android reports recognizer IDLE before calling onFinalTranscript.
        state.value = state.value.copy(audio = AudioState.IDLE)
        runCurrent()
        assertFalse(finished.isCompleted)
        state.value = state.value.copy(awaitingTranscript = false, busy = true, turnStatus = TurnStatus.REQUESTING)
        runCurrent()
        assertFalse(finished.isCompleted)
        state.value = state.value.copy(audio = AudioState.SPEAKING)
        state.value = state.value.copy(busy = false, turnStatus = TurnStatus.COMPLETE)
        runCurrent()
        assertFalse("Completed HTTP is not completed playback", finished.isCompleted)
        state.value = state.value.copy(audio = AudioState.IDLE)
        runCurrent()
        assertTrue(finished.isCompleted)
        assertFalse(SessionPolicy.failed(finished.await()))
    }
    @Test fun streamFailureEndsSessionRatherThanAutomaticallyRetrying() = runTest {
        val state = MutableStateFlow(AgentState(busy = true, audio = AudioState.SPEAKING))
        val finished = async { state.first(SessionPolicy::turnFinished) }
        runCurrent()
        state.value = state.value.copy(busy = false, audio = AudioState.IDLE, turnStatus = TurnStatus.NETWORK_ERROR)
        assertTrue(SessionPolicy.failed(finished.await()))
    }
    @Test fun sessionDurationIsBoundedEvenForInvalidIntentValues() {
        assertEquals(1000L, SessionPolicy.duration(Long.MIN_VALUE))
        assertEquals(28_800_000L, SessionPolicy.duration(Long.MAX_VALUE))
        assertEquals(900_000L, SessionPolicy.duration(900_000L))
    }
    @Test fun onlyANewCompletedReplyOpensAnotherFollowUpWindow() {
        val previous = AgentState(turnId = 7, turnStatus = TurnStatus.COMPLETE)
        assertFalse("Silence must not reuse the prior reply", SessionPolicy.hasReply(7, previous))
        assertFalse(SessionPolicy.hasReply(7, previous.copy(turnId = 8, turnStatus = TurnStatus.NETWORK_ERROR)))
        assertTrue("A new reply continues the same dialogue without another wake", SessionPolicy.hasReply(7, previous.copy(turnId = 8)))
    }
}
