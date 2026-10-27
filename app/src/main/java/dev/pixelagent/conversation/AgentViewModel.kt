package dev.pixelagent.conversation

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import dev.pixelagent.model.UnconfiguredModelProvider
import dev.pixelagent.speech.LocalSpeech
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

class AgentViewModel(application: Application) : AndroidViewModel(application) {
    private val mutableState = MutableStateFlow(AgentState())
    val state = mutableState.asStateFlow()
    val model = UnconfiguredModelProvider()
    private val speech = LocalSpeech(
        context = application,
        onState = { audio -> mutableState.update { it.copy(audio = audio) } },
        onTranscript = { text -> mutableState.update { it.copy(transcript = text) } },
        onNotice = { text -> mutableState.update { it.copy(notice = text) } },
        onVoice = { voice -> mutableState.update { it.copy(localVoice = voice, ttsInitializing = false) } },
    )

    init {
        mutableState.update { it.copy(localRecognitionAvailable = speech.recognitionAvailable) }
        speech.initialize()
    }

    fun listen() = speech.listen()
    fun speak() = speech.speak(state.value.transcript.ifBlank {
        "Hello. Pixel Agent is running on this phone. This is a local voice test."
    })
    fun stopAudio() = speech.stop()
    fun permissionDenied() {
        mutableState.update { it.copy(notice = "Microphone access was declined. You can still test local speech output.") }
    }

    override fun onCleared() { speech.close() }
}

