package hu.fokusz

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.view.accessibility.AccessibilityEvent

class BlockerService : AccessibilityService() {
    private val h = Handler(Looper.getMainLooper())
    private var fg = ""
    private val tick = object : Runnable {
        override fun run() {
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            if (fg in Store.BLOCKED && pm.isInteractive) {
                if (Store.add(this@BlockerService, -1) <= 0) lock()
            }
            h.postDelayed(this, 1000)
        }
    }
    override fun onServiceConnected() { h.post(tick) }
    override fun onAccessibilityEvent(e: AccessibilityEvent) {
        if (e.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val p = e.packageName?.toString() ?: return
        if (p == "com.android.systemui" || p.contains("inputmethod")) return
        fg = p
        if (p in Store.BLOCKED && Store.bal(this) <= 0) lock()
    }
    private fun lock() {
        fg = ""
        performGlobalAction(GLOBAL_ACTION_HOME)
        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
    override fun onInterrupt() {}
    override fun onDestroy() { h.removeCallbacks(tick); super.onDestroy() }
}
