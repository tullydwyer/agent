package dev.pixelagent.conversation

import android.app.Application
import android.os.Handler
import android.os.Looper
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.pixelagent.model.PlanUsageProvider
import dev.pixelagent.speech.LocalSpeech
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject

class AgentViewModel(application: Application) : AndroidViewModel(application) {
    private val prefs = application.getSharedPreferences("conversation", 0)
    private val mutableState = MutableStateFlow(AgentState())
    val state = mutableState.asStateFlow()
    private val handler = Handler(Looper.getMainLooper())
    private var turn = 0L
    private var job: Job? = null
    private var provider: PlanUsageProvider? = null
    private var voiceTurn = false
    private var sendTranscript = false
    private var speechEndedAt = 0L
    private var sentence = StringBuilder()
    private val speech = LocalSpeech(
        context = application,
        onState = { audio -> mutableState.update { it.copy(audio = audio) } },
        onTranscript = { text -> mutableState.update { it.copy(transcript = text) } },
        onNotice = { text -> mutableState.update { it.copy(notice = text) } },
        onVoice = { voice -> mutableState.update { it.copy(localVoice = voice, ttsInitializing = false) } },
        onFinalTranscript = { text -> if (sendTranscript) provider?.let { send(text, it, true) } },
        onSpeechEnded = { if (sendTranscript) speechEndedAt = android.os.SystemClock.elapsedRealtime() },
        onPlaybackStart = { recordLatency() },
    )

    init {
        val saved = runCatching {
            val array = JSONArray(prefs.getString("messages", "[]"))
            (0 until array.length()).map { array.getJSONObject(it) }.map { ChatMessage(it.getString("role"), it.getString("text")) }
                .filter { it.role in listOf("user", "assistant") && it.text.length <= 4000 }.takeLast(40)
        }.getOrDefault(emptyList())
        mutableState.update { it.copy(messages = saved, persona = prefs.getString("persona", it.persona) ?: it.persona, localRecognitionAvailable = speech.recognitionAvailable) }
        updateLatencyLabel(readLatencySamples())
        speech.initialize()
    }
    fun setPersona(value: String) {
        val bounded = value.take(1000)
        mutableState.update { it.copy(persona = bounded) }
        prefs.edit().putString("persona", bounded).apply()
    }
    private fun save(messages: List<ChatMessage>) {
        val array = JSONArray()
        messages.takeLast(40).forEach { array.put(JSONObject().put("role", it.role).put("text", it.text)) }
        prefs.edit().putString("messages", array.toString()).apply()
    }
    private fun recordLatency() {
        if (!voiceTurn || speechEndedAt == 0L) return
        val elapsed = android.os.SystemClock.elapsedRealtime() - speechEndedAt
        speechEndedAt = 0L
        val samples = readLatencySamples().takeLast(99) + elapsed
        prefs.edit().putString("voice_latency_ms", samples.joinToString(",")).apply()
        updateLatencyLabel(samples)
    }
    private fun readLatencySamples(): List<Long> = (prefs.getString("voice_latency_ms", "") ?: "").split(",").mapNotNull { it.toLongOrNull() }.takeLast(100)
    private fun updateLatencyLabel(samples: List<Long>) {
        if (samples.isEmpty()) return
        val sorted = samples.sorted()
        val median = sorted[(sorted.size - 1) / 2]
        val p95 = sorted[kotlin.math.ceil(sorted.size * 0.95).toInt() - 1]
        mutableState.update { it.copy(latency = "Voice latency: ${samples.size} samples · median ${median} ms · p95 ${p95} ms") }
    }
    fun listen(model: PlanUsageProvider) {
        if (!model.isConfigured) { mutableState.update { it.copy(notice = "Select an account model first.") }; return }
        provider = model
        sendTranscript = true
        speechEndedAt = 0L
        speech.listen()
    }
    fun listenTest() { sendTranscript = false; speech.listen() }
    fun speak() = speech.speak(state.value.transcript.ifBlank { "Hello. Pixel Agent is running on this phone." })
    fun send(text: String, model: PlanUsageProvider, speakReply: Boolean = false) {
        val message = text.trim().take(4000)
        if (message.isBlank() || state.value.busy) return
        if (!model.isConfigured) { mutableState.update { it.copy(notice = "Select an account model first.") }; return }
        speech.stop()
        provider = model
        voiceTurn = speakReply
        sentence = StringBuilder()
        val id = ++turn
        val previous = state.value.messages.takeLast(12)
        val input = JSONArray().put(JSONObject().put("role", "developer").put("content", state.value.persona.take(1000)))
        previous.forEach { input.put(JSONObject().put("role", it.role).put("content", it.text.take(4000))) }
        input.put(JSONObject().put("role", "user").put("content", message))
        mutableState.update { it.copy(busy = true, partial = "", notice = "Thinking online…") }
        job = viewModelScope.launch {
            try {
                val response = withTimeout(90_000) {
                    model.conversation(input) { delta -> handler.post {
                        if (id == turn && state.value.busy) {
                            mutableState.update { it.copy(partial = (it.partial + delta).take(16000)) }
                            if (voiceTurn) {
                                sentence.append(delta)
                                val boundary = sentence.indexOfFirst { it == '.' || it == '!' || it == '?' || it == '\n' }
                                if (boundary >= 0) {
                                    val part = sentence.substring(0, boundary + 1).trim()
                                    sentence.delete(0, boundary + 1)
                                    if (part.isNotEmpty()) speech.speak(part, enqueue = true)
                                }
                            }
                        }
                    } }
                }
                if (id != turn) return@launch
                if (voiceTurn && sentence.isNotBlank()) speech.speak(sentence.toString(), enqueue = true)
                val messages = (state.value.messages + ChatMessage("user", message) + ChatMessage("assistant", response.take(4000))).takeLast(40)
                save(messages)
                mutableState.update { it.copy(messages = messages, busy = false, partial = "", notice = "Reply complete.") }
            } catch (cancelled: CancellationException) {
                if (id == turn) { model.cancel(); speech.stop(); mutableState.update { it.copy(busy = false, partial = "", notice = "Turn timed out or was cancelled.") } }
            } catch (failure: Exception) {
                if (id == turn) { speech.stop(); mutableState.update { it.copy(busy = false, partial = "", notice = "Turn failed (${failure.javaClass.simpleName}). Check connectivity, account access or plan limits and retry. Partial reply discarded.") } }
            } finally {
                if (id != turn) mutableState.update { it.copy(busy = false) }
            }
        }
    }
    fun stopAudio() = speech.stop()
    fun cancel() {
        turn++
        speechEndedAt = 0L
        provider?.cancel()
        job?.cancel()
        speech.stop()
        mutableState.update { it.copy(partial = "", notice = "Cancelled. Partial reply discarded.") }
    }
    fun permissionDenied() { mutableState.update { it.copy(notice = "Microphone access was declined.") } }
    override fun onCleared() { cancel(); speech.close() }
}
