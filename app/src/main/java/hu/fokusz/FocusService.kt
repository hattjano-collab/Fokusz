package hu.fokusz

import android.app.AppOpsManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.Process
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

object UsageGuard {
    fun allowed(c: Context): Boolean {
        val ops = c.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        return ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), c.packageName) == AppOpsManager.MODE_ALLOWED
    }
}

class FocusService : Service() {
    companion object {
        @Volatile var isRunning = false
        private const val CHANNEL = "fokusz-monitor"
    }
    private val handler = Handler(Looper.getMainLooper())
    private var lastEventTime = 0L
    private var currentPackage = ""
    private var cover: View? = null
    private var coverQuestMode = false
    private var windowManager: WindowManager? = null
    private val pulse = object : Runnable {
        override fun run() {
            try { monitor() } catch (_: Exception) { hideCover() }
            handler.postDelayed(this, 1000L)
        }
    }

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Fókusz figyelés", NotificationManager.IMPORTANCE_LOW))
        val intent = Intent(this, MainActivity::class.java)
        val pi = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notification = Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_lock_idle_lock)
            .setContentTitle("Fókusz aktív")
            .setContentText("A napi apphasználati idő figyelése bekapcsolva")
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
        startForeground(2, notification)
        isRunning = true
        QuestStore.resetClock(this)
        FocusPrefs.resetClock(this)
        handler.post(pulse)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!UsageGuard.allowed(this) || !Settings.canDrawOverlays(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    private fun monitor() {
        if (!UsageGuard.allowed(this) || !Settings.canDrawOverlays(this)) { stopSelf(); return }
        QuestStore.tick(this)
        FocusPrefs.tick(this)
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        if (!pm.isInteractive) { hideCover(); return }
        val us = getSystemService(USAGE_STATS_SERVICE) as UsageStatsManager
        val now = System.currentTimeMillis()
        val events = us.queryEvents(now - 30_000L, now) ?: return
        val ev = UsageEvents.Event()
        var recentTime = lastEventTime
        while (events.hasNextEvent()) {
            events.getNextEvent(ev)
            if (ev.eventType == UsageEvents.Event.ACTIVITY_RESUMED && ev.timeStamp > recentTime) {
                recentTime = ev.timeStamp
                currentPackage = ev.packageName ?: ""
            }
        }
        lastEventTime = recentTime
        val questLocked = QuestStore.isTimerLocked(this)
        val focusLocked = FocusPrefs.focusLocked(this)
        val locked = questLocked || focusLocked
        val browsersLocked = (QuestStore.isCustomTimerLocked(this) || focusLocked) &&
            currentPackage in Store.BROWSERS
        val isSocial = FocusPrefs.blocked(this, currentPackage)
            || (locked && (currentPackage in Store.BLOCKED || currentPackage in Store.QUEST_SOCIAL))
        if (isSocial || browsersLocked) {
            if (FocusPrefs.emergencyActive(this) && !QuestStore.isCustomTimerLocked(this)) {
                if (Store.bal(this) > 0) Store.add(this, -1)
                ProgressStore.used(this, 1)
                hideCover()
            } else if (locked) {
                showCover(true)
            } else if (FocusPrefs.blocked(this, currentPackage)) {
                if (Store.bal(this) > 0) Store.add(this, -1)
                ProgressStore.used(this, 1)
                if (Store.bal(this) <= 0) showCover(false) else hideCover()
            } else {
                hideCover()
            }
        } else hideCover()
    }

    private fun showCover(questMode: Boolean) {
        if (cover != null) {
            if (coverQuestMode == questMode) return
            hideCover()
        }
        coverQuestMode = questMode
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(48, 60, 48, 60)
            setBackgroundColor(Color.rgb(16, 22, 38))
        }
        val title = TextView(this).apply {
            text = if (questMode) "🔒 Fókusz\nQuest folyamatban!" else "🔒 Fókusz\nElfogyott a mai időkereted"
            textSize = 26f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
        }
        val desc = TextView(this).apply {
            text = if (questMode) "A küldetés időzítője alatt a közösségi appok zárolva vannak. Térj vissza a feladatodhoz!"
                else "A Fókusz alkalmazásban további perceket szerezhetsz."
            textSize = 17f
            setTextColor(Color.LTGRAY)
            gravity = Gravity.CENTER
            setPadding(0, 30, 0, 30)
        }
        val button = Button(this).apply {
            text = "Fókusz megnyitása"
            setOnClickListener {
                hideCover()
                val i = Intent(this@FocusService, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                startActivity(i)
            }
        }
        panel.addView(title); panel.addView(desc); panel.addView(button)
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )
        params.gravity = Gravity.CENTER
        try { wm.addView(panel, params); cover = panel; windowManager = wm }
        catch (_: Exception) { cover = null }
    }

    private fun hideCover() {
        val view = cover ?: return
        cover = null
        try { windowManager?.removeView(view) } catch (_: Exception) { }
    }

    override fun onDestroy() {
        isRunning = false
        handler.removeCallbacks(pulse)
        hideCover()
        super.onDestroy()
    }
    override fun onBind(intent: Intent?): IBinder? = null
}
