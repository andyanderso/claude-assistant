package com.andyanderso.claudecopilot

import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale

/**
 * Speech in (Android's SpeechRecognizer) and speech out (TextToSpeech).
 * Must be created and used on the main thread; callbacks always arrive on the main thread.
 *
 * Every operation has exactly one completion callback. Cancelling clears callbacks first,
 * so a cancelled listen or utterance never calls back.
 */
class SpeechIO(context: Context) : RecognitionListener {

    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())

    // ---- speech out ----
    private val tts: TextToSpeech
    private var ttsState = 0                 // 0 = initializing, 1 = ready, -1 = failed
    private var pending: Pair<String, () -> Unit>? = null
    private var currentUtterance: String? = null
    private var onSpoken: (() -> Unit)? = null
    private var utteranceCount = 0

    // ---- speech in ----
    private var recognizer: SpeechRecognizer? = null
    private var onPartial: ((String) -> Unit)? = null
    private var onHeard: ((String) -> Unit)? = null
    private var onNothing: (() -> Unit)? = null
    private var onFailed: ((String) -> Unit)? = null

    init {
        tts = TextToSpeech(app) { status -> onTtsReady(status) }
    }

    private fun onTtsReady(status: Int) {
        ttsState = if (status == TextToSpeech.SUCCESS) 1 else -1
        if (ttsState == 1) {
            tts.setLanguage(Locale.US)   // setter returns a status code, so it isn't a Kotlin property
            tts.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}
                override fun onDone(utteranceId: String?) = finishedSpeaking(utteranceId)
                override fun onError(utteranceId: String?, errorCode: Int) = finishedSpeaking(utteranceId)
                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) = finishedSpeaking(utteranceId)
            })
        }
        // Something was asked to be spoken before the engine was ready: do it now.
        main.post {
            pending?.let { (text, done) -> pending = null; speak(text, done) }
        }
    }

    /** Speak [text]; [done] runs when it finishes (or immediately if speech output is unavailable). */
    fun speak(text: String, done: () -> Unit) {
        when (ttsState) {
            0 -> { pending = text to done; return }
            -1 -> { main.post(done); return }
        }
        val id = "u${++utteranceCount}"
        currentUtterance = id
        onSpoken = done
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, id)
    }

    private fun finishedSpeaking(id: String?) {
        main.post {
            if (id == null || id != currentUtterance) return@post   // stale or cancelled
            currentUtterance = null
            val cb = onSpoken
            onSpoken = null
            cb?.invoke()
        }
    }

    fun stopSpeaking() {
        pending = null
        onSpoken = null
        currentUtterance = null
        if (ttsState == 1) tts.stop()
    }

    /** Short acknowledgement tone so you know Claude Copilot heard "Hey Claude". */
    fun chime() {
        try {
            val tone = ToneGenerator(AudioManager.STREAM_MUSIC, 70)
            tone.startTone(ToneGenerator.TONE_PROP_ACK, 160)
            main.postDelayed({ tone.release() }, 400)
        } catch (_: RuntimeException) {
            // Some devices refuse to create a ToneGenerator; the chime is optional.
        }
    }

    /**
     * Listen for one utterance.
     * @param onPartial live transcript while you talk
     * @param onHeard   final text
     * @param onNothing silence or nothing understood
     * @param onFailed  a real problem, with a sentence safe to show and say
     */
    fun listen(
        onPartial: (String) -> Unit,
        onHeard: (String) -> Unit,
        onNothing: () -> Unit,
        onFailed: (String) -> Unit
    ) {
        cancelListening()
        if (!SpeechRecognizer.isRecognitionAvailable(app)) {
            main.post { onFailed("Speech recognition isn't available on this phone.") }
            return
        }
        this.onPartial = onPartial
        this.onHeard = onHeard
        this.onNothing = onNothing
        this.onFailed = onFailed

        // A fresh recognizer per utterance avoids stale state after errors on some phones.
        val r = SpeechRecognizer.createSpeechRecognizer(app)
        r.setRecognitionListener(this)
        recognizer = r

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-US")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            // Allow a short pause mid-thought before deciding you're done. Some recognizers ignore this.
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1500L)
        }
        r.startListening(intent)
    }

    /** Stop recording and use what was heard so far (the result arrives via onHeard). */
    fun finishListening() {
        recognizer?.stopListening()
    }

    fun cancelListening() {
        onPartial = null; onHeard = null; onNothing = null; onFailed = null
        recognizer?.let {
            it.cancel()
            it.destroy()
        }
        recognizer = null
    }

    /** Stop everything: listening and talking. */
    fun cancelAll() {
        cancelListening()
        stopSpeaking()
    }

    fun shutdown() {
        cancelAll()
        tts.shutdown()
    }

    // ---- RecognitionListener ----

    override fun onPartialResults(partialResults: Bundle?) {
        val text = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
        if (!text.isNullOrBlank()) onPartial?.invoke(text)
    }

    override fun onResults(results: Bundle?) {
        val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.trim()
        val heard = onHeard
        val nothing = onNothing
        cancelListening()
        if (text.isNullOrEmpty()) nothing?.invoke() else heard?.invoke(text)
    }

    override fun onError(error: Int) {
        val nothing = onNothing
        val failed = onFailed
        cancelListening()
        when (error) {
            SpeechRecognizer.ERROR_NO_MATCH,
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> nothing?.invoke()
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> failed?.invoke("Claude Copilot needs microphone permission.")
            SpeechRecognizer.ERROR_NETWORK,
            SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> failed?.invoke("No connection for speech recognition.")
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> failed?.invoke("The speech recognizer is busy. Try again.")
            else -> failed?.invoke("I couldn't hear that. Try again.")
        }
    }

    override fun onReadyForSpeech(params: Bundle?) {}
    override fun onBeginningOfSpeech() {}
    override fun onRmsChanged(rmsdB: Float) {}
    override fun onBufferReceived(buffer: ByteArray?) {}
    override fun onEndOfSpeech() {}
    override fun onEvent(eventType: Int, params: Bundle?) {}
}
