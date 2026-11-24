package hu.fokusz

import android.content.Context
import android.os.SystemClock
import android.provider.Settings
import org.json.JSONArray
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

internal enum class FocusAppRule(val label: String) {
    DEFAULT("Alapértelmezett"), ALLOW("Engedélyezve"), BLOCK("Tiltva")
}

object FocusPrefs {
    const val CLASSROOM_PACKAGE = "com.google.android.apps.classroom"
    const val MAX_FOCUS_NOTE = 2000
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
        CLASSROOM_PACKAGE,
        "com.duolingo", "com.application.xeropan",
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
    private val essentialPackages = setOf(
        CLASSROOM_PACKAGE, "android", "com.android.intentresolver", "com.android.systemui", "com.android.phone", "com.android.permissioncontroller",
        "com.google.android.permissioncontroller", "com.miui.home", "com.android.launcher3",
        "com.google.android.apps.nexuslauncher"
    )
    fun isEssential(c: Context, pkg: String): Boolean = pkg == c.packageName || pkg in essentialPackages

    fun alwaysAllowedInFocus(c: Context, pkg: String): Boolean = isEssential(c, pkg) || pkg in focusAllowed

    internal fun focusRule(c: Context, pkg: String): FocusAppRule {
        val saved = sp(c).getString("focus_rule_$pkg", null)
        return FocusAppRule.entries.firstOrNull { it.name == saved } ?: FocusAppRule.DEFAULT
    }

    internal fun setFocusRule(c: Context, pkg: String, rule: FocusAppRule) {
        val editor = sp(c).edit()
        if (rule == FocusAppRule.DEFAULT) editor.remove("focus_rule_$pkg") else editor.putString("focus_rule_$pkg", rule.name)
        editor.apply()
    }

    internal fun selectedSubject(c: Context): FocusSubject =
        FocusSubjects.all(c).firstOrNull { it.id == FocusSubjects.selected(c) } ?: FocusSubjects.general

    internal fun allowedDuringFocus(c: Context, pkg: String, subjectApps: Set<String>? = null): Boolean {
        if (pkg.isBlank() || isEssential(c, pkg)) return true
        return when (focusRule(c, pkg)) {
            FocusAppRule.ALLOW -> true
            FocusAppRule.BLOCK -> false
            FocusAppRule.DEFAULT -> pkg in focusAllowed ||
                pkg in (subjectApps ?: focus(c).getStringSet("subjectApps", emptySet()).orEmpty())
        }
    }

    private val standard = Store.BLOCKED + setOf("com.ss.android.ugc.trill")
    fun blocked(c: Context, pkg: String): Boolean {
        if (isEssential(c, pkg)) return false
        val p = sp(c)
        val canonical = SocialUsage.appKey(pkg)
        return p.getBoolean("blocked_$canonical", p.getBoolean("blocked_$pkg", pkg in standard))
    }
    fun setBlocked(c: Context, pkg: String, enabled: Boolean) {
        sp(c).edit().putBoolean("blocked_" + SocialUsage.appKey(pkg), enabled).apply()
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
        if (QuestStore.isCustomTimerLocked(c) || UserQuests.locked(c) || focusLocked(c)) return false
        sp(c).edit().putString("emergency_date", today())
            .putLong("emergency_until", SystemClock.elapsedRealtime() + 180000L).commit()
        return true
    }
    fun emergencyActive(c: Context): Boolean =
        sp(c).getString("emergency_date", "") == today() &&
            SystemClock.elapsedRealtime() < sp(c).getLong("emergency_until", 0L) &&
            !QuestStore.isCustomTimerLocked(c) && !UserQuests.locked(c) && !focusLocked(c)
    fun emergencyLeft(c: Context): Int {
        if (!emergencyActive(c)) return 0
        return ((sp(c).getLong("emergency_until", 0L) - SystemClock.elapsedRealtime()) / 1000L).toInt().coerceAtLeast(0)
    }

    private fun focus(c: Context) = c.getSharedPreferences("fokusz_focus_v1", Context.MODE_PRIVATE)
    fun focusPhase(c: Context): Int = focus(c).getInt("phase", 0) // 0 off,1 active,2 finished
    fun focusTarget(c: Context) = focus(c).getInt("target", 1500)
    internal fun startFocus(c: Context, seconds: Int, subject: FocusSubject = FocusSubjects.general): Boolean {
        if (QuestStore.isTimerLocked(c) || UserQuests.pending(c)) return false
        if (seconds !in 900..7200) return false
        return synchronized(this) {
            if (focusPhase(c) == 1) return@synchronized false
            val deadline = FocusDeadline.start(sampleTime(c), seconds * 1000L)
            focus(c).edit().putInt("phase", 1).putInt("target", seconds).putDeadline(deadline)
                .putString("sessionId", java.util.UUID.randomUUID().toString())
                .putString("subjectId", subject.id).putString("subjectName", subject.name)
                .putStringSet("subjectApps", subject.packages).remove("note").commit()
        }
    }
    fun focusSessionId(c: Context): String = focus(c).getString("sessionId", "").orEmpty()
    fun focusSubjectId(c: Context): String = focus(c).getString("subjectId", "").orEmpty()
    fun focusSubjectName(c: Context): String = focus(c).getString("subjectName", FocusSubjects.general.name)
        ?: FocusSubjects.general.name
    fun focusNote(c: Context): String = focus(c).getString("note", "").orEmpty()

