package hu.fokusz

import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

internal object DuoEvidence {
    fun dailyXp(response: String, today: LocalDate, zone: ZoneId): Int {
        val raw = response.trim()
        val rows = if (raw.startsWith("[")) JSONArray(raw) else JSONObject(raw).let {
            it.optJSONArray("summaries") ?: it.optJSONArray("xpSummaries") ?: JSONArray()
        }
        val byTimestamp = mutableMapOf<String, Int>()
        for (index in 0 until rows.length()) {
            val row = rows.optJSONObject(index) ?: continue
            if (row.optBoolean("frozen", false)) continue
            val key = row.optString("date")
            if (day(key, zone) != today) continue
            val xp = maxOf(row.optInt("gainedXp", 0), row.optInt("xp", 0), 0)
            byTimestamp[key] = maxOf(byTimestamp[key] ?: 0, xp)
        }
        return byTimestamp.values.sumOf { it.toLong() }.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }

    fun day(raw: String, zone: ZoneId): LocalDate? {
        raw.toLongOrNull()?.takeIf { it > 0 }?.let { timestamp ->
            return runCatching {
                val instant = if (timestamp >= 10_000_000_000L) Instant.ofEpochMilli(timestamp)
                    else Instant.ofEpochSecond(timestamp)
                val utc = instant.atOffset(ZoneOffset.UTC)
                if (utc.toLocalTime() == java.time.LocalTime.MIDNIGHT) utc.toLocalDate()
                    else instant.atZone(zone).toLocalDate()
            }.getOrNull()
        }
        return runCatching { LocalDate.parse(raw) }.getOrElse {
            runCatching { Instant.parse(raw).atZone(zone).toLocalDate() }.getOrNull()
        }
    }

    fun verifiedStreak(profile: JSONObject, today: LocalDate, zone: ZoneId): Boolean {
        val streak = profile.optJSONObject("streakData")?.optJSONObject("currentStreak") ?: return false
        if (streak.optInt("length", 0) <= 0 || streak.optBoolean("frozen", false)) return false
        return sequenceOf("lastExtendedDate", "endDate").any { day(streak.optString(it), zone) == today }
    }
}
