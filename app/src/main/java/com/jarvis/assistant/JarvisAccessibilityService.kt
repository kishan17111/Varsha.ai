package com.jarvis.assistant

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.os.Bundle
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Lets Varsha do what a finger would: tap buttons, type, swipe, press Back/Home.
 * This is also what makes "play the video" and "press send" work after opening an app.
 */
class JarvisAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile
        var instance: JarvisAccessibilityService? = null
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Bus.log("Accessibility connected")
    }

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    private fun nodes(): List<AccessibilityNodeInfo> {
        val out = ArrayList<AccessibilityNodeInfo>()
        fun walk(n: AccessibilityNodeInfo?) {
            if (n == null) return
            out.add(n)
            for (i in 0 until n.childCount) walk(n.getChild(i))
        }
        walk(rootInActiveWindow)
        return out
    }

    fun label(n: AccessibilityNodeInfo): String =
        listOfNotNull(n.text, n.contentDescription).joinToString(" ")

    fun click(n: AccessibilityNodeInfo): Boolean {
        var c: AccessibilityNodeInfo? = n
        while (c != null) {
            if (c.isClickable && c.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
            c = c.parent
        }
        return false
    }

    /** Tap something visible on screen by its text / description. */
    fun clickByText(target: String): Boolean {
        val all = nodes()
        val t = target.lowercase().trim()
        val hit = all.firstOrNull { label(it).lowercase().trim() == t }
            ?: all.firstOrNull { label(it).lowercase().contains(t) }
            ?: return false
        return click(hit)
    }

    /** Wait for a matching element to appear (the app is still loading), then tap it. */
    fun clickWhenReady(
        timeoutMs: Long,
        initialDelayMs: Long = 1500,
        thenBack: Boolean = false,
        pred: (AccessibilityNodeInfo) -> Boolean
    ) {
        Thread {
            Thread.sleep(initialDelayMs)
            val end = SystemClock.elapsedRealtime() + timeoutMs
            while (SystemClock.elapsedRealtime() < end) {
                val hit = try { nodes().firstOrNull(pred) } catch (e: Exception) { null }
                if (hit != null && click(hit)) {
                    if (thenBack) {
                        Thread.sleep(700)
                        performGlobalAction(GLOBAL_ACTION_BACK)
                    }
                    return@Thread
                }
                Thread.sleep(500)
            }
        }.start()
    }

    fun typeText(text: String): Boolean {
        val f = rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            ?: nodes().firstOrNull { it.isEditable }
            ?: return false
        val cur = if (f.isShowingHintText) "" else (f.text?.toString() ?: "")
        val args = Bundle().apply {
            putCharSequence(
                AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                if (cur.isEmpty()) text else "$cur $text"
            )
        }
        return f.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    /** Finger direction: "up" moves the finger up (content scrolls down). */
    fun swipe(dir: String): Boolean {
        val dm = resources.displayMetrics
        val w = dm.widthPixels.toFloat()
        val h = dm.heightPixels.toFloat()
        val p = Path()
        when (dir) {
            "up" -> { p.moveTo(w / 2, h * 0.75f); p.lineTo(w / 2, h * 0.25f) }
            "down" -> { p.moveTo(w / 2, h * 0.25f); p.lineTo(w / 2, h * 0.75f) }
            "left" -> { p.moveTo(w * 0.8f, h / 2); p.lineTo(w * 0.2f, h / 2) }
            "right" -> { p.moveTo(w * 0.2f, h / 2); p.lineTo(w * 0.8f, h / 2) }
            else -> return false
        }
        val g = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(p, 0, 300))
            .build()
        return dispatchGesture(g, null, null)
    }

    fun inPackage(pkg: String): Boolean = rootInActiveWindow?.packageName?.toString() == pkg

    /** Blocking poll: call only from a background thread (see runAsync). */
    fun awaitNode(timeoutMs: Long, pred: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        val end = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < end) {
            val hit = try { nodes().firstOrNull(pred) } catch (e: Exception) { null }
            if (hit != null) return hit
            Thread.sleep(400)
        }
        return null
    }

    fun tapWhen(timeoutMs: Long, pred: (AccessibilityNodeInfo) -> Boolean): Boolean {
        val n = awaitNode(timeoutMs, pred) ?: return false
        return click(n)
    }

    fun setText(n: AccessibilityNodeInfo, text: String): Boolean {
        n.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        return n.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    fun runAsync(block: () -> Unit) {
        Thread { try { block() } catch (e: Exception) { } }.start()
    }

    private fun tapAt(x: Float, y: Float): Boolean {
        val p = Path().apply { moveTo(x, y) }
        val g = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(p, 0, 50))
            .build()
        return dispatchGesture(g, null, null)
    }

    /** Double-tap the middle of the screen (Instagram's "like"). Call from a background thread. */
    fun doubleTap(): Boolean {
        val dm = resources.displayMetrics
        tapAt(dm.widthPixels / 2f, dm.heightPixels / 2f)
        Thread.sleep(120)
        return tapAt(dm.widthPixels / 2f, dm.heightPixels / 2f)
    }
}
