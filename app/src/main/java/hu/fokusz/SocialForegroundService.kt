package hu.fokusz

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent

/** Only window identity is needed. The service cannot retrieve window content. */
class SocialForegroundService : AccessibilityService() {
    companion object {
        @Volatile internal var connected = false
            private set
    }

    private val activityClasses = mutableMapOf<ComponentName, Boolean>()

    override fun onServiceConnected() {
        connected = true
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        val activity = event.className?.toString() ?: return
        if (pkg == packageName && !activity.startsWith(packageName)) return
        if (event.eventTime + 1500L < SystemClock.uptimeMillis()) return
        val component = ComponentName(pkg, activity)
        val isActivity = activityClasses.getOrPut(component) {
            runCatching { packageManager.getActivityInfo(component, 0) }.isSuccess
        }
        if (isActivity) FocusService.windowChanged(pkg, activity)
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        connected = false
        super.onDestroy()
    }
}
