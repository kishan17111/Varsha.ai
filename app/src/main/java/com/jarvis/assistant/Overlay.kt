package com.jarvis.assistant

import android.content.Context
import android.graphics.PixelFormat
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager

/** Floating orb over any app while Varsha is active or speaking. Needs "display over other apps". */
class Overlay(private val ctx: Context) {
    private val wm = ctx.getSystemService(WindowManager::class.java)
    private var view: OrbView? = null

    fun show() {
        if (view != null || !Settings.canDrawOverlays(ctx)) return
        val d = ctx.resources.displayMetrics.density
        val size = (150 * d).toInt()
        val v = OrbView(ctx, compact = true)
        val lp = WindowManager.LayoutParams(
            size, size,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = (48 * d).toInt()
        }
        try {
            wm.addView(v, lp)
            view = v
        } catch (e: Exception) {
            view = null
        }
    }

    fun hide() {
        view?.let { try { wm.removeView(it) } catch (e: Exception) { } }
        view = null
    }
}
