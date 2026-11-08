package com.jarvis.assistant

import android.os.Handler
import android.os.Looper

enum class VState { IDLE, LISTENING, ACTIVE, SPEAKING }

/** Tiny in-memory log + live state shared between the service and the screens. */
object Bus {
    private val main = Handler(Looper.getMainLooper())
    private val sb = StringBuilder()

    @Volatile var listener: ((String) -> Unit)? = null
    @Volatile var onState: (() -> Unit)? = null

    @Volatile var state: VState = VState.IDLE
    @Volatile var level: Float = 0f          // 0..1 microphone loudness
    @Volatile var appVisible: Boolean = false

    fun text(): String = synchronized(sb) { sb.toString() }

    fun log(line: String) {
        synchronized(sb) {
            sb.append(line).append('\n')
            if (sb.length > 4000) sb.delete(0, sb.length - 3000)
        }
        main.post { listener?.invoke(text()) }
    }

    fun pokeState() {
        main.post { onState?.invoke() }
    }
}
