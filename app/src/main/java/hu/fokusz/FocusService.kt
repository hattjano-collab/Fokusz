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
import android.content.res.Configuration
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
    private enum class CoverReason { QUEST, BALANCE, BREAK, APP_LIMIT, TEAM_OFFER, TEAM_WAIT, TEAM_PENDING, TEAM_AUDIO, RECHECK }

    companion object {
        @Volatile var isRunning = false
        private const val CHANNEL = "fokusz-monitor"
        private const val METER_INTERVAL_MS = 1000L
        private const val FOREGROUND_POLL_INTERVAL_MS = 250L
        private var running: FocusService? = null

        internal fun refreshUnlocks() { running?.refreshCover() }
    }
    private val handler = Handler(Looper.getMainLooper())
    private val balanceWarning = BalanceWarning()
    private val teamSession = TeamTimeSession()
    private var lastEventTime = 0L
    private var currentPackage = ""
    private var resumedPackage = ""
    private var resumedClass: String? = null
    private var previousPackage = ""
    private var cover: View? = null
    private var coverReason: CoverReason? = null
    private var coverDescription: TextView? = null
    private var coverPreludeId: String? = null
    private var unlockOverlay: SocialUnlockOverlay? = null
    private var unlockPackage = ""
    private var unlockExpiredMinutes: Int? = null
    private var unlockDraft = SocialUnlockDraft()
    private var unlockFinishing = false
    private var unlockLeaving = false
    private var visibleGrant: SocialUnlockGrant? = null
    private var heardPrelude = ""
    private var playingPrelude = ""
    private var voicePlayer: TeamVoicePlayer? = null
    private val expiryPulse = Runnable { refreshCover() }
    private var windowManager: WindowManager? = null
    private val background=CoroutineScope(SupervisorJob()+Dispatchers.IO)
    private var lastNotification=0L
    private var lastChessSync=0L
    private var lastDuolingoSync=0L
    private var lastObservedDay: LocalDate? = null

    private val pulse = object : Runnable {
        private var nextMeterAt = 0L

        override fun run() {
            val now = SystemClock.uptimeMillis()
            val fastChecks = runCatching { SocialUnlockStore.hasSelectedApps(this@FocusService) && screenAvailable() }
                .getOrDefault(false)
            try {
                if (now >= nextMeterAt) {
                    nextMeterAt = now + METER_INTERVAL_MS
                    monitor()
                } else if (fastChecks) {
                    pollForeground()
                }
            } catch (_: Exception) { coverAfterFailure() }
            val next = if (fastChecks) minOf(nextMeterAt, now + FOREGROUND_POLL_INTERVAL_MS) else nextMeterAt
            handler.postAtTime(this, next)
        }
    }

    private fun screenAvailable(): Boolean = (getSystemService(POWER_SERVICE) as PowerManager).isInteractive &&
        getSystemService(KeyguardManager::class.java)?.isKeyguardLocked != true

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Fókusz figyelés", NotificationManager.IMPORTANCE_LOW))
        startForeground(2,liveNotification())
        isRunning = true
        running = this
        CloudTeams.start(this)
        QuestStore.resetClock(this)
        UserQuests.resetClock(this)
        handler.post(pulse)
    }

    private fun liveNotification():Notification {
        val intent=Intent(this,MainActivity::class.java)
        val pi=PendingIntent.getActivity(this,0,intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val custom = UserQuests.active(this)
        val focus = FocusPrefs.focusTimer(this)
        val part=when {
            custom != null && custom.left > 0 -> "Saját quest: " + clock(custom.left)
            QuestStore.isTimerLocked(this) -> "Quest: "+clock(QuestStore.secondsLeft(this))
            focus != null && focus.secondsLeft > 0 -> "Fókusz: " + clock(focus.secondsLeft) +
                (if (focus.paused) " (szünet)" else "")
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

    private fun refreshCover() {
        try { monitor(allowCharge = false) } catch (_: Exception) { coverAfterFailure() }
    }

    private fun coverAfterFailure() {
        runCatching {
            if (SocialUnlockStore.selected(this, currentPackage) && Settings.canDrawOverlays(this)) {
                showCover(CoverReason.RECHECK)
            } else hideCover()
        }
    }

    private fun monitor(allowCharge: Boolean = true) {
        handler.removeCallbacks(expiryPulse)
        if (!UsageGuard.allowed(this) || !Settings.canDrawOverlays(this)) { stopSelf(); return }
        if (allowCharge) updateBackgroundWork()
        if (!screenAvailable()) {
            teamSession.clear()
            visibleGrant = null
            heardPrelude = ""
            unlockDraft = SocialUnlockDraft()
            hideCover()
            return
        }
        if (allowCharge && SocialUnlockStore.hasSelectedApps(this)) {
            val day = LocalDate.now()
            if (lastObservedDay != day) {
                SocialUnlockStore.observeDay(this, day)
                lastObservedDay = day
            }
        }
        readForeground()
        val pkg = currentPackage
        if (pkg.isBlank() || FocusPrefs.isEssential(this, pkg) || QuizDraft.isCameraActive(pkg)) {
            teamSession.clear()
            hideCover()
            return
        }
        val restrictions = restrictions()
        if (blockedByTimer(pkg, restrictions)) {
            teamSession.clear()
            showCover(CoverReason.QUEST, timerDescription(restrictions))
            return
        }
        if (SocialUnlockStore.limitReached(this, pkg)) {
            teamSession.clear()
            showCover(CoverReason.APP_LIMIT)
            return
        }
        val selected = SocialUnlockStore.selected(this, pkg)
        val chargeBalance = selected || (!restrictions.focusRulesActive && FocusPrefs.blocked(this, pkg))
        if (!chargeBalance) {
            if (allowCharge) SocialUsage.recordSecond(this, pkg, advanceBreak = false)
            teamSession.clear()
            visibleGrant = null
            hideCover()
            return
        }
        val socialApp = selected || pkg in SocialUnlockStore.standardPackages
        val breakLeft = if (socialApp) SocialUsage.breakSecondsLeft(this) else 0
        if (breakLeft > 0) {
            showBreak(breakLeft)
            return
        }
        if (selected && SocialTeamRequests.waitingForTime(this, pkg)) {
            teamSession.clear()
            showCover(CoverReason.TEAM_PENDING)
            return
        }
        val time = if (selected) FocusPrefs.sampleTime(this) else null
        val grant = time?.let { SocialUnlockStore.current(this, pkg, it) }
        if (time != null && grant != null) {
            visibleGrant = grant
            handler.postDelayed(expiryPulse, grant.millisLeft(time))
        }
        if (selected && grant == null) {
            val expiredMinutes = visibleGrant?.takeIf { it.packageName == pkg }?.minutes
            showUnlock(pkg, expiredMinutes)
            return
        }
        if (unlockFinishing) return
        if (unlockOverlay != null) hideCover()
        val emergency = if (selected) grant?.emergency == true else restrictions.emergency
        if (allowCharge) chargeSecond(pkg, grant, emergency, socialApp)
    }

    private fun updateBackgroundWork() {
        QuestStore.tick(this)
        TeamVoiceGate.watch(this)
        UserQuests.tick(this)
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
    }

    private fun readForeground() {
        val us = getSystemService(USAGE_STATS_SERVICE) as UsageStatsManager
        val now = System.currentTimeMillis()
        if (lastEventTime > now) lastEventTime = 0L
        val window = if (lastEventTime == 0L) 30 * 60_000L else 30_000L
        val windowStart = maxOf(lastEventTime, now - window)
        val events = us.queryEvents(windowStart, now) ?: return
        val ev = UsageEvents.Event()
        val previouslySeen = lastEventTime
        while (events.hasNextEvent()) {
            events.getNextEvent(ev)
            if (ev.timeStamp <= previouslySeen) continue
            when (ev.eventType) {
                UsageEvents.Event.ACTIVITY_RESUMED -> setForeground(ev.packageName.orEmpty(), ev.className)
                UsageEvents.Event.ACTIVITY_PAUSED -> {
                    if (currentPackage == ev.packageName && resumedClass == ev.className) currentPackage = ""
                }
                else -> continue
            }
            lastEventTime = ev.timeStamp
        }
    }

    private fun pollForeground() {
        if (!UsageGuard.allowed(this) || !Settings.canDrawOverlays(this)) return
        val previous = currentPackage
        readForeground()
        if (previous != currentPackage && (SocialUnlockStore.selected(this, previous) ||
                SocialUnlockStore.selected(this, currentPackage))) refreshCover()
    }

    private fun setForeground(pkg: String, activity: String?) {
        if (pkg != resumedPackage) {
            previousPackage = resumedPackage
            resumedPackage = pkg
            visibleGrant = null
            heardPrelude = ""
            unlockDraft = SocialUnlockDraft()
            if (unlockOverlay != null) hideCover()
        }
        resumedClass = activity
        currentPackage = pkg
    }

    private data class Restrictions(
        val questLocked: Boolean,
        val legacyCustom: Boolean,
        val userQuest: Boolean,
        val focusLocked: Boolean,
        val scheduleLeft: Int,
        val subjectApps: Set<String>?,
        val emergency: Boolean,
    ) {
        val focusRulesActive: Boolean get() = focusLocked || legacyCustom || userQuest || scheduleLeft > 0
    }

    private fun restrictions(): Restrictions {
        val questLocked = QuestStore.isTimerLocked(this)
        val legacyCustom = questLocked && QuestStore.isCustomTimerLocked(this)
        val userQuest = UserQuests.locked(this)
        val focusLocked = FocusPrefs.focusLocked(this)
        val scheduleLeft = FocusSchedules.secondsLeft(this)
        val subjectApps = when {
            userQuest -> UserQuests.subjectApps(this)
            legacyCustom -> FocusPrefs.selectedSubject(this).packages
            scheduleLeft > 0 && !focusLocked -> emptySet()
            else -> null
        }
        return Restrictions(questLocked, legacyCustom, userQuest, focusLocked, scheduleLeft, subjectApps,
            FocusPrefs.emergencyActive(this))
    }

    private fun blockedByTimer(pkg: String, rules: Restrictions): Boolean {
        val normalQuestBlock = rules.questLocked && !rules.legacyCustom && !rules.emergency &&
            (FocusPrefs.blocked(this, pkg) || pkg in Store.BLOCKED || pkg in Store.QUEST_SOCIAL ||
                SocialUnlockStore.selected(this, pkg))
        val activeRule = rules.focusLocked || rules.legacyCustom || rules.userQuest ||
            (rules.scheduleLeft > 0 && !FocusSchedules.allowsException(this, pkg))
        return normalQuestBlock || (activeRule && !FocusPrefs.allowedDuringFocus(this, pkg, rules.subjectApps))
    }

    private fun timerDescription(rules: Restrictions): String {
        val timers = buildList {
            if (rules.focusLocked) FocusPrefs.focusTimer(this@FocusService)?.let { timer ->
                add("Fókusz: ${clock(timer.secondsLeft)}${if (timer.paused) " (szünet)" else ""}")
            }
            if (rules.scheduleLeft > 0) add("Ismétlődő fókusz: ${clock(rules.scheduleLeft)}")
            if (rules.questLocked) add("Quest: ${clock(QuestStore.secondsLeft(this@FocusService))}")
            if (rules.userQuest) UserQuests.active(this@FocusService)?.let { add("Saját quest: ${clock(it.left)}") }
        }
        return "Az aktív fókusz vagy quest tiltja ezt az alkalmazást.\n${timers.joinToString("\n")}".trimEnd()
    }

    private fun chargeSecond(pkg: String, unlock: SocialUnlockGrant?, emergency: Boolean, socialApp: Boolean) {
        val spendReason = ScreenTimeLedger.socialReason(pkg)
        if (unlock?.approved == true) {
            if (SocialUnlockStore.recordSecond(this, unlock)) {
                ScreenTimeLedger.record(this, "$spendReason · jóváhagyott pluszidő", -1)
                recordUsage(pkg, socialApp)
            } else showCover(CoverReason.BALANCE)
            return
        }
        val threshold = ScreenTimeAlerts.warningSeconds(this)
        val today = LocalDate.now()
        val personalBefore = Store.bal(this)
        if (personalBefore > threshold) balanceWarning.shouldVibrate(today, personalBefore, threshold)
        val spentPersonal = unlock?.emergency != true && Store.spend(this, 1, spendReason)
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
            val voiceMode = TeamVoiceGate.mode(this)
            val prelude = TeamVoiceGate.item(teamId)
            if (voiceMode == 2 && !TeamVoiceGate.loaded(teamId)) {
                showCover(CoverReason.TEAM_AUDIO, "A Fókusz ellenőrzi, van-e meghallgatandó hangüzenet.")
                return
            }
            if (prelude != null && prelude.id != heardPrelude && voiceMode > 0) {
                showCover(CoverReason.TEAM_AUDIO, prelude = prelude)
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
        if (unlock != null) SocialUnlockStore.recordSecond(this, unlock)
        recordUsage(pkg, socialApp && (spentPersonal || spentTeam || unlock?.emergency == true))
    }

    private fun recordUsage(pkg: String, advanceBreak: Boolean) {
        ProgressStore.used(this, 1)
        val newBreak = SocialUsage.recordSecond(this, pkg, advanceBreak = advanceBreak)
        if (newBreak > 0) showBreak(newBreak) else hideCover()
    }

    private fun showUnlock(pkg: String, expiredMinutes: Int?) {
        val existing = unlockOverlay
        if (existing != null && unlockPackage == pkg && unlockExpiredMinutes == expiredMinutes) {
            if (expiredMinutes == null) updateUnlockAvailability(existing, pkg)
            return
        }
        hideCover()
        val app = SocialUnlockStore.app(this, pkg)
        lateinit var overlay: SocialUnlockOverlay
        overlay = SocialUnlockOverlay(this, app, unlockDraft, expiredMinutes,
            onExit = { if (unlockOverlay === overlay) exitUnlock(pkg) },
            onRetry = {
                if (unlockOverlay === overlay) {
                    visibleGrant = null
                    unlockDraft = SocialUnlockDraft()
                    refreshCover()
                }
            },
            onConfirm = { minutes, reason, emergency, approved ->
                refreshCover()
                if (unlockOverlay !== overlay || currentPackage != pkg || unlockLeaving) false else {
                    val grant = SocialUnlockStore.grant(this, app, minutes, reason, emergency = emergency, approved = approved)
                    if (grant == null) false else {
                        runCatching { SocialTeamEvents.onGrant(this, grant) }
                        visibleGrant = grant
                        unlockFinishing = true
                        unlockDraft = SocialUnlockDraft()
                        true
                    }
                }
            },
            onAlternative = {
                if (unlockOverlay === overlay && !unlockLeaving) {
                    unlockLeaving = true
                    SocialUnlockStore.recordCancellation(this)
                    unlockDraft = SocialUnlockDraft()
                    runCatching {
                        startActivity(Intent(this, MainActivity::class.java).putExtra("focus_tab", 1)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP))
                    }
                }
            },
            onFinished = {
                if (unlockOverlay === overlay) {
                    hideCover()
                    refreshCover()
                }
            })
        if (expiredMinutes == null) updateUnlockAvailability(overlay, pkg)
        val wm = getSystemService(WindowManager::class.java)
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN, PixelFormat.OPAQUE
        ).apply {
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or
                WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN
        }
        try {
            wm.addView(overlay.view, params)
            windowManager = wm
            cover = overlay.view
            unlockOverlay = overlay
            unlockPackage = pkg
            unlockExpiredMinutes = expiredMinutes
            overlay.enter()
        } catch (_: Exception) { hideCover() }
    }

    private fun updateUnlockAvailability(overlay: SocialUnlockOverlay, pkg: String) {
        overlay.updateAvailability(SocialUnlockStore.availableMinutes(this, pkg),
            SocialUnlockStore.emergencyMinutes(this, pkg), SocialUnlockStore.approvedMinutes(this, pkg))
    }

    private fun exitUnlock(pkg: String) {
        if (unlockLeaving) return
        unlockLeaving = true
        if (unlockExpiredMinutes == null) SocialUnlockStore.recordCancellation(this)
        unlockDraft = SocialUnlockDraft()
        teamSession.clear()
        val rules = restrictions()
        val previous = previousPackage.takeIf { it.isNotBlank() && it != pkg }
        if (previous != null && canReturnTo(previous, rules)) {
            val intent = packageManager.getLaunchIntentForPackage(previous)?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (intent != null && runCatching { startActivity(intent) }.isSuccess) return
        }
        runCatching { startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }

    private fun canReturnTo(pkg: String, rules: Restrictions): Boolean {
        if (FocusPrefs.isEssential(this, pkg)) return true
        if (blockedByTimer(pkg, rules) || SocialUnlockStore.limitReached(this, pkg) ||
            SocialTeamRequests.waitingForTime(this, pkg)) return false
        val selected = SocialUnlockStore.selected(this, pkg)
        val grant = if (selected) SocialUnlockStore.current(this, pkg) ?: return false else null
        val chargesBalance = selected || (!rules.focusRulesActive && FocusPrefs.blocked(this, pkg))
        if (!chargesBalance) return true
        if ((selected || pkg in SocialUnlockStore.standardPackages) && SocialUsage.breakSecondsLeft(this) > 0) return false
        return grant?.emergency == true || grant?.approved == true || (!selected && rules.emergency) || Store.bal(this) > 0 || (ScreenTimeAlerts.automaticTeamTime(this) &&
            CloudTeams.leased(this) > 0)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (unlockOverlay != null && !unlockFinishing) {
            hideCover()
            refreshCover()
        }
    }

    private fun showBreak(seconds: Int) = showCover(
        CoverReason.BREAK,
        "10 perc közösségi média után tarts egy rövid szünetet.\nFolytathatod $seconds másodperc múlva."
    )

    private fun showCover(reason: CoverReason, countdown: String? = null, prelude: TeamChatItem? = null) {
        if (cover != null) {
            if (coverReason == reason && coverPreludeId == prelude?.id) {
                if (countdown != null) coverDescription?.text = countdown
                return
            }
            hideCover()
        }
        coverReason = reason
        coverPreludeId = prelude?.id
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(48, 60, 48, 60)
            setBackgroundColor(Color.rgb(16, 22, 38))
        }
        val title = TextView(this).apply {
            text = when (reason) {
                CoverReason.QUEST -> "🔒 Fókusz\nZárolt alkalmazás"
                CoverReason.BALANCE -> "🔒 Fókusz\nElfogyott a mai időkereted"
                CoverReason.RECHECK -> "Fókusz\nA feloldás ellenőrzése"
                CoverReason.BREAK -> "Fókusz\nKötelező szünet"
                CoverReason.APP_LIMIT -> "🔒 Fókusz\nElérted az app napi keretét"
                CoverReason.TEAM_AUDIO -> "Fókusz\nHangüzenet a közös idő előtt"
                CoverReason.TEAM_PENDING -> "Fókusz\nA csapat döntésére vársz"
                CoverReason.TEAM_OFFER, CoverReason.TEAM_WAIT -> "Fókusz\nElfogyott a saját időkereted"
            }
            textSize = 26f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
        }
        val desc = TextView(this).apply {
            text = when (reason) {
                CoverReason.QUEST -> countdown.orEmpty()
                CoverReason.BALANCE -> "A Fókusz alkalmazásban további perceket szerezhetsz."
                CoverReason.RECHECK -> "A Fókusz rövidesen újra ellenőrzi az engedélyt."
                CoverReason.BREAK -> countdown.orEmpty()
                CoverReason.APP_LIMIT -> "A többi kiválasztott apphoz továbbra is használhatod a maradék idődet."
                CoverReason.TEAM_OFFER -> "Felhasználsz 5 percet a közös keretből? Az idő csak használat közben fogy."
                CoverReason.TEAM_AUDIO -> countdown ?: if (TeamVoiceGate.mode(this@FocusService) == 2)
                    "A folytatáshoz hallgasd végig a csapat hangüzenetét." else "A csapat hagyott neked egy üzenetet."
                CoverReason.TEAM_PENDING -> "A kért pluszidő jóváhagyásáig ez az app zárolva van."
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
        if (reason == CoverReason.TEAM_AUDIO && prelude != null) {
            panel.addView(Button(this).apply {
                text = "Hangüzenet lejátszása"
                setOnClickListener {
                    if (playingPrelude == prelude.id) return@setOnClickListener
                    val attachment = prelude.attachment()
                    if (attachment == null) {
                        desc.text = "A hangüzenet sérült."
                        return@setOnClickListener
                    }
                    val player = TeamVoicePlayer(this@FocusService)
                    voicePlayer = player
                    playingPrelude = prelude.id
                    if (player.play(attachment) { completed ->
                        playingPrelude = ""
                        if (completed && coverReason == CoverReason.TEAM_AUDIO) {
                            heardPrelude = prelude.id
                            desc.text = "A közös idő ellenőrzése…"
                        } else desc.text = "A hangüzenetet most nem sikerült lejátszani."
                    }) {
                        desc.text = "Hangüzenet lejátszása folyamatban…"
                    } else {
                        playingPrelude = ""
                        desc.text = "A hangüzenetet most nem sikerült lejátszani."
                    }
                }
            })
            if (TeamVoiceGate.mode(this) == 1) {
                panel.addView(Button(this).apply {
                    text = "Tovább a közös időhöz"
                    setOnClickListener { heardPrelude = prelude.id; desc.text = "A közös idő ellenőrzése…" }
                })
            }
        }
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
        coverPreludeId = null
        voicePlayer?.stop()
        voicePlayer = null
        playingPrelude = ""
        unlockOverlay = null
        unlockFinishing = false
        unlockLeaving = false
        try { windowManager?.removeView(view) } catch (_: Exception) { }
    }

    override fun onDestroy() {
        isRunning = false
        if (running === this) running = null
        handler.removeCallbacks(pulse)
        handler.removeCallbacks(expiryPulse)
        background.cancel()
        hideCover()
        super.onDestroy()
    }
    override fun onBind(intent: Intent?): IBinder? = null
}
