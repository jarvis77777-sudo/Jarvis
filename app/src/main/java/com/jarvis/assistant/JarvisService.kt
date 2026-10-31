package com.jarvis.assistant

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale

/**
 * The always-on part of Jarvis. Runs as a foreground service (that's the persistent notification),
 * listens for "Hey Jarvis", then listens for the command, asks Claude, speaks and acts.
 */
class JarvisService : Service(), TextToSpeech.OnInitListener {

    companion object {
        const val ACTION_ACTIVATE = "com.jarvis.assistant.service.ACTIVATE"
        const val ACTION_STOP = "com.jarvis.assistant.service.STOP"
        private const val CHANNEL = "jarvis"
        private const val NOTIF_ID = 1

        private val WAKE = Regex("""\b(?:(?:hey|hi|ok|okay|hello)\s+)?(?:jarvis|jarvas|jervis|jarvus)\b""")
    }

    private enum class Mode { WAKE, COMMAND }

    private val main = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private var mode = Mode.WAKE

    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var onSpeechDone: (() -> Unit)? = null
    private var pendingActivate = false

    private lateinit var brain: Brain
    private var stopped = false

    // ------------------------------------------------------------ lifecycle

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        brain = Brain(this)
        createChannel()
        try {
            startForeground(NOTIF_ID, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } catch (e: Exception) {
            // No mic permission yet, or Android refused a background start.
            stopSelf()
            return
        }
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            stopSelf()
            return
        }
        tts = TextToSpeech(this, this)
        JarvisBus.set(JarvisBus.State.IDLE, "Say \"Hey Jarvis\"")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_ACTIVATE -> {
                if (ttsReady) activate() else pendingActivate = true
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        stopped = true
        main.removeCallbacksAndMessages(null)
        destroyRecognizer()
        tts?.stop()
        tts?.shutdown()
        tts = null
        JarvisBus.set(JarvisBus.State.OFF, "")
        super.onDestroy()
    }

    // ------------------------------------------------------------ text to speech

