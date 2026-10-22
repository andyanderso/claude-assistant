package com.andyanderso.copilot

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.rementia.openwakeword.lib.WakeWordEngine
import com.rementia.openwakeword.lib.model.DetectionMode
import com.rementia.openwakeword.lib.model.WakeWordModel
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Does all the work: listens for "Hey Claude", runs the conversation, and keeps going
 * while the screen is off or another app is in front (when the user allows that).
 *
 * The flow:
 *   WAITING (wake-word engine has the mic)
 *     → "Hey Claude" → chime → LISTENING (speech recognizer has the mic)
 *     → THINKING (Claude API) → SPEAKING → LISTENING again for a follow-up
 *     → silence or "that's all" → back to WAITING
 *
 * Only one thing holds the microphone at a time; we always stop one before starting the other.
 *
 * It runs as a foreground service of type "microphone", which Android requires for listening
 * in the background, and which Android 14+ only lets an app start while it is on screen.
 * That's why it is always started from MainActivity and never on boot.
 */
class AssistantService : Service() {

    companion object {
        private const val TAG = "Copilot"
        const val ACTION_START = "com.andyanderso.copilot.START"   // begin / resume waiting for the wake word
        const val ACTION_TAP = "com.andyanderso.copilot.TAP"       // the orb was tapped

        /** Train this with openWakeWord and drop it in app/src/main/assets/ (see README). */
        const val WAKE_MODEL_ASSET = "hey_claude.onnx"

        private const val CHANNEL_ID = "listening"
        private const val NOTIFICATION_ID = 1
        private const val MIC_HANDOFF_MS = 350L                // let one mic user let go before the next starts
        private const val CONVERSATION_RESET_MS = 10 * 60_000L // forget context after 10 quiet minutes
        private const val MAX_HISTORY = 20                     // messages sent to Claude per turn

        private val DISMISSAL = Regex(
            "^(stop|cancel|never ?mind|that's all|that is all|that's it|thanks,? that's (all|it)|thank you,? that's (all|it)|goodbye|bye)[.!]?$",
            RegexOption.IGNORE_CASE
        )

        fun start(context: Context) = send(context, ACTION_START)
        fun tap(context: Context) = send(context, ACTION_TAP)
        fun stop(context: Context) {
            context.stopService(Intent(context, AssistantService::class.java))
        }

        private fun send(context: Context, action: String) {
            ContextCompat.startForegroundService(
                context, Intent(context, AssistantService::class.java).setAction(action)
            )
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var prefs: Prefs
    private lateinit var claude: ClaudeClient
    private lateinit var speech: SpeechIO

    // Wake word
    private var engine: WakeWordEngine? = null
    private var engineThreshold = -1f
    private var detectionJob: Job? = null
    private var wakeStartJob: Job? = null
    private var engineFailures = 0
    private val engineScope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, t ->
            // Usually: the mic is in use by something else (a call, another recorder).
            Log.e(TAG, "Wake-word engine failed", t)
            scope.launch { onEngineFailure() }
        }
    )

