package com.jarvis.assistant

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import java.util.Locale

/**
 * Always-on listener. Say "Varsha <command>" (or "Varsha", wait for "Yes?", then the command).
 * Uses Android's built-in speech recogniser + text-to-speech: no API key, no account.
 */
class VoiceService : Service(), RecognitionListener {

    companion object {
        const val ACTION_STOP = "com.jarvis.assistant.STOP"
        const val ACTION_PUSH = "com.jarvis.assistant.PUSH"
        const val ACTION_TEXT = "com.jarvis.assistant.TEXT"
        const val ACTION_SAMPLE = "com.jarvis.assistant.SAMPLE"
        private const val CHANNEL = "jarvis"
        private val WAKE_WORDS = setOf("varsha", "versha", "warsha", "barsha", "varshaa", "varsa", "versa", "vasha", "varisha")
    }

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var recognizer: SpeechRecognizer
    private lateinit var tts: TextToSpeech
    private lateinit var commands: Commands
    private lateinit var overlay: Overlay

    private var running = true
    private var ttsReady = false
    private var speaking = false
    private var awaitingCommand = false
    private var awaitUntil = 0L
    private var preferOffline = true
    private var lastActivity = 0L
    private var wakeLock: PowerManager.WakeLock? = null

    private val startRunnable = Runnable { listen() }
    private val speakGuard = Runnable { speaking = false; listen() }

