package dev.pixelagent

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.k2fsa.sherpa.onnx.*
import dev.pixelagent.model.CredentialStore
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.ByteBuffer
import java.nio.ByteOrder

@RunWith(AndroidJUnit4::class)
class FeasibilityTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    @Test fun keystoreRecordSurvivesNewStoreInstanceWithoutPlaintext() {
        // Test-package storage only; never touches the user's model credentials.
        val context = instrumentation.targetContext
        val marker = "test-only-credential-1234567890"
        val value = JSONObject().put("host", "test-host").put("token", marker)
        CredentialStore(context, "test-model-credentials").write(value)
        assertEquals(marker, CredentialStore(context, "test-model-credentials").read().getString("token"))
        val bytes = java.io.File(context.noBackupFilesDir, "test-model-credentials").readBytes()
        assertFalse(String(bytes, Charsets.ISO_8859_1).contains(marker))
    }
    @Test fun nativeKeywordEngineDetectsKnownPrerecordedPhrase() {
        val config = KeywordSpotterConfig(modelConfig = OnlineModelConfig(transducer = OnlineTransducerModelConfig(
            encoder = "keyword/encoder-epoch-12-avg-2-chunk-16-left-64.int8.onnx",
            decoder = "keyword/decoder-epoch-12-avg-2-chunk-16-left-64.onnx",
            joiner = "keyword/joiner-epoch-12-avg-2-chunk-16-left-64.int8.onnx"),
            tokens = "keyword/tokens.txt", modelType = "zipformer2"), keywordsFile = "keyword/keywords.txt")
        val engine = KeywordSpotter(instrumentation.targetContext.assets, config)
        val stream = engine.createStream("▁ L IGHT ▁UP")
        try {
            assertTrue(stream.ptr != 0L)
            val wav = instrumentation.context.assets.open("keyword.wav").use { it.readBytes() }
            val buffer = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN)
            // Upstream fixture is canonical 16 kHz mono PCM16 WAV.
            assertEquals(16000, buffer.getInt(24))
            assertEquals(1, buffer.getShort(22).toInt())
            assertEquals(16, buffer.getShort(34).toInt())
            buffer.position(44)
            val audio = FloatArray(buffer.remaining() / 2) { buffer.short / 32768f }
            stream.acceptWaveform(audio + FloatArray(8000), 16000)
            var detected = false
            while (engine.isReady(stream)) {
                engine.decode(stream)
                if (engine.getResult(stream).keyword.isNotBlank()) detected = true
            }
            assertTrue("Expected LIGHT UP in the upstream fixture", detected)
        } finally { stream.release(); engine.release() }
    }
}
