package hu.fokusz

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/** Helyben tárolt motivációs adatok; sem fiók, sem szerver nem szükséges. */
object ProgressStore {
    private fun sp(c: Context) = c.getSharedPreferences("fokusz_progress_v1", Context.MODE_PRIVATE)
    private fun day(c: Context, date: LocalDate = LocalDate.now()) =
        c.getSharedPreferences("fokusz_day_" + date.toString(), Context.MODE_PRIVATE)

    data class Daily(val date: LocalDate, val usage: Int, val earned: Int, val tasks: Int, val exercises: Int, val studying: Int)
    @Synchronized fun recent(c: Context, count: Int = 7): List<Daily> =
        (count - 1 downTo 0).map { offset ->
            val date = LocalDate.now().minusDays(offset.toLong())
            val s = day(c, date)
            Daily(date, s.getInt("usage", 0), s.getInt("earned", 0), s.getInt("tasks", 0),
                s.getInt("reps", 0), s.getInt("studying", 0))
        }

    @Synchronized private fun increment(c: Context, key: String, delta: Int) {
        val s = day(c)
        s.edit().putInt(key, s.getInt(key, 0) + delta).apply()
    }

    @Synchronized fun used(c: Context, seconds: Int) {
        if (seconds > 0) increment(c, "usage", seconds)
    }

    @Synchronized fun earned(c: Context, seconds: Int) {
        if (seconds > 0) increment(c, "earned", seconds)
    }

    @Synchronized fun taskFinished(c: Context, type: String, goal: Int, reward: Int) {
        val p = sp(c)
        increment(c, "tasks", 1)
        if (type == "PUSHUPS") {
            increment(c, "reps", goal)
            p.edit().putInt("total_reps", p.getInt("total_reps", 0) + goal).apply()
        }
        if (type == "TIMED") {
            increment(c, "studying", goal)
        }
        if (type == "CHESS") p.edit().putInt("total_chess", p.getInt("total_chess", 0) + 1).apply()
        val newXP = p.getInt("xp", 0) + when (type) {
            "PUSHUPS" -> 50
            "CHESS" -> 45
            "TIMED" -> 35
            else -> 25
        }
        val today = LocalDate.now().toString()
        val last = p.getString("last_completed", "")
        val lastDate = try { LocalDate.parse(last) } catch (_: Exception) { null }
        val streak = if (last == today) p.getInt("streak", 0)
            else if (lastDate == LocalDate.now().minusDays(1)) p.getInt("streak", 0) + 1
            else 1
        p.edit().putInt("xp", newXP).putInt("streak", streak)
            .putString("last_completed", today)
            .putInt("total_tasks", p.getInt("total_tasks", 0) + 1)
            .apply()
        earned(c, reward)
        updateWidget(c)
    }

    fun xp(c: Context) = sp(c).getInt("xp", 0)
    fun level(c: Context) = 1 + xp(c) / 250
    fun streak(c: Context): Int {
        val p = sp(c)
        val last = try { LocalDate.parse(p.getString("last_completed", "")) } catch (_: Exception) { null }
        return if (last == LocalDate.now() || last == LocalDate.now().minusDays(1)) p.getInt("streak", 0) else 0
    }
    fun totalReps(c: Context) = sp(c).getInt("total_reps", 0)
    fun totalChess(c: Context) = sp(c).getInt("total_chess", 0)
    fun totalTasks(c: Context) = sp(c).getInt("total_tasks", 0)
    fun weekTasks(c: Context): Int = recent(c).sumOf { it.tasks }
    fun weekReps(c: Context): Int = recent(c).sumOf { it.exercises }
    fun weekStudy(c: Context): Int = recent(c).sumOf { it.studying }
    fun milestones(c: Context): List<Pair<String, Boolean>> = listOf(
        "🏁 Első küldetés" to (totalTasks(c) >= 1),
        "♟️ 10 sakkmeccs" to (totalChess(c) >= 10),
        "💪 100 fekvőtámasz" to (totalReps(c) >= 100),
        "🔥 7 napos sorozat" to (streak(c) >= 7),
        "📚 5 óra fókuszidő" to (recent(c, 90).sumOf { it.studying } >= 5 * 3600),
        "🎯 50 sikeres quest" to (totalTasks(c) >= 50)
    )

    @Synchronized fun weeklyBonusAvailable(c: Context): Boolean {
        val p = sp(c)
        val week = java.time.temporal.WeekFields.ISO.weekOfWeekBasedYear()
        val key = LocalDate.now().get(java.time.temporal.WeekFields.ISO.weekBasedYear()).toString() +
            "-" + LocalDate.now().get(week)
        return p.getString("weeklyClaim", "") != key && weekTasks(c) >= 8
    }
    @Synchronized fun claimWeekly(c: Context): Boolean {
        if (!weeklyBonusAvailable(c)) return false
        val p = sp(c)
        val now = LocalDate.now()
        val key = now.get(java.time.temporal.WeekFields.ISO.weekBasedYear()).toString() + "-" +
            now.get(java.time.temporal.WeekFields.ISO.weekOfWeekBasedYear())
        p.edit().putString("weeklyClaim", key).apply()
        Store.add(c, 600)
        earned(c, 600)
        return true
    }
    fun updateWidget(c: Context) {
        try {
            val manager = AppWidgetManager.getInstance(c)
            val ids = manager.getAppWidgetIds(ComponentName(c, FokuszWidget::class.java))
            if (ids.isNotEmpty()) FokuszWidget.updateAll(c, manager, ids)
        } catch (_: Exception) {}
    }
}
