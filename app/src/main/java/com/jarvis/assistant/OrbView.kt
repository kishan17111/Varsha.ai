package com.jarvis.assistant

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.min
import kotlin.math.sin

/**
 * Pulsing orb. Indigo = waiting for "Varsha", cyan = listening to you (reacts to your voice),
 * purple = speaking. Used on the main screen and as the floating overlay.
 */
class OrbView(ctx: Context, private val compact: Boolean = false) : View(ctx) {

    private val density = resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var phase = 0f
    private var shown = 0f

    private val anim = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 1800
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener {
            phase = it.animatedValue as Float
            shown += (Bus.level - shown) * 0.25f
            invalidate()
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        anim.start()
    }

    override fun onDetachedFromWindow() {
        anim.cancel()
        super.onDetachedFromWindow()
    }

    private fun lighten(c: Int) = Color.rgb(
        min(255, Color.red(c) + 90), min(255, Color.green(c) + 90), min(255, Color.blue(c) + 90)
    )

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val base = min(width, height) * 0.2f
        val st = Bus.state
        val color = when (st) {
            VState.IDLE -> 0xFF607D8B.toInt()
            VState.LISTENING -> 0xFF5C6BC0.toInt()
            VState.ACTIVE -> 0xFF26C6DA.toInt()
            VState.SPEAKING -> 0xFFAB47BC.toInt()
        }
        val wave = sin(phase * (2 * Math.PI).toFloat() * 2f)
        val amp = when (st) {
            VState.IDLE -> 0.02f
            VState.LISTENING -> 0.03f + shown * 0.25f
            VState.ACTIVE -> 0.12f + shown * 0.7f
            VState.SPEAKING -> 0.18f + 0.12f * (wave + 1f) / 2f
        }

        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 3f * density
        paint.shader = null
        for (i in 0..2) {
            val p = (phase + i / 3f) % 1f
            paint.color = color
            val strength = if (st == VState.IDLE || st == VState.LISTENING) 70 else 170
            paint.alpha = ((1f - p) * strength).toInt()
            canvas.drawCircle(cx, cy, base * (1f + p * (0.6f + amp * 1.5f)), paint)
        }

        val coreR = base * (1f + amp * 0.4f)
        paint.style = Paint.Style.FILL
        paint.alpha = 255
        paint.shader = RadialGradient(cx, cy, coreR, lighten(color), color, Shader.TileMode.CLAMP)
        canvas.drawCircle(cx, cy, coreR, paint)
        paint.shader = null
    }
}