    @Synchronized fun saveFocusNote(c: Context, text: String) {
        if (focusPhase(c) != 1) return
        focus(c).edit().putString("note", text.take(MAX_FOCUS_NOTE)).apply()
    }

    private fun rawFocusNotes(c: Context): JSONArray =
        runCatching { JSONArray(focus(c).getString("pastNotes", "[]")) }.getOrElse { JSONArray() }

    internal fun focusNotes(c: Context): List<FocusNote> {
        val archived = rawFocusNotes(c)
        return (0 until archived.length()).mapNotNull { archived.optJSONObject(it)?.let(FocusNote::fromJson) }
            .sortedByDescending { it.finishedAt }
    }

    private val deadlineMigration = Any()
    @Volatile private var cachedBoot: Int? = null

    private fun sampleTime(c: Context): FocusTime {
        val boot = cachedBoot ?: runCatching {
            Settings.Global.getInt(c.contentResolver, Settings.Global.BOOT_COUNT, -1)
        }.getOrDefault(-1).also { cachedBoot = it }
        return FocusTime(System.currentTimeMillis(), SystemClock.elapsedRealtime(), boot)
    }

    private fun android.content.SharedPreferences.Editor.putDeadline(deadline: FocusDeadline) =
        putLong("deadlineMillis", deadline.deadlineMillis)
            .putLong("clockWall", deadline.origin.wallMillis).putLong("clockElapsed", deadline.origin.elapsedMillis)
            .putInt("clockBoot", deadline.origin.boot)
            .putLong("pausedAtMillis", deadline.pausedAtMillis).putLong("pauseSpent", deadline.pauseSpentMillis)
            .remove("elapsed").remove("paused").remove("last")

    private fun readDeadline(values: Map<String, *>): FocusDeadline? {
        val end = values["deadlineMillis"] as? Long ?: return null
        val start = FocusTime(
            values["clockWall"] as? Long ?: return null,
            values["clockElapsed"] as? Long ?: return null,
            values["clockBoot"] as? Int ?: -1
        )
        val pauseSpent = (values["pauseSpent"] as? Long ?: 0L)
            .coerceIn(0L, FocusDeadline.PAUSE_LIMIT_MILLIS)
        return FocusDeadline(start, end, values["pausedAtMillis"] as? Long ?: 0L, pauseSpent)
    }

    private fun activeDeadline(c: Context, time: FocusTime): FocusDeadline? {
        val p = focus(c)
        val values = p.all
        if (values["phase"] != 1) return null
        return readDeadline(values) ?: synchronized(deadlineMigration) {
            val latest = p.all
            if (latest["phase"] != 1) return@synchronized null
            readDeadline(latest)?.let { return@synchronized it }
            // A korábbi verzió csak az elhasznált időt mentette.
            val remaining = (latest["target"] as? Int ?: 1500) * 1000L - (latest["elapsed"] as? Long ?: 0L)
            val migrated = FocusDeadline.start(time, remaining, latest["pauseSpent"] as? Long ?: 0L,
                latest["paused"] == true)
            if (p.edit().putDeadline(migrated).commit()) migrated else null
        }
    }

    internal fun focusTimer(c: Context): FocusTimer? {
        val time = sampleTime(c)
        return activeDeadline(c, time)?.view(time)
    }

    @Synchronized fun togglePause(c: Context): Boolean {
        val time = sampleTime(c)
        val next = activeDeadline(c, time)?.togglePause(time) ?: return false
        return focus(c).edit().putDeadline(next).commit()
    }

    fun focusLocked(c: Context): Boolean = (focusTimer(c)?.secondsLeft ?: 0) > 0
    @Synchronized fun claimFocus(c: Context): Boolean {
        val timer = focusTimer(c) ?: return false
        if (timer.secondsLeft > 0) return false
        val target = focusTarget(c)
        val subjectId = focusSubjectId(c)
        val subjectName = focusSubjectName(c)
        val note = focusNote(c).trim()
        val editor = focus(c).edit().putInt("phase", 2)
        if (note.isNotBlank()) {
            val result = FocusNote(subjectId, subjectName, timer.endsAtMillis, target, note)
            editor.putString("pastNotes", rawFocusNotes(c).put(result.toJson()).toString())
        }
        if (!editor.commit()) return false
        val credited = Store.addStudyReward(c, target / 2, "Fókuszidő jutalma")
        val completedDay = Instant.ofEpochMilli(timer.endsAtMillis).atZone(ZoneId.systemDefault()).toLocalDate()
        BossProgress.add(c, "quest_3", target, day = completedDay)
        val title = if (subjectId.isEmpty()) "Mélyfókusz" else "Mélyfókusz · $subjectName"
        ProgressStore.taskFinished(c, "STUDY", target, credited, title = title)
        return true
    }
    @Synchronized fun clearFocus(c: Context) {
        if (focusPhase(c) == 2) focus(c).edit().putInt("phase", 0).remove("note").commit()
    }

}
