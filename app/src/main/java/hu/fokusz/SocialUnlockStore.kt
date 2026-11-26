package hu.fokusz

import android.content.Context
import android.graphics.drawable.Drawable
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.YearMonth
import java.util.UUID

internal enum class SocialUnlockReason(val id: String, val icon: String, val label: String) {
    SCHOOL("school", "🎓", "Iskolai ügyet intézek"),
    MESSAGES("messages", "💬", "Megnézem az üzeneteimet"),
    CONTACT("contact", "📞", "El kell érnem valakit"),
    SEARCH("lookup", "🔎", "Utánanézek valaminek"),
    PUBLISH("share", "📤", "Elküldök vagy feltöltök valamit"),
    REST("break", "🎮", "Kikapcsolódnék egy kicsit");

    companion object {
        fun read(value: String) = entries.first { it.id == value || it.name == value }
    }
}

internal data class SocialUnlockApp(val packageName: String, val name: String, val icon: Drawable?)

internal data class SocialUnlockGrant(
    val id: String,
    val packageName: String,
    val appName: String,
    val minutes: Int,
    val reason: SocialUnlockReason,
    val startedAt: Long,
    val expiresAt: Long,
    val startedElapsed: Long,
    val boot: Int,
    val emergency: Boolean = false,
    val approved: Boolean = false,
) {
    fun millisLeft(time: FocusTime): Long {
        val duration = expiresAt - startedAt
        val elapsed = if (boot >= 0 && time.boot == boot && time.elapsedMillis >= startedElapsed) {
            time.elapsedMillis - startedElapsed
        } else {
            time.wallMillis - startedAt
        }
        return (duration - elapsed.coerceAtLeast(0)).coerceAtLeast(0)
    }
}

internal data class SocialUnlockEntry(val grant: SocialUnlockGrant, val usedSeconds: Int)

internal object SocialUnlockStore {
    private const val HISTORY_LIMIT = 100
    internal const val PREFS = "fokusz_social_unlock_v1"
    private val extraApps = setOf(
        "com.instagram.lite", "com.facebook.lite", "com.facebook.mlite", "com.zhiliaoapp.musically.go",
        "com.ss.android.ugc.tiktok.lite", "com.whatsapp.w4b", "org.thoughtcrime.securesms",
        "com.reddit.frontpage", "com.vkontakte.android", "com.weico.international",
        "com.tencent.mm", "com.LINE.verda", "jp.naver.line.android", "com.mewe",
        "org.joinmastodon.android", "com.nextdoor", "com.flickr.android", "tv.twitch.android.app",
        "org.thunderdog.challegram"
    )
    val standardPackages: Set<String> by lazy {
        Store.BLOCKED + Store.QUEST_SOCIAL + FocusPrefs.socialChoices.map { it.second }
    }
    val supportedPackages: Set<String> by lazy { standardPackages + extraApps }

    private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private fun activeKey(pkg: String) = "grant_$pkg"
    private fun usedKey(id: String) = "used_$id"

    fun apps(c: Context): List<SocialUnlockApp> = supportedPackages.mapNotNull { pkg ->
        if (FocusPrefs.isEssential(c, pkg)) null else installedApp(c, pkg)
    }.sortedBy { it.name.lowercase(java.util.Locale.getDefault()) }

    fun app(c: Context, pkg: String): SocialUnlockApp =
        installedApp(c, pkg) ?: SocialUnlockApp(pkg, pkg, null)

    @Suppress("DEPRECATION")
    private fun installedApp(c: Context, pkg: String): SocialUnlockApp? = runCatching {
        val manager = c.packageManager
        val info = manager.getApplicationInfo(pkg, 0)
        SocialUnlockApp(pkg, info.loadLabel(manager).toString().ifBlank { pkg }, info.loadIcon(manager))
    }.getOrNull()

    fun selected(c: Context, pkg: String): Boolean = pkg in supportedPackages &&
        !FocusPrefs.isEssential(c, pkg) && pkg in prefs(c).getStringSet("selected", emptySet()).orEmpty()

    fun hasSelectedApps(c: Context) = prefs(c).getStringSet("selected", emptySet()).orEmpty().isNotEmpty()

    fun guideSeen(c: Context) = prefs(c).getBoolean("guideSeen", false)
    fun dismissGuide(c: Context) = prefs(c).edit().putBoolean("guideSeen", true).apply()

    fun jokes(c: Context) = prefs(c).getBoolean("jokes", true)
    fun setJokes(c: Context, enabled: Boolean) = prefs(c).edit().putBoolean("jokes", enabled).apply()

    fun emergencyUsedToday(c: Context) = prefs(c).getString("emergencyDay", null) == LocalDate.now().toString()

    fun emergencyMinutes(c: Context, pkg: String): Int {
        if (!FocusPrefs.emergencyAvailable(c) || FocusPrefs.focusLocked(c) ||
            UserQuests.locked(c) || QuestStore.isCustomTimerLocked(c)) return 0
        return minOf(3L, remainingDailySeconds(c, pkg) / 60L).toInt()
    }

