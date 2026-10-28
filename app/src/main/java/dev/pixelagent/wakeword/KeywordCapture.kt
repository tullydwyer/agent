package dev.pixelagent.wakeword

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import com.k2fsa.sherpa.onnx.*

/** Blocking CPU worker. Returns only AFTER releasing the mic, before STT may acquire it. */
class KeywordCapture(context: Context) : AutoCloseable {
    private val engine = KeywordSpotter(context.assets, KeywordSpotterConfig(
        modelConfig = OnlineModelConfig(transducer = OnlineTransducerModelConfig(
            encoder = "keyword/encoder-epoch-12-avg-2-chunk-16-left-64.int8.onnx",
            decoder = "keyword/decoder-epoch-12-avg-2-chunk-16-left-64.onnx",
            joiner = "keyword/joiner-epoch-12-avg-2-chunk-16-left-64.int8.onnx"),
            tokens = "keyword/tokens.txt", modelType = "zipformer2", numThreads = 1),
        keywordsFile = "keyword/keywords.txt"))

    @android.annotation.SuppressLint("MissingPermission") // Service checks; permission loss throws and ends session.
    fun awaitKeyword(running: () -> Boolean, onListening: () -> Unit): Boolean {
        val stream = engine.createStream()
        var recorder: AudioRecord? = null
        try {
            check(stream.ptr != 0L)
            if (!running()) return false
            val minimum = AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            check(minimum > 0)
            recorder = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, 16000,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minimum, 6400))
            check(recorder.state == AudioRecord.STATE_INITIALIZED)
            recorder.startRecording()
            check(recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING)
            onListening()
            val buffer = ShortArray(1600)
            while (running()) {
                check(recorder.activeRecordingConfiguration?.isClientSilenced != true) { "microphone_silenced" }
                val count = recorder.read(buffer, 0, buffer.size, AudioRecord.READ_NON_BLOCKING)
                check(count >= 0) { "microphone_disconnected" }
                if (count == 0) { Thread.sleep(20); continue }
                stream.acceptWaveform(FloatArray(count) { buffer[it] / 32768f }, 16000)
                while (running() && engine.isReady(stream)) {
                    engine.decode(stream)
                    if (engine.getResult(stream).keyword.isNotBlank()) return true
                }
            }
            return false
        } finally {
            runCatching { recorder?.stop() }
            recorder?.release()
            stream.release()
        }
    }
    override fun close() { engine.release() }
}
