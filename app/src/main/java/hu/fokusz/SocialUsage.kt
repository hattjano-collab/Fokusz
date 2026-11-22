package hu.fokusz

import android.content.Context
import android.os.SystemClock
import android.provider.Settings
import java.time.LocalDate

internal object SocialUsage {
    private const val SESSION_SECONDS = 600
    private const val BREAK_MILLIS = 20_000L

    private fun prefs(context: Context) =
        context.getSharedPreferences("fokusz_social_use_v1", Context.MODE_PRIVATE)

    fun appKey(packageName: String) = when (packageName) {
        "com.ss.android.ugc.trill" -> "com.zhiliaoapp.musically"
        else -> packageName
    }

    private fun bootCount(context: Context) =
        Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, -1)

    fun dailyLimitMinutes(context: Context, packageName: String): Int =
        prefs(context).getInt("limit_${appKey(packageName)}", 0)

    fun setDailyLimit(context: Context, packageName: String, minutes: Int) {
        prefs(context).edit().putInt("limit_${appKey(packageName)}", minutes.coerceIn(0, 1440)).apply()
    }

    fun dailySeconds(context: Context, packageName: String): Int =
        prefs(context).getInt("used_${appKey(packageName)}_${LocalDate.now()}", 0)

    fun dailyLimitReached(context: Context, packageName: String): Boolean {
        val limit = dailyLimitMinutes(context, packageName)
        return limit > 0 && dailySeconds(context, packageName) >= limit * 60
    }

    fun breakSecondsLeft(context: Context): Int {
        val preferences = prefs(context)
        val deadline = preferences.getLong("break_deadline", 0L)
        if (deadline == 0L || preferences.getInt("break_boot", -1) != bootCount(context)) return 0
        val remaining = deadline - SystemClock.elapsedRealtime()
        return if (remaining in 1..BREAK_MILLIS) ((remaining + 999L) / 1000L).toInt() else 0
    }

    fun recordSecond(context: Context, packageName: String, advanceBreak: Boolean): Int {
        val preferences = prefs(context)
        val date = LocalDate.now().toString()
        val lastDate = preferences.getString("used_date", null)
        val dailyKey = "used_${appKey(packageName)}_$date"
        val edit = preferences.edit()
        if (lastDate != date) {
            if (lastDate != null) {
                preferences.all.keys.filter { it.startsWith("used_") && it.endsWith("_$lastDate") }
                    .forEach { edit.remove(it) }
            }
            edit.putString("used_date", date)
        }
        edit.putInt(dailyKey, preferences.getInt(dailyKey, 0) + 1)
        val sessionSeconds = preferences.getInt("session_seconds", 0) + if (advanceBreak) 1 else 0
        if (sessionSeconds < SESSION_SECONDS) {
            edit.putInt("session_seconds", sessionSeconds).apply()
            return 0
        }
        edit.putInt("session_seconds", 0)
            .putLong("break_deadline", SystemClock.elapsedRealtime() + BREAK_MILLIS)
            .putInt("break_boot", bootCount(context))
            .apply()
        return (BREAK_MILLIS / 1000L).toInt()
    }
}