    private fun dayKey(date: LocalDate) = "activity_$date"
    fun baseline(c: Context) = prefs(c).getInt("baselineMinutes", 0)
    fun setBaseline(c: Context, minutes: Int) = minutes in 1..1440 &&
        baseline(c) == 0 && prefs(c).edit().putInt("baselineMinutes", minutes).commit()

    fun dailyUsed(c: Context, date: LocalDate): Int? = prefs(c).let { p ->
        val value = p.getInt(dayKey(date), -1)
        value.takeIf { it >= 0 }
    }

    @Synchronized fun observeDay(c: Context, day: LocalDate) {
        val p = prefs(c)
        val key = dayKey(day)
        if (p.contains(key)) return
        val zone = java.time.ZoneId.systemDefault()
        val measured = records(c).filter {
            java.time.Instant.ofEpochMilli(it.startedAt).atZone(zone).toLocalDate() == day
        }.sumOf { p.getInt(usedKey(it.id), 0) }
        p.edit().putInt(key, measured).apply()
    }

    @Synchronized fun recordCancellation(c: Context) {
        val p = prefs(c)
        val day = LocalDate.now()
        val key = "cancelled_$day"
        p.edit().putInt(key, cancelled(c, day) + 1).apply()
    }

    fun cancelled(c: Context, date: LocalDate = LocalDate.now()): Int =
        prefs(c).getInt("cancelled_$date", 0)

    fun active(c: Context, time: FocusTime = FocusPrefs.sampleTime(c)): List<SocialUnlockGrant> =
        prefs(c).getStringSet("selected", emptySet()).orEmpty().mapNotNull { current(c, it, time) }
            .sortedBy(SocialUnlockGrant::expiresAt)

    @Synchronized fun close(c: Context, grant: SocialUnlockGrant): Boolean {
        if (current(c, grant.packageName)?.id != grant.id) return false
        return prefs(c).edit().remove(activeKey(grant.packageName)).commit()
    }

    fun restUntil(c: Context): Long = prefs(c).getLong("restUntil", 0L)
        .takeIf { it > System.currentTimeMillis() } ?: 0L

    fun canStartRest(c: Context) = restUntil(c) == 0L &&
        prefs(c).getString("restMonth", null) != YearMonth.now().toString()

    @Synchronized fun startRest(c: Context): Boolean = canStartRest(c) && prefs(c).edit()
        .putString("restMonth", YearMonth.now().toString())
        .putLong("restUntil", System.currentTimeMillis() + 86_400_000L).commit()

    fun stopRest(c: Context) = prefs(c).edit().remove("restUntil").commit()

    fun limitReached(c: Context, pkg: String): Boolean =
        SocialUsage.dailyLimitReached(c, pkg) && !(selected(c, pkg) && restUntil(c) > 0L)

    private fun remainingDailySeconds(c: Context, pkg: String): Long {
        val daily = SocialUsage.dailyLimitMinutes(c, pkg)
        return if (daily == 0 || restUntil(c) > 0L) Long.MAX_VALUE
        else (daily * 60L - SocialUsage.dailySeconds(c, pkg)).coerceAtLeast(0L)
    }

    @Synchronized
    fun setSelected(c: Context, pkg: String, selected: Boolean): Boolean {
        if (pkg !in supportedPackages || FocusPrefs.isEssential(c, pkg)) return false
        val p = prefs(c)
        val current = p.getStringSet("selected", emptySet()).orEmpty()
        val edit = p.edit().putStringSet("selected", if (selected) current + pkg else current - pkg)
        if (!selected || pkg !in current) edit.remove(activeKey(pkg))
        return edit.commit()
    }

    private fun creditKey(pkg: String) = "approved_$pkg"

    fun approvedMinutes(c: Context, pkg: String): Int =
        (minOf(prefs(c).getInt(creditKey(pkg), 0).toLong(), remainingDailySeconds(c, pkg)) / 60L).toInt()

    @Synchronized fun creditApproved(c: Context, requestId: String, pkg: String, seconds: Int): Boolean {
        if (pkg !in supportedPackages || seconds !in 60..3600) return false
        val p = prefs(c)
        val receipt = "approval_$requestId"
        if (p.getBoolean(receipt, false)) return true
        val balance = (p.getInt(creditKey(pkg), 0).toLong() + seconds).coerceAtMost(Store.MAX_SAFE_SECONDS.toLong())
        return p.edit().putBoolean(receipt, true).putInt(creditKey(pkg), balance.toInt()).commit()
    }

    fun availableMinutes(c: Context, pkg: String): Int {
        val shared = CloudTeams.availableSeconds(c)
        val balance = (Store.bal(c).toLong() + shared).coerceAtMost(Store.MAX_SAFE_SECONDS.toLong())
        val available = minOf(balance, remainingDailySeconds(c, pkg))
        return (available / 60L).toInt()
    }