    // If the recogniser ever goes quiet (some phones stop it silently), kick it again.
    private val watchdog = object : Runnable {
        override fun run() {
            if (running && !speaking && ::recognizer.isInitialized &&
                SystemClock.elapsedRealtime() - lastActivity > 20000
            ) {
                recognizer.cancel()
                listen()
            }
            handler.postDelayed(this, 10000)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Varsha", NotificationManager.IMPORTANCE_LOW))
        try {
            ServiceCompat.startForeground(
                this, 1, buildNotification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            )
        } catch (e: Exception) {
            Bus.log("Can't start: grant the microphone permission first.")
            stopSelf()
            return
        }

        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "varsha:listen")
            .apply { acquire() }

        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts.language = Locale.getDefault()
                tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {}
                    override fun onDone(utteranceId: String?) { handler.post(doneSpeaking) }
                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) { handler.post(doneSpeaking) }
                })
                ttsReady = true
                tts.setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANT)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                applyVoice()
                say("Varsha is ready.")
            }
        }
        commands = Commands(this) { say(it) }
        overlay = Overlay(this)
        setState(VState.LISTENING)

        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            Bus.log("No speech recogniser on this phone (install/enable the Google app).")
            return
        }
        recognizer = SpeechRecognizer.createSpeechRecognizer(this)
        recognizer.setRecognitionListener(this)
        Bus.log("Listening for “Varsha”…")
        listen()
        handler.postDelayed(watchdog, 10000)
    }

    private val doneSpeaking = Runnable {
        handler.removeCallbacks(speakGuard)
        speaking = false
        listen()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                running = false
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_PUSH -> {
                awaitingCommand = true
                awaitUntil = SystemClock.elapsedRealtime() + 8000
                setState(VState.ACTIVE)
                if (::recognizer.isInitialized) recognizer.cancel()
                handler.removeCallbacks(startRunnable)
                handler.postDelayed(startRunnable, 150)
            }
            ACTION_SAMPLE -> if (ttsReady) {
                applyVoice()
                say("Hi, I'm Varsha. How do I sound?")
            }
            ACTION_TEXT -> intent.getStringExtra("text")?.let { if (::commands.isInitialized) commands.handle(it) }
        }
        return START_STICKY
    }

    private fun buildNotification(): Notification {
        val stop = PendingIntent.getService(
            this, 0,
            Intent(this, VoiceService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("Varsha is listening")
            .setContentText("Say “Varsha” followed by a command")
            .setOngoing(true)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stop)
            .build()
    }

    // ---------- on-screen state ----------

    private fun setState(s: VState) {
        Bus.state = s
        if (::overlay.isInitialized) {
            if ((s == VState.ACTIVE || s == VState.SPEAKING) && !Bus.appVisible) overlay.show() else overlay.hide()
        }
        Bus.pokeState()
    }

    // ---------- voice choice ----------

    private fun applyVoice() {
        val p = getSharedPreferences("varsha", MODE_PRIVATE)
        tts.setSpeechRate(p.getFloat("rate", 1.0f))
        tts.setPitch(1.05f)
        try {
            val lang = Locale.getDefault().language
            val vs = (tts.voices ?: emptySet())
                .filter { it.locale.language == lang && !it.isNetworkConnectionRequired }
                .sortedBy { it.name }
            if (vs.isNotEmpty()) {
                val i = Math.floorMod(p.getInt("voice", 0), vs.size)
                tts.voice = vs[i]
                Bus.log("Voice ${i + 1}/${vs.size}: ${vs[i].name}")
            }
        } catch (e: Exception) {
        }
    }

    // ---------- speaking ----------

    private fun say(text: String) {
        Bus.log("Varsha: $text")
        if (!ttsReady || text.isBlank()) return
        speaking = true
        setState(VState.SPEAKING)
        if (::recognizer.isInitialized) recognizer.cancel()
        handler.removeCallbacks(speakGuard)
        handler.postDelayed(speakGuard, 8000)
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "varsha")
    }

    // ---------- listening ----------

    private fun listen() {
        if (!running || speaking || !::recognizer.isInitialized) return
        lastActivity = SystemClock.elapsedRealtime()
        if (awaitingCommand && SystemClock.elapsedRealtime() > awaitUntil) awaitingCommand = false
        setState(if (awaitingCommand) VState.ACTIVE else VState.LISTENING)
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, preferOffline)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, packageName)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }
        try {
            recognizer.startListening(i)
        } catch (e: Exception) {
            restartSoon(1000)
        }
    }

    private fun restartSoon(ms: Long) {
        handler.removeCallbacks(startRunnable)
        handler.postDelayed(startRunnable, ms)
    }

    private fun process(text: String) {
        val t = text.lowercase().trim()
        Bus.log("Heard: $t")
        val now = SystemClock.elapsedRealtime()
        if (awaitingCommand && now < awaitUntil) {
            awaitingCommand = false
            setState(VState.ACTIVE)
            commands.handle(t)
            return
        }
        val end = findWakeEnd(t)
        if (end < 0) return                      // ignore everything that isn't addressed to Varsha
        setState(VState.ACTIVE)
        val cmd = t.substring(end).trim()
        if (cmd.isEmpty()) {
            awaitingCommand = true
            awaitUntil = now + 8000
            say("Yes?")
        } else {
            commands.handle(cmd)
        }
    }

    /** Index just after the wake word, tolerating misheard spellings ("barsha", "var sha"...). */
    private fun findWakeEnd(t: String): Int {
        val toks = Regex("""\S+""").findAll(t).toList()
        for ((i, m) in toks.withIndex()) {
            if (isWake(m.value)) return m.range.last + 1
            if (i + 1 < toks.size && isWake(m.value + toks[i + 1].value)) return toks[i + 1].range.last + 1
        }
        return -1
    }

    private fun isWake(word: String): Boolean {
        val x = word.filter { it.isLetter() }
        if (x.length < 4 || x.length > 8) return false
        return x in WAKE_WORDS || lev(x, "varsha") <= 1
    }

    private fun lev(a: String, b: String): Int {
        val dp = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            var prev = dp[0]
            dp[0] = i
            for (j in 1..b.length) {
                val tmp = dp[j]
                dp[j] = minOf(dp[j] + 1, dp[j - 1] + 1, prev + if (a[i - 1] == b[j - 1]) 0 else 1)
                prev = tmp
            }
        }
        return dp[b.length]
    }

    override fun onResults(results: Bundle?) {
        lastActivity = SystemClock.elapsedRealtime()
        Bus.level = 0f
        val t = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
        if (!t.isNullOrBlank()) process(t)
        restartSoon(200)
    }

    override fun onError(error: Int) {
        lastActivity = SystemClock.elapsedRealtime()
        when (error) {
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> {
                Bus.log("Microphone permission missing.")
            }
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> {
                recognizer.cancel()
                restartSoon(1000)
            }
            SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> restartSoon(200)
            12, 13 -> { // language not supported / unavailable offline
                preferOffline = false
                Bus.log("Offline speech pack missing; using the online recogniser. Download it in Google app → Settings → Voice → Offline speech recognition.")
                restartSoon(1000)
            }
            SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT, SpeechRecognizer.ERROR_SERVER -> {
                Bus.log("Speech recognition needs internet or the offline pack (Google app → Settings → Voice → Offline speech recognition).")
                restartSoon(3000)
            }
            else -> restartSoon(1000)
        }
    }

    override fun onReadyForSpeech(params: Bundle?) { lastActivity = SystemClock.elapsedRealtime() }
    override fun onBeginningOfSpeech() {}
    override fun onRmsChanged(rmsdB: Float) { Bus.level = ((rmsdB + 2f) / 12f).coerceIn(0f, 1f) }
    override fun onBufferReceived(buffer: ByteArray?) {}
    override fun onEndOfSpeech() { Bus.level = 0f }
    override fun onPartialResults(partialResults: Bundle?) {}
    override fun onEvent(eventType: Int, params: Bundle?) {}

    override fun onDestroy() {
        running = false
        if (::overlay.isInitialized) overlay.hide()
        Bus.state = VState.IDLE
        Bus.level = 0f
        Bus.pokeState()
        handler.removeCallbacksAndMessages(null)
        wakeLock?.let { if (it.isHeld) it.release() }
        if (::recognizer.isInitialized) recognizer.destroy()
        if (::tts.isInitialized) tts.shutdown()
        super.onDestroy()
    }
}
