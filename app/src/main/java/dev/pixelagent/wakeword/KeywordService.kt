package dev.pixelagent.wakeword

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import com.k2fsa.sherpa.onnx.*
import dev.pixelagent.MainActivity
import dev.pixelagent.R
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

data class KeywordState(val active: Boolean = false, val detections: Int = 0, val notice: String = "Session stopped", val elapsedMs: Long = 0)

/** Five-minute, explicitly started feasibility session. It never starts on boot or restarts itself. */
class KeywordService : Service() {
    companion object {
        private val mutableState = MutableStateFlow(KeywordState())
        val state = mutableState.asStateFlow()
    }
    private val running = AtomicBoolean(false)
    private val handler = Handler(Looper.getMainLooper())
    private var worker: Thread? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var startedAt = 0L
    private var wakeWord = WakeWordSettings.DEFAULT
    @Volatile private var destroyed = false
    private val expire = Runnable { stopSelf() }
    private val tick = object : Runnable {
        override fun run() {
            if (!running.get()) return
            mutableState.value = mutableState.value.copy(elapsedMs = SystemClock.elapsedRealtime() - startedAt)
            handler.postDelayed(this, 1000)
        }
    }
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "stop") { stopSelf(); return START_NOT_STICKY }
        if (running.get()) return START_NOT_STICKY
        if (HandsFreeService.state.value.active) { stopSelf(); return START_NOT_STICKY }
        wakeWord = WakeWordSettings(this).phrase
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            mutableState.value = KeywordState(notice = "Microphone permission required")
            stopSelf(); return START_NOT_STICKY
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("keyword", "Local keyword session", NotificationManager.IMPORTANCE_LOW))
        val stop = PendingIntent.getService(this, 1, Intent(this, KeywordService::class.java).setAction("stop"), PendingIntent.FLAG_IMMUTABLE)
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val notification = Notification.Builder(this, "keyword").setSmallIcon(R.drawable.ic_agent)
            .setContentTitle("Pixel Agent microphone active").setContentText("Local keyword trial · say $wakeWord · ends in 5 minutes")
            .setContentIntent(open).setOngoing(true).setVisibility(Notification.VISIBILITY_PUBLIC)
            .addAction(Notification.Action.Builder(null, "Stop", stop).build()).build()
        try {
            startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            startedAt = SystemClock.elapsedRealtime()
            mutableState.value = KeywordState(active = true, notice = "Loading local keyword model")
            running.set(true)
            wakeLock = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "PixelAgent:keyword").apply { acquire(310000) }
            handler.postDelayed(expire, 300000)
            handler.post(tick)
            worker = thread(name = "local-keyword") { capture() }
        } catch (_: RuntimeException) {
            mutableState.value = KeywordState(notice = "Android refused the microphone session. Start again while the app is visible.")
            stopSelf()
        }
        return START_NOT_STICKY
    }

    @android.annotation.SuppressLint("MissingPermission") // Checked immediately before foreground start; revocation fails closed.
    private fun capture() {
        var recorder: AudioRecord? = null
        var engine: KeywordSpotter? = null
        var stream: OnlineStream? = null
        try {
            engine = KeywordSpotter(assets, KeywordSpotterConfig(
                modelConfig = OnlineModelConfig(transducer = OnlineTransducerModelConfig(
                    encoder = "keyword/encoder-epoch-12-avg-2-chunk-16-left-64.int8.onnx",
                    decoder = "keyword/decoder-epoch-12-avg-2-chunk-16-left-64.onnx",
                    joiner = "keyword/joiner-epoch-12-avg-2-chunk-16-left-64.int8.onnx"),
                    tokens = "keyword/tokens.txt", modelType = "zipformer2", numThreads = 1),
                keywordsFile = "keyword/keywords.txt"))
            stream = engine.createStream(WakeWordSettings(this).tokens(wakeWord))
            check(stream.ptr != 0L)
            if (!running.get()) return
            val minBuffer = AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            check(minBuffer > 0)
            recorder = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, 16000, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuffer, 6400))
            check(recorder.state == AudioRecord.STATE_INITIALIZED)
            recorder.startRecording()
            check(recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING)
            publish("Listening locally for $wakeWord")
            val buffer = ShortArray(1600)
            while (running.get()) {
                check(recorder.activeRecordingConfiguration?.isClientSilenced != true) { "Microphone capture was silenced" }
                val count = recorder.read(buffer, 0, buffer.size, AudioRecord.READ_NON_BLOCKING)
                check(count >= 0) { "Microphone disconnected" }
                if (count == 0) { Thread.sleep(20); continue }
                stream.acceptWaveform(FloatArray(count) { buffer[it] / 32768f }, 16000)
                while (running.get() && engine.isReady(stream)) {
                    engine.decode(stream)
                    if (engine.getResult(stream).keyword.isNotBlank()) {
                        mutableState.value = mutableState.value.copy(detections = mutableState.value.detections + 1)
                        publish("Keyword detected locally")
                        engine.reset(stream)
                    }
                }
            }
        } catch (_: Exception) { publish("Keyword session failed; check permission, microphone availability, and model files") }
        catch (_: LinkageError) { publish("Keyword native library could not load on this device") }
        finally {
            runCatching { recorder?.stop() }; recorder?.release()
            stream?.release(); engine?.release()
            mutableState.value = mutableState.value.copy(active = false)
            handler.post { if (!destroyed) stopSelf() }
        }
    }
    private fun publish(message: String) {
        mutableState.value = mutableState.value.copy(notice = message, elapsedMs = SystemClock.elapsedRealtime() - startedAt)
    }
    override fun onDestroy() {
        destroyed = true
        running.set(false)
        handler.removeCallbacks(expire)
        handler.removeCallbacks(tick)
        worker?.join(2000)
        wakeLock?.let { if (it.isHeld) it.release() }
        val stillStopping = worker?.isAlive == true
        mutableState.value = mutableState.value.copy(active = stillStopping,
            notice = if (stillStopping) "Stopping local keyword engine…" else if (mutableState.value.notice.contains("failed") || mutableState.value.notice.contains("could not")) mutableState.value.notice else "Session stopped",
            elapsedMs = if (startedAt == 0L) 0 else SystemClock.elapsedRealtime() - startedAt)
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }
}
