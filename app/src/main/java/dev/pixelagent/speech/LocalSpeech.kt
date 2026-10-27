package dev.pixelagent.speech

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import dev.pixelagent.conversation.AudioState
import java.util.Locale

/** Owns microphone/recognizer/TTS lifecycle. Calls must run on the main thread. */
class LocalSpeech(
    context: Context,
    private val onState: (AudioState) -> Unit,
    private val onTranscript: (String) -> Unit,
    private val onNotice: (String) -> Unit,
    private val onVoice: (String?) -> Unit,
) {
    private val appContext = context.applicationContext
    private var recognizer: SpeechRecognizer? = null
    private var recognitionGeneration = 0
    private var tts: TextToSpeech? = null
    private var closed = false
    private var utteranceId: String? = null
    private var localVoiceReady = false
    val recognitionAvailable: Boolean
        get() = SpeechRecognizer.isOnDeviceRecognitionAvailable(appContext)

    fun initialize() {
        tts = TextToSpeech(appContext) { status ->
            appContext.mainExecutor.execute {
                if (closed) return@execute
                val engine = tts
                val voice = if (status == TextToSpeech.SUCCESS) {
                    engine?.voices?.filter {
                        !it.isNetworkConnectionRequired && it.locale.language == "en" &&
                            TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED !in it.features.orEmpty()
                    }?.sortedWith(compareByDescending<android.speech.tts.Voice> {
                        it.locale.country == "AU"
                    }.thenByDescending { it.quality })?.firstOrNull()
                } else null
                localVoiceReady = voice != null && engine?.setVoice(voice) == TextToSpeech.SUCCESS
                onVoice(if (localVoiceReady) voice?.name else null)
            }
        }
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String?) = postForUtterance(id) { onState(AudioState.SPEAKING) }
            override fun onDone(id: String?) = postForUtterance(id) {
                utteranceId = null
                onState(AudioState.IDLE)
            }
            @Deprecated("Required by Android")
            override fun onError(id: String?) = postForUtterance(id) {
                utteranceId = null
                onState(AudioState.IDLE)
                onNotice("Local speech playback failed. Check installed voice data.")
            }
        })
    }

    private fun postForUtterance(id: String?, action: () -> Unit) {
        appContext.mainExecutor.execute {
            if (!closed && id != null && id == utteranceId) action()
        }
    }

    fun listen() {
        stop()
        if (!recognitionAvailable) {
            onNotice("No on-device recognizer is installed. No cloud fallback was used.")
            return
        }
        try {
            val generation = recognitionGeneration
            val speech = SpeechRecognizer.createOnDeviceSpeechRecognizer(appContext)
            recognizer = speech
            speech.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {
                    if (closed || generation != recognitionGeneration) return
                    onState(AudioState.LISTENING)
                    onNotice("Say a short phrase. Audio is handled by the on-device recognizer.")
                }
                override fun onBeginningOfSpeech() = Unit
                override fun onRmsChanged(rmsdB: Float) = Unit
                override fun onBufferReceived(buffer: ByteArray?) = Unit
                override fun onEndOfSpeech() {
                    if (!closed && generation == recognitionGeneration) onState(AudioState.TRANSCRIBING)
                }
                override fun onError(error: Int) {
                    if (closed || generation != recognitionGeneration) return
                    releaseRecognizer()
                    onState(AudioState.IDLE)
                    onNotice(recognitionError(error))
                }
                override fun onResults(results: Bundle?) {
                    if (closed || generation != recognitionGeneration) return
                    val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull().orEmpty()
                    onTranscript(text)
                    releaseRecognizer()
                    onState(AudioState.IDLE)
                    onNotice(if (text.isBlank()) "No speech detected. Try again." else "Transcribed locally. Model experiments run separately below.")
                }
                override fun onPartialResults(partialResults: Bundle?) {
                    if (closed || generation != recognitionGeneration) return
                    partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull()?.let(onTranscript)
                }
                override fun onEvent(eventType: Int, params: Bundle?) = Unit
            })
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            }
            onTranscript("")
            onState(AudioState.LISTENING)
            speech.startListening(intent)
        } catch (exception: RuntimeException) {
            releaseRecognizer()
            onState(AudioState.IDLE)
            onNotice("Could not start local recognition: ${exception.javaClass.simpleName}.")
        }
    }

    fun speak(text: String) {
        stop()
        if (!localVoiceReady) {
            onNotice("Install an offline English voice in Android text-to-speech settings, then reopen the app.")
            return
        }
        val id = java.util.UUID.randomUUID().toString()
        utteranceId = id
        onState(AudioState.SPEAKING)
        if (tts?.speak(text.take(2000), TextToSpeech.QUEUE_FLUSH, null, id) != TextToSpeech.SUCCESS) {
            utteranceId = null
            onState(AudioState.IDLE)
            onNotice("Local speech playback could not start.")
        }
    }

    private fun releaseRecognizer() {
        recognitionGeneration++
        recognizer?.cancel()
        recognizer?.destroy()
        recognizer = null
    }

    fun stop() {
        utteranceId = null
        releaseRecognizer()
        tts?.stop()
        onState(AudioState.IDLE)
    }

    fun close() {
        closed = true
        stop()
        tts?.shutdown()
        tts = null
    }

    private fun recognitionError(code: Int): String = when (code) {
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission is required."
        SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED -> "The device recognizer does not support the current phone language."
        SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> "Download the offline recognition model for the phone language, then retry."
        SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "No clear speech detected. Try another short phrase."
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "The recognizer is busy. Stop and retry."
        else -> "Local recognition failed (code $code). No cloud fallback was used."
    }
}

