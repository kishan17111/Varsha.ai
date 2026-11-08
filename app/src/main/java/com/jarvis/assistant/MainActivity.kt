package com.jarvis.assistant

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat

class MainActivity : Activity() {

    private lateinit var status: TextView
    private lateinit var stateLabel: TextView
    private lateinit var log: TextView
    private lateinit var input: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (16 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad * 2, pad, pad)
        }
        fun add(v: View) = root.addView(v)
        fun button(label: String, click: () -> Unit) = add(Button(this).apply {
            text = label
            isAllCaps = false
            setOnClickListener { click() }
        })

        add(TextView(this).apply { text = "Varsha"; textSize = 28f })
        add(OrbView(this).apply {
            setBackgroundColor(Color.parseColor("#0B1020"))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, (220 * resources.displayMetrics.density).toInt()
            )
        })
        stateLabel = TextView(this).apply { gravity = Gravity.CENTER; setPadding(0, pad / 2, 0, pad / 2) }
        add(stateLabel)
        status = TextView(this).apply { setPadding(0, pad / 2, 0, pad / 2) }
        add(status)

        button("1 · Grant permissions") { askPermissions() }
        button("2 · Enable Varsha accessibility service") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        button("3 · Allow display over other apps") {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        }
        button("4 · Allow modify system settings") {
            startActivity(Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:$packageName")))
        }
        button("▶ Start Varsha") { send(null) }
        button("■ Stop Varsha") { send(VoiceService.ACTION_STOP) }
        button("🎤 Push to talk (no wake word)") { send(VoiceService.ACTION_PUSH) }
        button("🔊 Change voice") {
            val p = getSharedPreferences("varsha", MODE_PRIVATE)
            p.edit().putInt("voice", p.getInt("voice", 0) + 1).apply()
            send(VoiceService.ACTION_SAMPLE)
        }
        button("⏩ Voice speed") {
            val p = getSharedPreferences("varsha", MODE_PRIVATE)
            val rates = floatArrayOf(0.9f, 1.0f, 1.15f, 1.3f)
            val cur = p.getFloat("rate", 1.0f)
            val next = rates[(rates.indexOfFirst { it >= cur - 0.01f }.coerceAtLeast(0) + 1) % rates.size]
            p.edit().putFloat("rate", next).apply()
            send(VoiceService.ACTION_SAMPLE)
        }

        input = EditText(this).apply {
            hint = "Or type a command…"
            setSingleLine()
        }
        add(input)
        button("Run typed command") {
            val t = input.text.toString().trim()
            if (t.isNotEmpty()) { send(VoiceService.ACTION_TEXT, t); input.setText("") }
        }

        add(TextView(this).apply {
            setPadding(0, pad, 0, pad / 2)
            text = """
                Try saying:
                • Varsha, play lofi beats on YouTube
                • Varsha, send WhatsApp message to Sam saying I'm on my way
                • Varsha, call Mom
                • Varsha, play Believer on Spotify
                • Varsha, pause / next / volume up
                • Varsha, open Instagram
                • Varsha, turn on flashlight
                • Varsha, set alarm for 7 30 am
                • Varsha, navigate to the airport
                • Varsha, scroll down / go back / go home
                • Varsha, tap Subscribe
                • Varsha, open Instagram profile natgeo
                • Varsha, send Instagram message to Sam saying hey
                • Varsha, search Instagram for street food
                • Varsha, like / follow / comment saying nice
                • Varsha, next reel / previous reel
                • Varsha, what's Mom's number
                • Varsha, add contact Ali with number 5551234567
            """.trimIndent()
        })
        log = TextView(this)
        add(log)

        setContentView(ScrollView(this).apply { addView(root) })
    }

    override fun onResume() {
        super.onResume()
        Bus.appVisible = true
        Bus.onState = { updateLabel() }
        updateLabel()
        Bus.listener = { log.text = it }
        log.text = Bus.text()
        fun mark(ok: Boolean, s: String) = (if (ok) "✅ " else "⬜ ") + s
        status.text = listOf(
            mark(granted(Manifest.permission.RECORD_AUDIO), "Microphone"),
            mark(android.speech.SpeechRecognizer.isRecognitionAvailable(this), "Speech recogniser (Google app)"),
            mark(JarvisAccessibilityService.instance != null, "Accessibility service"),
            mark(Settings.canDrawOverlays(this), "Display over other apps"),
            mark(Settings.System.canWrite(this), "Modify system settings")
        ).joinToString("\n")
    }

    override fun onPause() {
        Bus.appVisible = false
        Bus.onState = null
        Bus.listener = null
        super.onPause()
    }

    private fun updateLabel() {
        stateLabel.text = when (Bus.state) {
            VState.IDLE -> "Tap ▶ Start Varsha"
            VState.LISTENING -> "Say “Varsha…”"
            VState.ACTIVE -> "Listening…"
            VState.SPEAKING -> "Speaking…"
        }
    }

    private fun granted(p: String) = checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

    private fun askPermissions() {
        val perms = mutableListOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.CALL_PHONE,
            Manifest.permission.ANSWER_PHONE_CALLS,
            Manifest.permission.SEND_SMS,
            Manifest.permission.READ_CONTACTS
        )
        if (Build.VERSION.SDK_INT >= 33) perms.add(Manifest.permission.POST_NOTIFICATIONS)
        requestPermissions(perms.toTypedArray(), 1)
    }

    private fun send(action: String?, text: String? = null) {
        if (!granted(Manifest.permission.RECORD_AUDIO)) {
            Toast.makeText(this, "Grant permissions first", Toast.LENGTH_SHORT).show()
            return
        }
        val i = Intent(this, VoiceService::class.java)
        if (action != null) i.action = action
        if (text != null) i.putExtra("text", text)
        ContextCompat.startForegroundService(this, i)
    }
}