    override fun onInit(status: Int) {
        val engine = tts ?: return
        if (status != TextToSpeech.SUCCESS) {
            JarvisBus.set(JarvisBus.State.OFF, "Text-to-speech failed to start")
            return
        }
        engine.setLanguage(Locale.UK)
        engine.setPitch(0.85f)          // a little deeper
        engine.setSpeechRate(0.95f)     // measured, butler-ish pace

        // Try to pick a male British voice if the phone has one installed.
        try {
            val maleBritish = engine.voices
                ?.filter { it.locale == Locale.UK && !it.isNetworkConnectionRequired }
                ?.firstOrNull { v -> listOf("rjs", "gbb", "gbd", "male").any { v.name.contains(it, ignoreCase = true) } }
            if (maleBritish != null) engine.setVoice(maleBritish)
        } catch (_: Exception) {
        }

        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) = finishSpeech()
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) = finishSpeech()
        })
        ttsReady = true

        if (pendingActivate) {
            pendingActivate = false
            activate()
        } else {
            listen(Mode.WAKE)
        }
    }

    private fun finishSpeech() {
        main.post {
            val cb = onSpeechDone
            onSpeechDone = null
            cb?.invoke()
        }
    }

    private fun speak(text: String, done: () -> Unit) {
        if (stopped) return
        destroyRecognizer() // so the mic doesn't hear Jarvis talking
        JarvisBus.set(JarvisBus.State.SPEAKING, text)
        onSpeechDone = done
        val engine = tts
        if (!ttsReady || engine == null ||
            engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, "jarvis") != TextToSpeech.SUCCESS
        ) {
            finishSpeech()
        }
    }

    // ------------------------------------------------------------ flow

    /** Widget tap or wake word with nothing after it. */
    private fun activate() {
        speak("Yes, ${brain.title}?") { listen(Mode.COMMAND) }
    }

    private fun handleCommand(text: String) {
        destroyRecognizer()
        JarvisBus.set(JarvisBus.State.THINKING, "“$text”")
        Thread {
            val reply = try {
                brain.ask(text)
            } catch (e: Exception) {
                Reply("Something went wrong, ${brain.title}.")
            }
            main.post { respond(reply) }
        }.start()
    }

    private fun respond(reply: Reply) {
        if (stopped) return
        speak(reply.speech) {
            val failure = Actions.execute(this, reply.action, reply.target, brain.title)
            if (failure != null) {
                speak(failure) { listen(Mode.WAKE) }
            } else if (reply.followUp) {
                listen(Mode.COMMAND)
            } else {
                listen(Mode.WAKE)
            }
        }
    }

    // ------------------------------------------------------------ speech recognition

    private fun listen(m: Mode) {
        if (stopped) return
        mode = m
        destroyRecognizer()

        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            JarvisBus.set(JarvisBus.State.OFF, "No speech recogniser on this phone")
            return
        }
        val r = SpeechRecognizer.createSpeechRecognizer(this)
        r.setRecognitionListener(listener)
        recognizer = r

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-US")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, packageName)
            if (m == Mode.WAKE) putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
        }
        try {
            r.startListening(intent)
        } catch (e: Exception) {
            restartLater(1000)
            return
        }
        if (m == Mode.WAKE) {
            JarvisBus.set(JarvisBus.State.IDLE, "Say \"Hey Jarvis\"")
        } else {
            JarvisBus.set(JarvisBus.State.LISTENING, "Listening…")
        }
    }

    private fun restartLater(delayMs: Long) {
        main.postDelayed({ listen(mode) }, delayMs)
    }

    private fun destroyRecognizer() {
        main.removeCallbacksAndMessages(null)
        recognizer?.let {
            try {
                it.setRecognitionListener(null)
                it.cancel()
                it.destroy()
            } catch (_: Exception) {
            }
        }
        recognizer = null
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onEvent(eventType: Int, params: Bundle?) {}

        override fun onPartialResults(partialResults: Bundle?) {
            if (mode != Mode.COMMAND) return
            val t = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
            if (!t.isNullOrBlank()) JarvisBus.set(JarvisBus.State.LISTENING, t)
        }

        override fun onError(error: Int) {
            when (error) {
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> {
                    JarvisBus.set(JarvisBus.State.OFF, "Microphone permission missing")
                    stopSelf()
                }
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> {
                    mode = Mode.WAKE
                    restartLater(1500)
                }
                else -> {
                    // No match / timeout / network hiccup: the normal "nobody spoke" case.
                    // Go (back) to waiting for the wake word.
                    mode = Mode.WAKE
                    restartLater(if (error == SpeechRecognizer.ERROR_NO_MATCH ||
                        error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) 150 else 800)
                }
            }
        }

        override fun onResults(results: Bundle?) {
            val options = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty()
            if (options.isEmpty()) {
                restartLater(150)
                return
            }
            if (mode == Mode.COMMAND) {
                val text = options.first().trim()
                if (text.isEmpty()) {
                    mode = Mode.WAKE
                    restartLater(150)
                } else {
                    handleCommand(text)
                }
                return
            }

            // WAKE mode: look for "jarvis" in any of the alternatives
            for (option in options) {
                val lower = option.lowercase(Locale.ENGLISH)
                val m = WAKE.find(lower) ?: continue
                val rest = lower.substring(m.range.last + 1).trim().trimStart(',', '.', ' ')
                if (rest.length >= 3) handleCommand(rest) else activate()
                return
            }
            restartLater(100)
        }
    }

    // ------------------------------------------------------------ notification

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Jarvis", NotificationManager.IMPORTANCE_LOW)
        )
    }

    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, JarvisService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("Jarvis is listening")
            .setContentText("Say \"Hey Jarvis\"")
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(this, android.R.drawable.ic_menu_close_clear_cancel),
                    "Stop", stop
                ).build()
            )
            .build()
    }
}
