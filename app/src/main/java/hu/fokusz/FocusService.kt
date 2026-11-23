package hu.fokusz

import android.app.AppOpsManager
import android.app.KeyguardManager
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
import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import java.time.LocalDate

object UsageGuard {
    fun allowed(c: Context): Boolean {
        val ops = c.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        return ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), c.packageName) == AppOpsManager.MODE_ALLOWED
    }
}

class FocusService : Service() {
    private enum class CoverReason { QUEST, BALANCE, BREAK, APP_LIMIT, TEAM_OFFER, TEAM_WAIT }

    companion object {
        @Volatile var isRunning = false
        private const val CHANNEL = "fokusz-monitor"
    }
    private val handler = Handler(Looper.getMainLooper())
    private val balanceWarning = BalanceWarning()
    private val teamSession = TeamTimeSession()
    private var lastEventTime = 0L
    private var currentPackage = ""
    private var cover: View? = null
    private var coverReason: CoverReason? = null
    private var coverDescription: TextView? = null
    private var windowManager: WindowManager? = null
    private val background=CoroutineScope(SupervisorJob()+Dispatchers.IO)
    private var lastNotification=0L
    private var lastChessSync=0L
    private var lastDuolingoSync=0L
    private var lastCalendarReminder=""

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
        startForeground(2,liveNotification())
        isRunning = true
        CloudTeams.start(this)
        QuestStore.resetClock(this)
        UserQuests.resetClock(this)
        FocusPrefs.resetClock(this)
        handler.post(pulse)
    }

    private fun liveNotification():Notification {
        val intent=Intent(this,MainActivity::class.java)
        val pi=PendingIntent.getActivity(this,0,intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val custom = UserQuests.active(this)
        val part=when {
            custom != null && custom.left > 0 -> "Saját quest: " + clock(custom.left)
            QuestStore.isTimerLocked(this) -> "Quest: "+clock(QuestStore.secondsLeft(this))
            FocusPrefs.focusLocked(this) -> "Fókusz: "+clock(FocusPrefs.focusLeft(this))+
                (if(FocusPrefs.focusPaused(this))" (szünet)" else "")
            else -> "Maradék képernyőidő: "+clock(Store.bal(this))
        }
        val reminder=CalendarStore.reminderText(this)
        return Notification.Builder(this,CHANNEL)
            .setSmallIcon(android.R.drawable.ic_lock_idle_lock)
            .setContentTitle(if(reminder!=null)reminder else "Fókusz aktív")
            .setContentText((custom?.quest?.title ?: QuestStore.current(this)?.title)?.take(38)?.let {
                part+" • "+it
            } ?: part)
            .setContentIntent(pi)
            .setOngoing(true).build()
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
        UserQuests.tick(this)
        FocusPrefs.tick(this)
        CloudTeams.pulse(this)
        val elapsed=SystemClock.elapsedRealtime()
        if(elapsed-lastNotification>=20000L) {
            lastNotification=elapsed
            try {
                val nm=getSystemService(NOTIFICATION_SERVICE) as NotificationManager
                nm.notify(2,liveNotification())
            } catch (_:Exception) {}
        }
        if(ChessVerifier.isLinked(this) && elapsed-lastChessSync>=600000L) {
            lastChessSync=elapsed
            background.launch {
                val result=ChessVerifier.dailyProgress(this@FocusService)
                if(result.success)
                    QuestStore.verifiedChess(this@FocusService,result)
            }
        }
        if(DuolingoStore.linked(this) && (lastDuolingoSync == 0L || elapsed-lastDuolingoSync >= 900_000L)) {
            lastDuolingoSync=elapsed
            background.launch { DuolingoStore.check(this@FocusService) }
        }
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        if (!pm.isInteractive || getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == true) {
            teamSession.clear()
            hideCover()
            return
        }
        val us = getSystemService(USAGE_STATS_SERVICE) as UsageStatsManager
        val now = System.currentTimeMillis()
        if (lastEventTime > now) lastEventTime = 0L
        val window = if (lastEventTime == 0L) 30 * 60_000L else 30_000L
        val events = us.queryEvents(now - window, now) ?: return
        val ev = UsageEvents.Event()
        val previouslySeen = lastEventTime
        while (events.hasNextEvent()) {
            events.getNextEvent(ev)
            if (ev.timeStamp <= previouslySeen) continue
            when (ev.eventType) {
                UsageEvents.Event.ACTIVITY_RESUMED -> currentPackage = ev.packageName.orEmpty()
                UsageEvents.Event.ACTIVITY_PAUSED, UsageEvents.Event.ACTIVITY_STOPPED -> {
                    if (currentPackage == ev.packageName) currentPackage = ""
                }
                else -> continue
            }
            lastEventTime = ev.timeStamp
        }
        val questLocked = QuestStore.isTimerLocked(this)
        val legacyCustom = questLocked && QuestStore.isCustomTimerLocked(this)
        val userQuest = UserQuests.locked(this)
        val focusLocked = FocusPrefs.focusLocked(this)
        if (currentPackage.isBlank() || FocusPrefs.isEssential(this, currentPackage) ||
            QuizDraft.isCameraActive(currentPackage)) {
            teamSession.clear()
            hideCover()
            return
        }
        val focusRulesActive = focusLocked || legacyCustom || userQuest
        val subjectApps = when {
            userQuest -> UserQuests.subjectApps(this)
            legacyCustom -> FocusPrefs.selectedSubject(this).packages
            else -> null
        }
        val emergency = FocusPrefs.emergencyActive(this)
        val normalQuestBlock = questLocked && !legacyCustom && !emergency &&
            (FocusPrefs.blocked(this, currentPackage) || currentPackage in Store.BLOCKED ||
                currentPackage in Store.QUEST_SOCIAL)
        if ((focusRulesActive && !FocusPrefs.allowedDuringFocus(this, currentPackage, subjectApps)) || normalQuestBlock) {
            teamSession.clear()
            showCover(CoverReason.QUEST)
            return
        }
        if (SocialUsage.dailyLimitReached(this, currentPackage)) {
            teamSession.clear()
            showCover(CoverReason.APP_LIMIT)
            return
        }
        val chargeBalance = !focusRulesActive && FocusPrefs.blocked(this, currentPackage)
        if (!chargeBalance) {
            SocialUsage.recordSecond(this, currentPackage, advanceBreak = false)
            teamSession.clear()
            hideCover()
            return
        }
        val socialApp = currentPackage in Store.BLOCKED || currentPackage in Store.QUEST_SOCIAL ||
            FocusPrefs.socialChoices.any { it.second == currentPackage }
        val breakLeft = if (socialApp) SocialUsage.breakSecondsLeft(this) else 0
        if (breakLeft > 0) {
            showBreak(breakLeft)
            return
        }

        val spendReason = ScreenTimeLedger.socialReason(currentPackage)
        val threshold = ScreenTimeAlerts.warningSeconds(this)
        val today = LocalDate.now()
        val personalBefore = Store.bal(this)
        if (personalBefore > threshold) balanceWarning.shouldVibrate(today, personalBefore, threshold)
        val spentPersonal = Store.spend(this, 1, spendReason)
        var spentTeam = false
        if (spentPersonal || emergency) {
            teamSession.clear()
            if (!emergency && balanceWarning.shouldVibrate(today, Store.bal(this), threshold)) {
                ScreenTimeAlerts.vibrate(this)
            }
        } else {
            val teamId = CloudTeams.teamId(this)
            val automatic = ScreenTimeAlerts.automaticTeamTime(this)
            val grant = teamSession.remaining(teamId, today)
            if (teamId.isBlank()) {
                showCover(CoverReason.BALANCE)
                return
            }
            if (!automatic && grant == 0) {
                showCover(CoverReason.TEAM_OFFER)
                return
            }
            spentTeam = CloudTeams.spendSecond(this, if (automatic) null else grant)
            if (!spentTeam) {
                showCover(CoverReason.TEAM_WAIT)
                return
            }
            if (!automatic) teamSession.spentSecond()
        }
        if (spentTeam) ScreenTimeLedger.record(this, "$spendReason · csapatkeret", -1)
        ProgressStore.used(this, 1)
        val newBreak = SocialUsage.recordSecond(this, currentPackage,
            advanceBreak = socialApp && (spentPersonal || spentTeam))
        if (newBreak > 0) showBreak(newBreak) else hideCover()
    }

    private fun showBreak(seconds: Int) = showCover(
        CoverReason.BREAK,
        "10 perc közösségi média után tarts egy rövid szünetet.\nFolytathatod $seconds másodperc múlva."
    )

    private fun showCover(reason: CoverReason, countdown: String? = null) {
        if (cover != null) {
            if (coverReason == reason) {
                if (countdown != null) coverDescription?.text = countdown
                return
            }
            hideCover()
        }
        coverReason = reason
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(48, 60, 48, 60)
            setBackgroundColor(Color.rgb(16, 22, 38))
        }
        val title = TextView(this).apply {
            text = when (reason) {
                CoverReason.QUEST -> "🔒 Fókusz\nQuest folyamatban!"
                CoverReason.BALANCE -> "🔒 Fókusz\nElfogyott a mai időkereted"
                CoverReason.BREAK -> "Fókusz\nKötelező szünet"
                CoverReason.APP_LIMIT -> "🔒 Fókusz\nElérted az app napi keretét"
                CoverReason.TEAM_OFFER, CoverReason.TEAM_WAIT -> "Fókusz\nElfogyott a saját időkereted"
            }
            textSize = 26f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
        }
        val desc = TextView(this).apply {
            text = when (reason) {
                CoverReason.QUEST -> "A küldetés időzítője alatt a közösségi appok zárolva vannak. Térj vissza a feladatodhoz!"
                CoverReason.BALANCE -> "A Fókusz alkalmazásban további perceket szerezhetsz."
                CoverReason.BREAK -> countdown.orEmpty()
                CoverReason.APP_LIMIT -> "A többi kiválasztott apphoz továbbra is használhatod a maradék idődet."
                CoverReason.TEAM_OFFER -> "Felhasználsz 5 percet a közös keretből? Az idő csak használat közben fogy."
                CoverReason.TEAM_WAIT -> "A közös idő most nem érhető el. A Fókusz újra próbálkozik."
            }
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
        panel.addView(title)
        panel.addView(desc)
        if (reason == CoverReason.TEAM_OFFER) {
            panel.addView(Button(this).apply {
                text = "Kérek 5 percet a csapattól"
                setOnClickListener {
                    teamSession.grant(CloudTeams.teamId(this@FocusService), LocalDate.now())
                    showCover(CoverReason.TEAM_WAIT)
                }
            })
        }
        panel.addView(button)
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )
        params.gravity = Gravity.CENTER
        try { wm.addView(panel, params); cover = panel; coverDescription = desc; windowManager = wm }
        catch (_: Exception) { cover = null }
    }

    private fun hideCover() {
        val view = cover ?: return
        cover = null
        coverDescription = null
        coverReason = null
        try { windowManager?.removeView(view) } catch (_: Exception) { }
    }

    override fun onDestroy() {
        isRunning = false
        handler.removeCallbacks(pulse)
        background.cancel()
        hideCover()
        super.onDestroy()
    }
    override fun onBind(intent: Intent?): IBinder? = null
}
