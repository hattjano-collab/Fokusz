package hu.fokusz

import android.content.Context
import android.os.SystemClock
import android.provider.Settings

internal class WorkoutRest(private val context: Context, private val key: String, private val session: String) {
    private val prefs = context.getSharedPreferences("fokusz_workout", Context.MODE_PRIVATE)
    private val prepareChoices = setOf(3, 5, 10)
    private val restChoices = setOf(30, 60, 90)

    fun prepareSeconds() = prefs.getInt("prepare_seconds", 5).takeIf { it in prepareChoices } ?: 5
    fun savePrepare(seconds: Int) = prefs.edit().putInt("prepare_seconds", seconds).apply()
    fun restSeconds() = prefs.getInt("rest_seconds", 60).takeIf { it in restChoices } ?: 60
    fun saveRest(seconds: Int) = prefs.edit().putInt("rest_seconds", seconds).apply()

    fun until(): Long {
        if (prefs.getString("session_$key", null) != session || prefs.getInt("boot_$key", -1) != bootCount()) return 0L
        val deadline = prefs.getLong("until_$key", 0L)
        return deadline.takeIf { it - SystemClock.elapsedRealtime() in 1..90_000L } ?: 0L
    }

    fun start(seconds: Int): Long {
        val deadline = SystemClock.elapsedRealtime() + seconds * 1000L
        prefs.edit().putString("session_$key", session).putInt("boot_$key", bootCount())
            .putLong("until_$key", deadline).commit()
        return deadline
    }

    fun clear() { prefs.edit().remove("until_$key").apply() }

    private fun bootCount(): Int = Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, -1)
}
