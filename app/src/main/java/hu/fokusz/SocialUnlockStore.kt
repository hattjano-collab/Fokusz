package hu.fokusz

import android.content.Context
import android.graphics.drawable.Drawable
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

internal enum class SocialUnlockReason(val icon: String, val label: String) {
    SCHOOL("🎓", "Iskolai ügyet intézek"),
    MESSAGES("💬", "Megnézem az üzeneteimet"),
    CONTACT("☎", "El kell érnem valakit"),
    SEARCH("⌕", "Utánanézek valaminek"),
    PUBLISH("↑", "Elküldök vagy feltöltök valamit"),
    REST("🎮", "Kikapcsolódnék")
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
        SocialUnlockApp(pkg, info.loadLabel(manager).toString(), info.loadIcon(manager))
    }.getOrNull()

    fun selected(c: Context, pkg: String): Boolean = pkg in supportedPackages &&
        !FocusPrefs.isEssential(c, pkg) && pkg in prefs(c).getStringSet("selected", emptySet()).orEmpty()

    @Synchronized
    fun setSelected(c: Context, pkg: String, selected: Boolean): Boolean {
        if (pkg !in supportedPackages || FocusPrefs.isEssential(c, pkg)) return false
        val p = prefs(c)
        val current = p.getStringSet("selected", emptySet()).orEmpty()
        val edit = p.edit().putStringSet("selected", if (selected) current + pkg else current - pkg)
        if (!selected || pkg !in current) edit.remove(activeKey(pkg))
        return edit.commit()
    }

    fun availableMinutes(c: Context, pkg: String): Int {
        val shared = CloudTeams.availableSeconds(c)
        val balance = (Store.bal(c).toLong() + shared).coerceAtMost(Store.MAX_SAFE_SECONDS.toLong())
        val daily = SocialUsage.dailyLimitMinutes(c, pkg)
        val available = if (daily == 0) balance else minOf(balance, (daily * 60L - SocialUsage.dailySeconds(c, pkg)).coerceAtLeast(0))
        return (available / 60L).toInt()
    }

    fun current(c: Context, pkg: String, time: FocusTime = FocusPrefs.sampleTime(c)): SocialUnlockGrant? {
        if (!selected(c, pkg)) return null
        return prefs(c).getString(activeKey(pkg), null)?.let(::decode)?.takeIf { it.millisLeft(time) > 0L }
    }

    @Synchronized
    fun grant(c: Context, app: SocialUnlockApp, minutes: Int, reason: SocialUnlockReason,
              time: FocusTime = FocusPrefs.sampleTime(c)): SocialUnlockGrant? {
        val pkg = app.packageName
        if (!selected(c, pkg) || minutes !in 1..availableMinutes(c, pkg) || current(c, pkg, time) != null) return null
        val grant = SocialUnlockGrant(UUID.randomUUID().toString(), pkg, app.name, minutes, reason,
            time.wallMillis, time.wallMillis + minutes * 60_000L, time.elapsedMillis, time.boot)
        val p = prefs(c)
        val history = records(c)
        val entries = (listOf(grant) + history).take(HISTORY_LIMIT)
        val edit = p.edit().putString(activeKey(pkg), encode(grant).toString())
            .putString("history", JSONArray(entries.map(::encode)).toString())
        history.drop(HISTORY_LIMIT - 1).forEach { removed ->
            val active = p.getString(activeKey(removed.packageName), null)?.let(::decode)
            if (active == null || active.id != removed.id || active.millisLeft(time) == 0L) edit.remove(usedKey(removed.id))
        }
        return grant.takeIf { edit.commit() }
    }

    @Synchronized
    fun recordSecond(c: Context, grant: SocialUnlockGrant) {
        val p = prefs(c)
        val key = usedKey(grant.id)
        val seconds = p.getInt(key, 0)
        if (seconds < grant.minutes * 60) p.edit().putInt(key, seconds + 1).apply()
    }

    fun history(c: Context): List<SocialUnlockEntry> {
        val p = prefs(c)
        return records(c).map { SocialUnlockEntry(it, p.getInt(usedKey(it.id), 0)) }
    }

    private fun records(c: Context): List<SocialUnlockGrant> = runCatching {
        val list = JSONArray(prefs(c).getString("history", "[]"))
        (0 until list.length()).mapNotNull { i -> list.optJSONObject(i)?.toString()?.let(::decode) }
    }.getOrDefault(emptyList())

    private fun encode(grant: SocialUnlockGrant): JSONObject = JSONObject()
        .put("id", grant.id).put("package", grant.packageName).put("name", grant.appName)
        .put("minutes", grant.minutes).put("reason", grant.reason.name)
        .put("start", grant.startedAt).put("end", grant.expiresAt)
        .put("elapsed", grant.startedElapsed).put("boot", grant.boot)

    private fun decode(text: String): SocialUnlockGrant? = runCatching {
        val item = JSONObject(text)
        SocialUnlockGrant(item.getString("id"), item.getString("package"), item.getString("name"),
            item.getInt("minutes"), SocialUnlockReason.valueOf(item.getString("reason")),
            item.getLong("start"), item.getLong("end"), item.getLong("elapsed"), item.getInt("boot"))
    }.getOrNull()
}