    // Conversation
    private val history = mutableListOf<ChatMessage>()
    private var inConversation = false
    private var lastExchangeAt = 0L
    private var focusRequest: AudioFocusRequest? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        claude = ClaudeClient(prefs)
        speech = SpeechIO(this)
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Android requires a started foreground service to call startForeground right away.
        if (!goForeground()) {
            stopSelf()
            return START_NOT_STICKY
        }
        when (intent?.action) {
            ACTION_TAP -> onTap()
            else -> if (!inConversation) startWakeWord()
        }
        // Don't ask Android to restart us later: it would be from the background,
        // where a microphone service isn't allowed to start anyway.
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        inConversation = false
        wakeStartJob?.cancel()
        detectionJob?.cancel()
        engine?.let { retire(it) }
        engine = null
        speech.shutdown()
        abandonAudioFocus()
        scope.cancel()
        AssistantBus.phase.value = Phase.OFF
        AssistantBus.hint.value = ""
        super.onDestroy()
    }

    // ------------------------------------------------------------------ foreground + notification

    private fun goForeground(): Boolean {
        val micGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        if (!micGranted) return false
        // The "microphone" service type only exists on Android 11+; passing it on 10 throws.
        val type = if (Build.VERSION.SDK_INT >= 30) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0
        return try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification("Starting…"), type)
            true
        } catch (e: Exception) {
            // e.g. Android refused because the app wasn't on screen when this was started.
            Log.w(TAG, "Couldn't start listening service", e)
            AssistantBus.phase.value = Phase.ERROR
            AssistantBus.hint.value = "Open Copilot to start listening."
            false
        }
    }

    private fun createChannel() {
        val channel = NotificationChannel(CHANNEL_ID, "Listening", NotificationManager.IMPORTANCE_LOW).apply {
            description = "Shown while Copilot can hear you"
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stop = PendingIntent.getBroadcast(
            this, 1, Intent(this, StopReceiver::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Copilot")
            .setContentText(text)
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(open)
            .addAction(0, "Stop", stop)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun updateNotification(text: String) {
        // If notifications aren't allowed (Android 13+), this is silently dropped; that's fine.
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(text))
    }

    // ------------------------------------------------------------------ wake word

    private fun hasWakeModel(): Boolean =
        try { assets.list("")?.contains(WAKE_MODEL_ASSET) == true } catch (_: Exception) { false }

    private fun startWakeWord() {
        wakeStartJob?.cancel()
        wakeStartJob = scope.launch {
            if (!prefs.wakeWordEnabled) {
                idle("Wake word is off. Tap the orb to talk.")
                return@launch
            }
            if (!hasWakeModel()) {
                idle("No “Hey Claude” model installed yet. Tap the orb to talk.")
                return@launch
            }
            val e = obtainEngine() ?: return@launch
            try {
                e.start()
            } catch (t: Throwable) {
                Log.e(TAG, "Couldn't start wake word", t)
                onEngineFailure()
                return@launch
            }
            AssistantBus.phase.value = Phase.WAITING
            AssistantBus.hint.value = "Say “Hey Claude”"
            updateNotification(
                if (prefs.listenOnlyWhileOpen) "Listening for “Hey Claude” while open"
                else "Listening for “Hey Claude”"
            )
        }
    }

    /** Reuse the loaded engine, or load one (a second or so) if none / the sensitivity changed. */
    private suspend fun obtainEngine(): WakeWordEngine? {
        val threshold = prefs.wakeThreshold
        engine?.let { existing ->
            if (engineThreshold == threshold) return existing
            detectionJob?.cancel()
            retire(existing)
            engine = null
        }
        return try {
            val e = withContext(Dispatchers.Default) {
                WakeWordEngine(
                    context = applicationContext,
                    models = listOf(WakeWordModel("Hey Claude", WAKE_MODEL_ASSET, threshold)),
                    detectionMode = DetectionMode.SINGLE_BEST,
                    detectionCooldownMs = 2000L,
                    scope = engineScope
                )
            }
            engine = e
            engineThreshold = threshold
            detectionJob = scope.launch { e.detections.collect { onWake() } }
            e
        } catch (t: Throwable) {
            Log.e(TAG, "Couldn't load wake-word model", t)
            fail("Couldn't load the wake-word model. Tap the orb to talk.")
            null
        }
    }

    private fun stopWakeWord() {
        wakeStartJob?.cancel()
        engine?.stop()
    }

    /**
     * Fully free an engine. stop() ends recording; we wait before release() so no audio frame
     * is still being scored when its models are closed.
     */
    private fun retire(e: WakeWordEngine) {
        CoroutineScope(Dispatchers.Default).launch {
            e.stop()
            delay(600)
            runCatching { e.release() }
        }
    }

    private suspend fun onEngineFailure() {
        if (inConversation) return
        engine?.stop()
        engineFailures++
        if (engineFailures <= 3) {
            AssistantBus.phase.value = Phase.IDLE
            AssistantBus.hint.value = "Microphone busy. Retrying…"
            delay(4000)
            if (!inConversation) startWakeWord()
        } else {
            fail("The microphone is in use by another app. Tap the orb to talk.")
        }
    }

    private fun onWake() {
        if (inConversation) return
        engineFailures = 0
        startConversation()
    }

    // ------------------------------------------------------------------ conversation

    private fun onTap() {
        when (AssistantBus.phase.value) {
            Phase.LISTENING -> speech.finishListening()                 // done talking: use what was heard
            Phase.SPEAKING -> { speech.stopSpeaking(); listen() }       // interrupt and reply
            Phase.THINKING -> Unit
            else -> startConversation()
        }
    }

    private fun startConversation() {
        if (inConversation) return
        if (prefs.apiKey.isBlank()) {
            AssistantBus.phase.value = Phase.ERROR
            AssistantBus.hint.value = "Add your Anthropic API key in settings (⚙)."
            return
        }
        inConversation = true
        stopWakeWord()
        if (System.currentTimeMillis() - lastExchangeAt > CONVERSATION_RESET_MS) history.clear()
        requestAudioFocus()
        updateNotification("In a conversation")
        scope.launch {
            speech.chime()
            delay(MIC_HANDOFF_MS)
            if (inConversation) listen()
        }
    }

    private fun listen() {
        AssistantBus.phase.value = Phase.LISTENING
        AssistantBus.hint.value = "Listening…"
        speech.listen(
            onPartial = { AssistantBus.hint.value = it },
            onHeard = { handleUtterance(it) },
            onNothing = { endConversation() },
            onFailed = { msg -> AssistantBus.add(Line(fromUser = false, text = msg)); endConversation() }
        )
    }

    private fun handleUtterance(text: String) {
        AssistantBus.add(Line(fromUser = true, text = text))
        if (DISMISSAL.matches(text.trim())) {
            speakThen("Okay.") { endConversation() }
            return
        }

        history += ChatMessage("user", text)
        trimHistory()
        AssistantBus.phase.value = Phase.THINKING
        AssistantBus.hint.value = if (prefs.grounding) "Checking sources…" else "Thinking…"
        AssistantBus.lastModel.value = ModelRouter.pick(prefs.model, text)

        val snapshot = history.toList()
        scope.launch {
            val result = runCatching { withContext(Dispatchers.IO) { claude.ask(snapshot) } }
            if (!inConversation) return@launch          // stopped while we were waiting
            result.onSuccess { reply ->
                history += ChatMessage("assistant", reply.text)
                lastExchangeAt = System.currentTimeMillis()
                AssistantBus.add(
                    Line(
                        fromUser = false,
                        text = Confidence.strip(reply.text),
                        confidence = Confidence.of(reply.text),
                        sources = reply.sources
                    )
                )
                // Hands-free: after answering, listen for a follow-up. Silence ends the conversation.
                speakThen(reply.text) { listen() }
            }.onFailure { e ->
                Log.w(TAG, "Claude request failed", e)
                // Drop the unanswered question so the next attempt starts clean.
                // (removeAt, not removeLast: removeLast crashes on Android 14 and older.)
                if (history.isNotEmpty() && history[history.lastIndex].role == "user") {
                    history.removeAt(history.lastIndex)
                }
                val msg = friendlyError(e)
                AssistantBus.add(Line(fromUser = false, text = msg))
                speakThen(msg) { endConversation() }
            }
        }
    }

    private fun speakThen(text: String, then: () -> Unit) {
        AssistantBus.phase.value = Phase.SPEAKING
        AssistantBus.hint.value = ""
        speech.speak(text) { if (inConversation) then() }
    }

    private fun endConversation() {
        if (!inConversation) return
        inConversation = false
        speech.cancelAll()
        abandonAudioFocus()
        startWakeWord()
    }

    private fun trimHistory() {
        while (history.size > MAX_HISTORY) history.removeAt(0)
        // The API requires the conversation to start with a user message.
        while (history.isNotEmpty() && history[0].role != "user") history.removeAt(0)
    }

    private fun friendlyError(e: Throwable): String = when (e) {
        is ClaudeApiException -> when (e.code) {
            401 -> "That API key was rejected. Check it in settings."
            403 -> "This API key isn't allowed to do that. Check your Anthropic console."
            404, 400 -> if ("model" in e.body) "That model isn't available. Pick another one in settings."
                        else "Claude couldn't process that request."
            429 -> "I'm being rate limited. Give it a moment and try again."
            529, 503 -> "Claude is overloaded right now. Try again shortly."
            else -> "Something went wrong talking to Claude, error ${e.code}."
        }
        is java.io.IOException -> "I couldn't reach the network. Check your connection."
        else -> "Something went wrong. Try again."
    }

    private fun idle(hint: String) {
        AssistantBus.phase.value = Phase.IDLE
        AssistantBus.hint.value = hint
        updateNotification("Tap to talk")
    }

    private fun fail(hint: String) {
        AssistantBus.phase.value = Phase.ERROR
        AssistantBus.hint.value = hint
    }

    // ------------------------------------------------------------------ audio focus

    /** Pause music and navigation audio while we talk, like Google's assistant does. */
    private fun requestAudioFocus() {
        val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .build()
        getSystemService(AudioManager::class.java).requestAudioFocus(req)
        focusRequest = req
    }

    private fun abandonAudioFocus() {
        focusRequest?.let { getSystemService(AudioManager::class.java).abandonAudioFocusRequest(it) }
        focusRequest = null
    }
}
