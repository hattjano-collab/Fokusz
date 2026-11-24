package hu.fokusz

import android.content.Context
import android.media.AudioAttributes
import android.os.Build
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import java.time.LocalDate

internal object ScreenTimeAlerts {
    private fun prefs(c: Context) = c.getSharedPreferences("fokusz_screen_time_alerts", Context.MODE_PRIVATE)

    fun warningSeconds(c: Context) = prefs(c).getInt("warningSeconds", 120)
    fun setWarningSeconds(c: Context, seconds: Int) {
        prefs(c).edit().putInt("warningSeconds", seconds.coerceAtLeast(0)).apply()
    }

    fun automaticTeamTime(c: Context) = prefs(c).getBoolean("automaticTeamTime", false)
    fun setAutomaticTeamTime(c: Context, automatic: Boolean) {
        prefs(c).edit().putBoolean("automaticTeamTime", automatic).apply()
    }

    @Suppress("DEPRECATION")
    fun vibrate(c: Context) {
        val vibrator = if (Build.VERSION.SDK_INT >= 31) {
            c.getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else c.getSystemService(Vibrator::class.java)
        runCatching {
            if (vibrator?.hasVibrator() == true) {
                val effect = VibrationEffect.createOneShot(350L, VibrationEffect.DEFAULT_AMPLITUDE)
                if (Build.VERSION.SDK_INT >= 33) {
                    vibrator.vibrate(effect, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_NOTIFICATION))
                } else {
                    val attributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
                    vibrator.vibrate(effect, attributes)
                }
            }
        }
    }
}

internal class BalanceWarning {
    private var lastDay: LocalDate? = null
    private var lastThreshold = 0
    private var warned = false

    fun shouldVibrate(date: LocalDate, remaining: Int, threshold: Int): Boolean {
        if (date != lastDay || threshold != lastThreshold || remaining > threshold) warned = false
        lastDay = date
        lastThreshold = threshold
        if (warned || remaining !in 1..threshold) return false
        warned = true
        return true
    }
}

internal class TeamTimeSession {
    companion object { const val GRANT_SECONDS = 300 }
    private var teamId = ""
    private var grantDate: LocalDate? = null
    private var seconds = 0

    fun grant(id: String, date: LocalDate) {
        teamId = id
        grantDate = date
        seconds = GRANT_SECONDS
    }

    fun remaining(id: String, date: LocalDate): Int =
        if (id.isNotBlank() && teamId == id && grantDate == date) seconds else 0

    fun spentSecond() { seconds = (seconds - 1).coerceAtLeast(0) }

    fun clear() { seconds = 0 }
}