    fun current(c: Context, pkg: String, time: FocusTime = FocusPrefs.sampleTime(c)): SocialUnlockGrant? {
        if (!selected(c, pkg)) return null
        return prefs(c).getString(activeKey(pkg), null)?.let(::decode)?.takeIf { it.millisLeft(time) > 0L }
    }

    @Synchronized
    fun grant(c: Context, app: SocialUnlockApp, minutes: Int, reason: SocialUnlockReason,
              time: FocusTime = FocusPrefs.sampleTime(c), emergency: Boolean = false, approved: Boolean = false): SocialUnlockGrant? {
        val pkg = app.packageName
        if (!selected(c, pkg) || current(c, pkg, time) != null || (emergency && approved)) return null
        val available = when {
            emergency -> emergencyMinutes(c, pkg)
            approved -> approvedMinutes(c, pkg)
            else -> availableMinutes(c, pkg)
        }
        if (minutes !in 1..available) return null
        val grant = SocialUnlockGrant(UUID.randomUUID().toString(), pkg, app.name, minutes, reason,
            time.wallMillis, time.wallMillis + minutes * 60_000L, time.elapsedMillis, time.boot, emergency, approved)
        val p = prefs(c)
        val history = records(c)
        val evicted = history.drop(HISTORY_LIMIT - 1)
        for (removed in evicted) {
            val saved = runCatching {
                SocialUnlockArchive.get(c).record(removed.id, removed.startedAt,
                    encode(removed).toString(), p.getInt(usedKey(removed.id), 0))
            }
            if (saved.isFailure) return null
        }
        val entries = (listOf(grant) + history).take(HISTORY_LIMIT)
        val edit = p.edit().putString(activeKey(pkg), encode(grant).toString())
            .putString("history", JSONArray(entries.map(::encode)).toString())
        val day = LocalDate.now()
        val dayKey = dayKey(day)
        if (!p.contains(dayKey)) {
            val previousSeconds = history.filter { entry ->
                java.time.Instant.ofEpochMilli(entry.startedAt).atZone(java.time.ZoneId.systemDefault()).toLocalDate() == day
            }.sumOf { p.getInt(usedKey(it.id), 0) }
            edit.putInt(dayKey, previousSeconds)
        }
        if (emergency) edit.putString("emergencyDay", LocalDate.now().toString())
        evicted.forEach { removed ->
            val active = p.getString(activeKey(removed.packageName), null)?.let(::decode)
            if (active == null || active.id != removed.id || active.millisLeft(time) == 0L) edit.remove(usedKey(removed.id))
        }
        return grant.takeIf { edit.commit() }
    }

    @Synchronized
    fun recordSecond(c: Context, grant: SocialUnlockGrant): Boolean {
        val p = prefs(c)
        val key = usedKey(grant.id)
        val seconds = p.getInt(key, 0)
        val day = dayKey(LocalDate.now())
        if (seconds >= grant.minutes * 60) return false
        val update = p.edit().putInt(key, seconds + 1).putInt(day, p.getInt(day, 0) + 1)
        if (grant.approved) {
            val credit = creditKey(grant.packageName)
            val balance = p.getInt(credit, 0)
            if (balance <= 0) return false
            return update.putInt(credit, balance - 1).commit()
        }
        update.apply()
        return true
    }

    fun history(c: Context): List<SocialUnlockEntry> {
        val p = prefs(c)
        val recent = records(c).map { SocialUnlockEntry(it, p.getInt(usedKey(it.id), 0)) }
        val archived = runCatching { SocialUnlockArchive.get(c).rows() }.getOrDefault(emptyList())
            .mapNotNull { (json, used) ->
                decode(json)?.let { SocialUnlockEntry(it, p.getInt(usedKey(it.id), used)) }
            }
        return (recent + archived).distinctBy { it.grant.id }
    }

    private fun records(c: Context): List<SocialUnlockGrant> = runCatching {
        val list = JSONArray(prefs(c).getString("history", "[]"))
        (0 until list.length()).mapNotNull { i -> list.optJSONObject(i)?.toString()?.let(::decode) }
    }.getOrDefault(emptyList())

    private fun encode(grant: SocialUnlockGrant): JSONObject = JSONObject()
        .put("id", grant.id).put("package", grant.packageName).put("name", grant.appName)
        .put("minutes", grant.minutes).put("reason", grant.reason.id).put("emergency", grant.emergency).put("approved", grant.approved)
        .put("start", grant.startedAt).put("end", grant.expiresAt)
        .put("elapsed", grant.startedElapsed).put("boot", grant.boot)

    private fun decode(text: String): SocialUnlockGrant? = runCatching {
        val item = JSONObject(text)
        SocialUnlockGrant(item.getString("id"), item.getString("package"), item.getString("name"),
            item.getInt("minutes"), SocialUnlockReason.read(item.getString("reason")),
            item.getLong("start"), item.getLong("end"), item.getLong("elapsed"), item.getInt("boot"),
            item.optBoolean("emergency"), item.optBoolean("approved"))
    }.getOrNull()
}
