package hu.fokusz

import android.content.Context
import android.os.SystemClock
import java.time.LocalDate

object FocusPrefs {
    private fun sp(c: Context) = c.getSharedPreferences("fokusz_options_v1", Context.MODE_PRIVATE)
    val socialChoices = listOf(
        "TikTok" to "com.zhiliaoapp.musically",
        "Instagram" to "com.instagram.android",
        "YouTube" to "com.google.android.youtube",
        "Snapchat" to "com.snapchat.android",
        "X" to "com.twitter.android",
        "Facebook" to "com.facebook.katana",
        "Reddit" to "com.reddit.frontpage",
        "Discord" to "com.discord",
        "WhatsApp" to "com.whatsapp",
        "Telegram" to "org.telegram.messenger",
        "Messenger" to "com.facebook.orca",
        "Threads" to "com.instagram.barcelona",
        "Pinterest" to "com.pinterest",
        "BeReal" to "com.bereal.ft"
    )
    // A fókuszmód engedélyezett appjai: Google kereső, ChatGPT, az alkalmazás
    // és az alapvető telefonos/biztonsági szolgáltatások.
    private val focusAllowed = setOf(
        "com.google.android.googlequicksearchbox", "com.openai.chatgpt",
        "com.google.android.apps.classroom",
        "com.android.settings", "com.android.systemui",
        "com.google.android.gms", "com.android.phone", "com.google.android.dialer",
        "com.android.dialer", "com.google.android.contacts",
        "com.google.android.apps.messaging", "com.android.mms",
        "com.google.android.deskclock", "com.android.deskclock",
        "com.miui.home", "com.android.launcher3", "com.google.android.apps.nexuslauncher",
        "com.miui.securitycenter", "com.android.permissioncontroller",
        "com.google.android.permissioncontroller", "com.android.packageinstaller",
        "com.google.android.packageinstaller", "com.google.android.inputmethod.latin",
        "com.android.inputmethod.latin", "com.miui.guardprovider"
    )
    fun allowedDuringFocus(c:Context, pkg:String):Boolean =
        pkg.isBlank() || pkg==c.packageName || pkg in focusAllowed

    private val standard = Store.BLOCKED + setOf("com.ss.android.ugc.trill")
    fun blocked(c: Context, pkg: String): Boolean {
        val p = sp(c)
        return p.getBoolean("blocked_" + pkg, pkg in standard)
    }
    fun setBlocked(c: Context, pkg: String, enabled: Boolean) {
        sp(c).edit().putBoolean("blocked_" + pkg, enabled).apply()
    }
    fun maxBalance(c: Context) = sp(c).getInt("maximum_seconds", 14400)
    fun setMaxBalance(c: Context, sec: Int) {
        sp(c).edit().putInt("maximum_seconds", sec.coerceIn(1800, 21600)).apply()
    }
    fun autoRestart(c:Context)=sp(c).getBoolean("auto_restart",false)
    fun setAutoRestart(c:Context,enabled:Boolean) {
        sp(c).edit().putBoolean("auto_restart",enabled).apply()
    }
    fun theme(c: Context) = sp(c).getString("theme", "menta") ?: "menta"
    fun setTheme(c: Context, value: String) { sp(c).edit().putString("theme", value).apply() }

    private fun today() = LocalDate.now().toString()
    @Synchronized fun emergencyAvailable(c: Context): Boolean =
        sp(c).getString("emergency_date", "") != today()
    @Synchronized fun activateEmergency(c: Context): Boolean {
        if (!emergencyAvailable(c)) return false
        // Ne lehessen az egyéni quest teljes lezárását feloldani.
        if (QuestStore.isCustomTimerLocked(c) || focusLocked(c)) return false
        sp(c).edit().putString("emergency_date", today())
            .putLong("emergency_until", SystemClock.elapsedRealtime() + 180000L).commit()
        return true
    }
    fun emergencyActive(c: Context): Boolean =
        sp(c).getString("emergency_date", "") == today() &&
            SystemClock.elapsedRealtime() < sp(c).getLong("emergency_until", 0L) &&
            !QuestStore.isCustomTimerLocked(c) && !focusLocked(c)
    fun emergencyLeft(c: Context): Int {
        if (!emergencyActive(c)) return 0
        return ((sp(c).getLong("emergency_until", 0L) - SystemClock.elapsedRealtime()) / 1000L).toInt().coerceAtLeast(0)
    }

    private fun focus(c: Context) = c.getSharedPreferences("fokusz_focus_v1", Context.MODE_PRIVATE)
    @Synchronized fun focusPhase(c: Context): Int = focus(c).getInt("phase", 0) // 0 off,1 active,2 finished
    @Synchronized fun focusTarget(c: Context) = focus(c).getInt("target", 1500)
    @Synchronized fun startFocus(c: Context, seconds: Int): Boolean {
        if (focusPhase(c) == 1) return false
        if (QuestStore.isTimerLocked(c)) return false
        if (seconds !in 900..7200) return false
        focus(c).edit().putInt("phase", 1).putInt("target", seconds).putLong("elapsed", 0)
            .putBoolean("paused",false).putLong("pauseSpent",0L)
            .putLong("last", SystemClock.elapsedRealtime()).commit()
        return true
    }
    fun focusPaused(c:Context):Boolean=focus(c).getBoolean("paused",false)
    fun pauseUsed(c:Context):Int=(focus(c).getLong("pauseSpent",0L)/1000L).toInt()
    @Synchronized fun togglePause(c:Context):Boolean {
        if(focusPhase(c)!=1 || focusLeft(c)==0) return false
        val s=focus(c)
        if(!focusPaused(c) && pauseUsed(c)>=300) return false
        s.edit().putBoolean("paused",!focusPaused(c))
            .putLong("last",SystemClock.elapsedRealtime()).commit()
        return true
    }
    @Synchronized fun tick(c: Context) {
        if (focusPhase(c) != 1) return
        val s = focus(c)
        val now = SystemClock.elapsedRealtime()
        val last = s.getLong("last", 0L)
        val delta = if (last > 0 && now >= last) (now - last).coerceIn(0, 1500) else 0
        if(focusPaused(c)) {
            val paused=(s.getLong("pauseSpent",0L)+delta).coerceAtMost(300000L)
            s.edit().putLong("pauseSpent",paused).putLong("last",now)
                .putBoolean("paused",paused<300000L).apply()
        } else {
            val elapsed = (s.getLong("elapsed", 0L) + delta).coerceAtMost(focusTarget(c) * 1000L)
            s.edit().putLong("elapsed", elapsed).putLong("last", now).apply()
        }
    }
    @Synchronized fun resetClock(c: Context) {
        if (focusPhase(c) == 1) focus(c).edit().putLong("last", SystemClock.elapsedRealtime()).apply()
    }
    fun focusLeft(c: Context): Int =
        ((focusTarget(c) * 1000L - focus(c).getLong("elapsed", 0L)).coerceAtLeast(0L) + 999L).div(1000).toInt()
    fun focusLocked(c: Context) = focusPhase(c) == 1 && focusLeft(c) > 0
    @Synchronized fun claimFocus(c: Context): Boolean {
        if (focusPhase(c) != 1 || focusLeft(c) > 0) return false
        focus(c).edit().putInt("phase", 2).commit()
        val reward = focusTarget(c) / 2
        Store.add(c, reward)
        ProgressStore.taskFinished(c, "STUDY", focusTarget(c), reward)
        return true
    }
    @Synchronized fun clearFocus(c: Context) {
        if (focusPhase(c) == 2) focus(c).edit().putInt("phase", 0).commit()
    }

}
