package hu.fokusz

import android.content.Context
import java.time.LocalDate
import org.json.JSONArray

internal object ScreenTimeLedger {
    data class Entry(val at: Long, val reason: String, val seconds: Long)

    private const val HISTORY_DAYS = 30L
    private const val DAILY_ENTRIES = 200
    private const val PREFIX = "fokusz_time_statement_"
    private fun day(c: Context, date: LocalDate) =
        c.getSharedPreferences(PREFIX + date.toString(), Context.MODE_PRIVATE)

    fun socialReason(packageName: String): String {
        val known = FocusPrefs.socialChoices.firstOrNull { it.second == packageName }?.first
        return when (packageName) {
            "com.ss.android.ugc.trill" -> "TikTok"
            else -> known ?: packageName
        } + " használata"
    }

    @Synchronized fun record(c: Context, reason: String, seconds: Int) {
        if (seconds == 0) return
        val now = System.currentTimeMillis()
        val date = LocalDate.now()
        val p = day(c, date)
        val entries = read(p.getString("rows", null))
        val lastIndex = entries.length() - 1
        val last = entries.optJSONArray(lastIndex)
        val lastSeconds = last?.optLong(2) ?: 0L
        if (last != null && last.optString(1) == reason &&
            (lastSeconds > 0) == (seconds > 0) && now - last.optLong(0) in 0L..3000L
        ) {
            entries.put(lastIndex, JSONArray().put(now).put(reason).put(lastSeconds + seconds))
        } else {
            if (entries.length() >= DAILY_ENTRIES) entries.remove(0)
            entries.put(JSONArray().put(now).put(reason).put(seconds))
        }
        p.edit().putString("rows", entries.toString()).apply()
    }

    @Synchronized fun recent(c: Context, limit: Int): List<Entry> {
        val date = LocalDate.now()
        return (0L until HISTORY_DAYS).asSequence()
            .map { read(day(c, date.minusDays(it)).getString("rows", null)) }
            .flatMap { items ->
                (items.length() - 1 downTo 0).asSequence().mapNotNull { index ->
                    val item = items.optJSONArray(index) ?: return@mapNotNull null
                    Entry(item.optLong(0), item.optString(1), item.optLong(2))
                }
            }
            .take(limit)
            .toList()
    }

    private fun read(raw: String?): JSONArray =
        raw?.let { runCatching { JSONArray(it) }.getOrNull() } ?: JSONArray()
}
