package dev.pixelagent.wakeword

import android.Manifest
import android.app.*
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.*
import dev.pixelagent.MainActivity
import dev.pixelagent.R
import dev.pixelagent.conversation.AgentRuntime
import dev.pixelagent.conversation.AudioState
import dev.pixelagent.model.AgentConnection
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

data class HandsFreeState(
    val active: Boolean = false,
    val listening: Boolean = false,
    val followUp: Boolean = false,
    val detections: Int = 0,
    val elapsedMs: Long = 0,
    val durationMs: Long = SessionPolicy.DEFAULT_DURATION_MS,
    val notice: String = "Hands-free is off. Start a session to use the wake word.",
)

/** Explicit, bounded, non-sticky microphone session. Nothing starts at boot or after process death. */
class HandsFreeService : Service() {
    companion object {
        const val STOP = "stop"
        const val DURATION = "durationMs"
        private val mutableState = MutableStateFlow(HandsFreeState())
        val state = mutableState.asStateFlow()
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var agent: AgentRuntime
    private var startedAt = 0L
    private var wakeLock: PowerManager.WakeLock? = null
    private var focus: AudioFocusRequest? = null
    @Volatile private var running = false
    private var ending = "Session stopped. Tap Start hands-free to listen again."
    private var lastNotification = ""
    private var wakeWord = WakeWordSettings.DEFAULT
    private var worker: Job? = null
    private val prefs by lazy { getSharedPreferences("hands_free", 0) }

    override fun onBind(intent: Intent?) = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == STOP) { finish("Session stopped. Tap Start hands-free to listen again."); return START_NOT_STICKY }
        if (running) return START_NOT_STICKY
        if (mutableState.value.active) { stopSelf(); return START_NOT_STICKY }
        wakeWord = WakeWordSettings(this).phrase
        agent = AgentRuntime.get(application)
        val provider = AgentConnection.get(this).provider
        val notifications = getSystemService(NotificationManager::class.java)
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED ||
            (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) ||
            !notifications.areNotificationsEnabled()) {
            finish("Microphone and notifications must be allowed. Enable them in Android settings, then start again.")
            return START_NOT_STICKY
        }
        if (KeywordService.state.value.active || !provider.isConfigured || !agent.state.value.localRecognitionAvailable ||
            agent.state.value.localVoice == null || agent.state.value.busy || agent.state.value.audio != AudioState.IDLE) {
            finish("Finish other audio and connect a model with local speech ready, then start again.")
            return START_NOT_STICKY
        }
        val duration = SessionPolicy.duration(intent?.getLongExtra(DURATION, SessionPolicy.DEFAULT_DURATION_MS) ?: SessionPolicy.DEFAULT_DURATION_MS)
        notifications.createNotificationChannel(NotificationChannel("hands_free", "Hands-free conversation", NotificationManager.IMPORTANCE_LOW))
        if (notifications.getNotificationChannel("hands_free").importance == NotificationManager.IMPORTANCE_NONE) {
            finish("Enable the Hands-free conversation notification channel in Android settings, then start again.")
            return START_NOT_STICKY
        }
        try {
            startForeground(3, notification("Starting local wake word…"), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
            running = true
            startedAt = SystemClock.elapsedRealtime()
            prefs.edit().putBoolean("interrupted", true).apply()
            mutableState.value = HandsFreeState(active = true, durationMs = duration, notice = "Loading local wake word…")
            wakeLock = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "PixelAgent:hands-free").apply { acquire(duration + 10000) }
            val audio = getSystemService(AudioManager::class.java)
            focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                .setOnAudioFocusChangeListener { change -> if (change < 0) finish("Audio focus changed. Session stopped; start again when ready.") }.build()
            check(audio.requestAudioFocus(focus!!) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
            scope.launch { delay(duration); finish("Session time limit reached. Start a new session when ready.") }
            scope.launch {
                while (isActive && running) {
                    delay(1000)
                    mutableState.update { it.copy(elapsedMs = SystemClock.elapsedRealtime() - startedAt) }
                    if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED || !notifications.areNotificationsEnabled() ||
                        notifications.getNotificationChannel("hands_free").importance == NotificationManager.IMPORTANCE_NONE) {
                        finish("Microphone or notification access changed. Allow access, then explicitly restart.")
                    }
                }
            }
            scope.launch {
                agent.state.collect { current ->
                    if (!running || mutableState.value.listening) return@collect
                    if (current.audio == AudioState.LISTENING) publish(if (mutableState.value.followUp) "Listening for your reply · no wake word needed" else "Wake word heard. Listening to your message…")
                    else if (current.audio == AudioState.SPEAKING) publish("Speaking locally · wake word paused")
                    else if (current.busy) publish("Thinking online · wake word paused")
                }
            }
            worker = scope.launch {
                var capture: KeywordCapture? = null
                try {
                    // Keep ownership through cancellation during native initialization, so finally can release it.
                    withContext(NonCancellable + Dispatchers.IO) { capture = KeywordCapture(this@HandsFreeService, wakeWord) }
                    ensureActive()
                    while (isActive && running) {
                        val detected = withContext(Dispatchers.IO) {
                            capture!!.awaitKeyword({ running }) {
                                scope.launch { if (running) publish("Listening locally · say $wakeWord", listening = true) }
                            }
                        }
                        ensureActive()
                        if (!detected || !running) break
                        mutableState.update { it.copy(detections = it.detections + 1, listening = false, followUp = false) }
                        while (isActive && running) {
                            publish(if (mutableState.value.followUp) "Ready for your reply after the tone" else "Wake word heard. Listening to your message…")
                            val tone = ToneGenerator(AudioManager.STREAM_MUSIC, 45)
                            try { tone.startTone(ToneGenerator.TONE_PROP_BEEP, 100); delay(180) } finally { tone.release() }
                            val previousTurn = agent.state.value.turnId
                            // Detector owns no mic here. Await final transcript dispatch before considering idle.
                            withTimeout(120_000) {
                                agent.listen(provider)
                                withTimeoutOrNull(SessionPolicy.LISTEN_WINDOW_MS) {
                                    agent.state.first { !it.awaitingTranscript }
                                }
                                // Silence returns to keyword mode. A transcript accepted at the deadline still finishes normally.
                                if (agent.state.value.awaitingTranscript) agent.stopAudio()
                                agent.state.first(SessionPolicy::turnFinished)
                            }
                            if (SessionPolicy.failed(agent.state.value)) {
                                finish("${agent.state.value.turnStatus.label}. Session stopped; check connection/account and start again.")
                                break
                            }
                            if (!SessionPolicy.hasReply(previousTurn, agent.state.value)) break
                            publish("Reply finished · listening again after a short pause")
                            delay(SessionPolicy.COOLDOWN_MS) // Speaker tail must not become the next transcript.
                            mutableState.update { it.copy(followUp = true) }
                        }
                        mutableState.update { it.copy(followUp = false) }
                        if (running) publish("No speech heard · returning to $wakeWord")
                    }
                } catch (cancelled: CancellationException) {
                    if (cancelled is TimeoutCancellationException) finish("Voice turn timed out. Start a new session to retry.")
                    throw cancelled
                } catch (_: Exception) {
                    finish("Session failed. Check microphone, local speech, and model files; start again explicitly.")
                } catch (_: LinkageError) {
                    finish("Local keyword engine could not load. Session stopped.")
                } finally {
                    withContext(NonCancellable) {
                        withContext(Dispatchers.IO) { capture?.close() }
                        mutableState.update { it.copy(active = false, listening = false, notice = ending) }
                    }
                }
            }
        } catch (_: RuntimeException) {
            finish("Android refused the session or audio focus. Start again while Pixel Agent is visible.")
        }
        return START_NOT_STICKY
    }

    private fun notification(text: String): Notification {
        val stop = PendingIntent.getService(this, 3, Intent(this, HandsFreeService::class.java).setAction(STOP), PendingIntent.FLAG_IMMUTABLE)
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, "hands_free").setSmallIcon(R.drawable.ic_agent)
            .setContentTitle("Pixel Agent · hands-free session").setContentText(text)
            .setContentIntent(open).setOngoing(true).setVisibility(Notification.VISIBILITY_PUBLIC)
            .addAction(Notification.Action.Builder(null, "Stop", stop).build()).build()
    }
    private fun publish(text: String, listening: Boolean = false) {
        if (!running) return
        mutableState.update { it.copy(notice = text, listening = listening) }
        if (text != lastNotification) {
            lastNotification = text
            getSystemService(NotificationManager::class.java).notify(3, notification(text))
        }
    }
    private fun finish(message: String) {
        ending = message
        running = false // Worker exits and releases its mic before its coroutine finishes.
        mutableState.update { it.copy(active = startedAt != 0L, listening = false, notice = message) }
        stopSelf()
    }
    override fun onDestroy() {
        val ownedSession = startedAt != 0L
        running = false
        if (ownedSession) agent.cancel()
        scope.cancel()
        focus?.let { getSystemService(AudioManager::class.java).abandonAudioFocusRequest(it) }
        wakeLock?.let { if (it.isHeld) it.release() }
        if (ownedSession) prefs.edit().putBoolean("interrupted", false).putString("last_stop", ending).apply()
        mutableState.update { it.copy(active = worker?.isCompleted == false, listening = false, notice = ending) }
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }
}
