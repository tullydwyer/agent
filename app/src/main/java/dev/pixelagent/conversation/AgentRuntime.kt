package dev.pixelagent.conversation

import android.app.Application
import android.net.Uri
import androidx.room.Room
import dev.pixelagent.memory.MemoryDatabase
import dev.pixelagent.memory.MemoryRepository
import dev.pixelagent.memory.MemoryRetrieval
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Dispatchers
import dev.pixelagent.model.ModelHttpException
import dev.pixelagent.model.ModelResponseException
import dev.pixelagent.model.ModelStreamInterruptedException
import dev.pixelagent.model.PlanUsageProvider
import dev.pixelagent.skills.AssetSkillSource
import dev.pixelagent.skills.SkillRegistry
import dev.pixelagent.skills.SkillSession
import dev.pixelagent.speech.LocalSpeech
import dev.pixelagent.tools.ToolDispatcher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.IOException
import kotlin.math.ceil

/** Application-owned: an explicitly started service can outlive an Activity/ViewModel. */
class AgentRuntime private constructor(application: Application) {
    companion object {
        @Volatile private var instance: AgentRuntime? = null
        fun get(application: Application): AgentRuntime = instance ?: synchronized(this) {
            instance ?: AgentRuntime(application).also { instance = it }
        }
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val prefs = application.getSharedPreferences("conversation", 0)
    private val app = application
    private val memory = MemoryRepository(Room.databaseBuilder(application, MemoryDatabase::class.java, "memory.db").build())
    private val memoryLock = Mutex()
    private val registry = SkillRegistry(AssetSkillSource(application.assets))
    private val skills = SkillSession(registry, prefs.getString("active_skills", "[]") ?: "[]")
    private val mutableState = MutableStateFlow(AgentState())
    val state = mutableState.asStateFlow()
    private val handler = Handler(Looper.getMainLooper())
    private var turn = 0L
    private var job: Job? = null
    private var provider: PlanUsageProvider? = null
    private var voiceTurn = false
    private var sendTranscript = false
    private var speechEndedAt = 0L
    private var textStartedAt = 0L
    private var sentence = StringBuilder()
    private val dispatcher = ToolDispatcher(skills) { state.value.skillsAllowed }
    private val speech = LocalSpeech(
        context = application,
        onState = { audio -> mutableState.update { it.copy(audio = audio) } },
        onTranscript = { text -> mutableState.update { it.copy(transcript = text) } },
        onNotice = { text -> mutableState.update { it.copy(notice = text) } },
        onVoice = { voice -> mutableState.update { it.copy(localVoice = voice, ttsInitializing = false) } },
        onFinalTranscript = { text -> if (sendTranscript) { sendTranscript = false; provider?.let { send(text, it, true) } } },
        onSpeechEnded = { if (sendTranscript) speechEndedAt = SystemClock.elapsedRealtime() },
        onPlaybackStart = { recordVoiceLatency() },
        onRecognitionFinished = {
            sendTranscript = false
            mutableState.update { it.copy(awaitingTranscript = false) }
        },
    )

    init {
        dev.pixelagent.camera.SnapshotEncoder.clearTemporaryFiles(application.cacheDir)
        val saved = runCatching {
            if (prefs.getBoolean("memory_context_invalid", false)) emptyList()
            else ConversationHistory.restore(prefs.getString("messages", "[]") ?: "[]")
        }.getOrDefault(emptyList())
        mutableState.update { it.copy(messages = saved, persona = (prefs.getString("persona", it.persona) ?: it.persona).take(1000),
            localRecognitionAvailable = speech.recognitionAvailable, skillCatalog = registry.skills, skillIssues = registry.issues,
            activeSkills = skills.names, skillsAllowed = prefs.getBoolean("skills_allowed", true)) }
        updateLatencyLabels()
        speech.initialize()
        scope.launch {
            runCatching {
                val persona = memory.initializePersona(state.value.persona)
                expireMemories()
                prefs.edit().remove("persona").apply()
                mutableState.update { it.copy(persona = persona, memoryReady = true, memoryNotice = "Only confirmed, relevant facts go to the online model.") }
                memory.facts.collect { facts -> mutableState.update { it.copy(memories = facts) } }
            }.onFailure { mutableState.update { it.copy(memoryNotice = "Local memory could not be opened. Restart the app to retry.") } }
        }
    }

    fun setPersona(value: String) {
        if (!state.value.memoryReady || state.value.busy) return
        val bounded = value.take(1000)
        mutableState.update { it.copy(persona = bounded) }
        memoryChange { memory.setPersona(bounded) }
    }
    private fun clearDerivedContext() {
        save(emptyList())
        mutableState.update { it.copy(messages = emptyList(), transcript = "", partial = "", toolEvents = emptyList()) }
    }
    private suspend fun invalidateDerivedContext() {
        // Commit an invalidation marker before changing Room. A crash between
        // the DB commit and asynchronous preference cleanup must not restore
        // replies containing the forgotten fact on the next process start.
        check(withContext(Dispatchers.IO) { prefs.edit().putBoolean("memory_context_invalid", true).commit() }) { "Could not invalidate conversation history." }
        clearDerivedContext()
    }
    private suspend fun expireMemories() {
        if (memory.hasExpired()) {
            invalidateDerivedContext()
            memory.purgeExpired()
        }
    }
    private fun memoryChange(change: suspend () -> Unit) {
        cancel()
        scope.launch {
            memoryLock.withLock {
                runCatching { invalidateDerivedContext(); change() }
                    .onSuccess { mutableState.update { it.copy(memoryNotice = "Saved locally. Conversation history cleared to remove derived context.") } }
                    .onFailure { failure -> mutableState.update { it.copy(memoryNotice = failure.message ?: "Memory update failed.") } }
            }
        }
    }
    fun saveMemory(id: Long?, text: String, source: String, expiresAt: Long?, confirmed: Boolean) = memoryChange {
        memory.save(id, text, source, expiresAt, confirmed)
    }
    fun forgetMemory(id: Long) = memoryChange { memory.forget(id) }
    fun deleteMemory() = memoryChange {
        memory.deleteAll()
        val default = AgentState().persona
        memory.setPersona(default)
        mutableState.update { it.copy(persona = default) }
    }
    fun exportMemory(uri: Uri) {
        scope.launch {
            memoryLock.withLock {
                runCatching {
                    if (memory.hasExpired()) { cancel(); expireMemories() }
                    val json = memory.export()
                    withContext(Dispatchers.IO) { app.contentResolver.openOutputStream(uri, "wt")!!.bufferedWriter().use { it.write(json) } }
                }.onSuccess { mutableState.update { it.copy(memoryNotice = "Memory and persona exported to your chosen file.") } }
                    .onFailure { mutableState.update { it.copy(memoryNotice = "Export failed. Choose a writable file and try again.") } }
            }
        }
    }
    fun setSkillsAllowed(allowed: Boolean) {
        if (state.value.busy) cancel()
        prefs.edit().putBoolean("skills_allowed", allowed).apply()
        mutableState.update { it.copy(skillsAllowed = allowed) }
    }
    fun toggleSkill(name: String) {
        if (state.value.busy || !state.value.skillsAllowed) return
        runCatching { if (name in skills.names) skills.deactivate(name) else skills.activate(name) }
            .onSuccess { saveSkills() }
            .onFailure { mutableState.update { it.copy(notice = "Skill unavailable or active context limit reached.") } }
    }
    private fun saveSkills() {
        prefs.edit().putString("active_skills", skills.save()).apply()
        mutableState.update { it.copy(activeSkills = skills.names) }
    }
    private fun save(messages: List<ChatMessage>): List<ChatMessage> {
        val saved = ConversationHistory.prepare(messages)
        prefs.edit().putString("messages", saved.json).putBoolean("memory_context_invalid", false).apply()
        return saved.messages
    }
    private fun recordVoiceLatency() {
        if (!voiceTurn || speechEndedAt == 0L) return
        addLatency("voice_latency_ms", SystemClock.elapsedRealtime() - speechEndedAt)
        speechEndedAt = 0L
    }
    private fun addLatency(key: String, elapsed: Long) {
        prefs.edit().putString(key, (readLatencySamples(key).takeLast(99) + elapsed).joinToString(",")).apply()
        updateLatencyLabels()
    }
    private fun readLatencySamples(key: String): List<Long> = (prefs.getString(key, "") ?: "").split(",").mapNotNull { it.toLongOrNull() }.filter { it >= 0 }.takeLast(100)
    private fun updateLatencyLabels() {
        fun label(kind: String, key: String): String {
            val sorted = readLatencySamples(key).sorted()
            if (sorted.isEmpty()) return "$kind latency: no samples yet."
            val median = if (sorted.size % 2 == 1) sorted[sorted.size / 2] else (sorted[sorted.size / 2 - 1] + sorted[sorted.size / 2]) / 2
            val p95 = sorted[ceil(sorted.size * 0.95).toInt() - 1]
            return "$kind latency: ${sorted.size} samples · median $median ms · p95 $p95 ms"
        }
        mutableState.update { it.copy(latency = label("Voice", "voice_latency_ms"), textLatency = label("Text", "text_latency_ms")) }
    }
    fun listen(model: PlanUsageProvider) {
        if (state.value.busy || !model.isConfigured) { mutableState.update { it.copy(notice = "Finish the turn and select an account model first.") }; return }
        provider = model
        sendTranscript = true
        mutableState.update { it.copy(awaitingTranscript = true, turnStatus = TurnStatus.IDLE) }
        voiceTurn = false
        speechEndedAt = 0L
        speech.listen()
    }
    fun listenTest() { sendTranscript = false; speechEndedAt = 0L; speech.listen() }
    fun speak() = speech.speak(state.value.transcript.ifBlank { "Hello. Pixel Agent is running on this phone." })

    fun send(text: String, model: PlanUsageProvider, speakReply: Boolean = false, imageDataUrl: String? = null) {
        val message = text.trim().take(4000)
        if (message.isBlank() || state.value.busy) return
        if (!state.value.memoryReady) { showNotice("Wait for local memory to finish loading."); return }
        if (!model.isConfigured) { mutableState.update { it.copy(notice = "Select an account model first.") }; return }
        sendTranscript = false
        speech.stop()
        provider = model
        voiceTurn = speakReply
        if (!speakReply) speechEndedAt = 0L
        textStartedAt = if (speakReply) 0L else SystemClock.elapsedRealtime()
        sentence = StringBuilder()
        val id = ++turn
        mutableState.update { it.copy(busy = true, imageUploading = imageDataUrl != null, awaitingTranscript = false, partial = "", notice = if (imageDataUrl == null) "Thinking online…" else "Snapshot sent to the online model for this turn. Camera off.", turnId = id, turnStatus = TurnStatus.REQUESTING, toolEvents = emptyList()) }
        job = scope.launch {
            try {
                val input = memoryLock.withLock {
                    expireMemories()
                    val facts = memory.retrieve(message)
                    ConversationContext.build(state.value.persona, state.value.messages, message, registry, skills, state.value.skillsAllowed, MemoryRetrieval.context(facts), imageDataUrl)
                }
                val response = ConversationController(model, dispatcher).run(input, onDelta = { delta -> handler.post {
                    if (id == turn && state.value.busy) {
                        if (textStartedAt != 0L) { addLatency("text_latency_ms", SystemClock.elapsedRealtime() - textStartedAt); textStartedAt = 0L }
                        mutableState.update { it.copy(partial = (it.partial + delta).take(16000), turnStatus = TurnStatus.REQUESTING) }
                        if (voiceTurn) { sentence.append(delta); speakSentences() }
                    }
                } }, onTool = { call, result ->
                    if (id == turn) {
                        saveSkills()
                        mutableState.update { it.copy(turnStatus = TurnStatus.TOOLS, toolEvents = (it.toolEvents + "${call.namespace}.${call.name}: ${if (JSONObject(result).getBoolean("ok")) "complete" else "denied / unavailable"}").takeLast(12)) }
                    }
                })
                if (id != turn) return@launch
                if (voiceTurn && sentence.isNotBlank()) speech.speak(sentence.toString(), enqueue = true)
                sentence.clear()
                val savedMessage = if (imageDataUrl == null) message else "[Camera snapshot sent for this turn; image not retained] ${message.take(3900)}"
                val messages = save(state.value.messages + ChatMessage("user", savedMessage) + ChatMessage("assistant", response))
                mutableState.update { it.copy(messages = messages, busy = false, imageUploading = false, partial = "", notice = if (imageDataUrl == null) "Reply complete." else "Snapshot reply complete. Image discarded; text remains in conversation history.", turnStatus = TurnStatus.COMPLETE) }
            } catch (_: TimeoutCancellationException) {
                fail(id, TurnStatus.TIMEOUT, "Turn timed out after 90 seconds. Retry explicitly when ready.")
            } catch (cancelled: CancellationException) {
                fail(id, TurnStatus.CANCELLED, "Turn cancelled.")
                throw cancelled
            } catch (failure: Exception) {
                val status = when { failure is ModelHttpException && failure.status in setOf(401, 403, 429) -> TurnStatus.ACCESS_ERROR
                    failure is ModelResponseException && failure.code in setOf("subscription_sharing_usage_limit_exceeded", "subscription_sharing_usage_unavailable") -> TurnStatus.ACCESS_ERROR
                    failure is IOException || failure is ModelStreamInterruptedException -> TurnStatus.NETWORK_ERROR; else -> TurnStatus.FAILED }
                fail(id, status, "${status.label}. Check connectivity, account access or plan limits, then retry explicitly.")
            } finally {
                // Do not retain a completed coroutine frame containing image bytes.
                if (id == turn) job = null
            }
        }
    }

    private fun speakSentences() {
        while (sentence.isNotEmpty()) {
            val punctuation = sentence.indexOfFirst { it in ".!?\n" }
            val end = if (punctuation >= 0) minOf(punctuation + 1, 500) else if (sentence.length >= 500) 500 else return
            val part = sentence.substring(0, end).trim()
            sentence.delete(0, end)
            if (part.isNotEmpty()) speech.speak(part, enqueue = true)
        }
    }
    private fun fail(id: Long, status: TurnStatus, notice: String) {
        if (id != turn) return
        voiceTurn = false
        speechEndedAt = 0L
        textStartedAt = 0L
        sentence.clear()
        speech.stop()
        mutableState.update { it.copy(busy = false, imageUploading = false, partial = "", turnStatus = status, notice = "$notice Partial reply discarded; streamed speech may already have been heard. No automatic retry.") }
    }
    fun stopAudio() { sendTranscript = false; speechEndedAt = 0L; speech.stop(); mutableState.update { it.copy(awaitingTranscript = false) } }
    fun cancel() {
        // Invalidate callbacks and clear state before cancelling. Old jobs must
        // never reset state or cancel the request belonging to the next turn.
        turn++
        sendTranscript = false
        voiceTurn = false
        speechEndedAt = 0L
        textStartedAt = 0L
        sentence.clear()
        provider?.cancel()
        job?.cancel()
        job = null
        speech.stop()
        mutableState.update { it.copy(busy = false, imageUploading = false, awaitingTranscript = false, partial = "", turnStatus = TurnStatus.CANCELLED, notice = "Cancelled. Partial reply discarded; streamed speech may already have been heard.") }
    }
    fun showNotice(message: String) { mutableState.update { it.copy(notice = message) } }
    fun permissionDenied() { mutableState.update { it.copy(notice = "Microphone access was declined.") } }
    // The service cancels on termination; no audio session is restored after process death.
}
