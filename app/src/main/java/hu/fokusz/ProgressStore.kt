package hu.fokusz

import android.content.Context
import android.content.SharedPreferences
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeParseException

/** Helyben tárolt motivációs adatok; sem fiók, sem szerver nem szükséges. */
object ProgressStore {
    private fun sp(c: Context) = c.getSharedPreferences("fokusz_progress_v1", Context.MODE_PRIVATE)
    private fun day(c: Context, date: LocalDate = LocalDate.now()) =
        c.getSharedPreferences("fokusz_day_" + date.toString(), Context.MODE_PRIVATE)

    private const val COMPLETIONS = "completed_activities"

    data class Daily(val date: LocalDate, val usage: Int, val earned: Int, val tasks: Int, val exercises: Int, val studying: Int)
    data class Completion(val title: String, val time: LocalTime, val reward: Int)
    data class CompletionHistory(val activities: List<Completion>, val earlierCount: Int)

    private fun completionRecords(stats: SharedPreferences): JSONArray =
        try { JSONArray(stats.getString(COMPLETIONS, "[]") ?: "[]") }
        catch (_: org.json.JSONException) { JSONArray() }

    @Synchronized fun completions(c: Context, date: LocalDate): CompletionHistory {
        val stats = day(c, date)
        val records = completionRecords(stats)
        val activities = (0 until records.length()).mapNotNull { index ->
            val entry = records.optJSONObject(index) ?: return@mapNotNull null
            val title = entry.optString("title").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val time = try { LocalTime.parse(entry.optString("time")) }
            catch (_: DateTimeParseException) { return@mapNotNull null }
            Completion(title, time, entry.optInt("reward").coerceAtLeast(0))
        }
        return CompletionHistory(activities.asReversed(),
            (stats.getInt("tasks", 0) - activities.size).coerceAtLeast(0))
    }

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

    @Synchronized fun awardXp(c: Context, points: Int, receipt: String): Int {
        if (points <= 0) return 0
        val p = sp(c)
        val key = "xp_receipt_$receipt"
        val delta = (points - p.getInt(key, 0)).coerceAtLeast(0)
        if (delta == 0) return 0
        val total = (p.getInt("xp", 0).toLong() + delta).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        if (!p.edit().putInt("xp", total).putInt(key, points).commit()) return 0
        return delta
    }

    fun receiptXp(c: Context, receipt: String): Int = sp(c).getInt("xp_receipt_$receipt", 0)

    @Synchronized fun chessEarned(c: Context, date: LocalDate, gameId: String, seconds: Int) {
        awardXp(c, 15, "chess_$gameId")
        if (seconds <= 0) return
        val prefs = day(c, date)
        val key = "chess_earned_$gameId"
        if (prefs.contains(key)) return
        check(prefs.edit().putBoolean(key, true)
            .putInt("earned", prefs.getInt("earned", 0) + seconds).commit()) {
            "A sakkjutalom statisztikáját nem sikerült menteni."
        }
    }

    @Synchronized fun coachReps(c: Context, count: Int, mode: String = "pushups") {
        if(count<=0) return
        BossProgress.add(c, mode, count)
        increment(c,"reps",count)
        if(mode=="pushups") increment(c,"pushups",count)
        val p=sp(c)
        p.edit().putInt("total_reps",p.getInt("total_reps",0)+count).apply()
        val stats = day(c)
        val repetitions = stats.getInt("coach_xp_units_$mode", 0) + count
        stats.edit().putInt("coach_xp_units_$mode", repetitions).apply()
        val points = if (mode == "plank") repetitions / 5 else repetitions * 2
        awardXp(c, points.coerceAtMost(100), "coach_${LocalDate.now()}_$mode")
    }

    @Synchronized fun taskFinished(c: Context, type: String, goal: Int, reward: Int, title: String? = null,
                                   receipt: String? = null, shareWithTeam: Boolean = true) {
        val completedAt = LocalDateTime.now()
        val date = completedAt.toLocalDate()
        val stats = day(c, date)
        val p = sp(c)
        val receiptKey = receipt?.let { "task_receipt_$it" }
        if (receiptKey != null && p.contains(receiptKey)) return
        val activityTitle = title ?: when (type) {
            "STUDY" -> "Fókuszmód"
            "LANGUAGE" -> "Xeropan nyelvgyakorlás"
            else -> "Teljesített feladat"
        }
        val records = completionRecords(stats).put(JSONObject()
            .put("title", activityTitle)
            .put("time", completedAt.toLocalTime().toString())
            .put("reward", reward.coerceAtLeast(0)))
        val dailyUpdate = stats.edit().putInt("tasks", stats.getInt("tasks", 0) + 1)
            .putString(COMPLETIONS, records.toString())
        if (type == "PUSHUPS") {
            dailyUpdate.putInt("reps", stats.getInt("reps", 0) + goal)
                .putInt("pushups", stats.getInt("pushups", 0) + goal)
            p.edit().putInt("total_reps", p.getInt("total_reps", 0) + goal).apply()
        }
        if (type == "STUDY") {
            dailyUpdate.putInt("studying", stats.getInt("studying", 0) + goal)
            p.edit().putInt("total_study",p.getInt("total_study",0)+goal).apply()
        }
        if (reward > 0) dailyUpdate.putInt("earned", stats.getInt("earned", 0) + reward)
        dailyUpdate.apply()
        if (type == "CHESS") p.edit().putInt("total_chess", p.getInt("total_chess", 0) + 1).apply()
        val newXP = p.getInt("xp", 0) + when (type) {
            "PUSHUPS" -> 50
            "CHESS" -> 45
            "STUDY" -> 35
            else -> 25
        }
        val today = date.toString()
        val last = p.getString("last_completed", "")
        val lastDate = try { LocalDate.parse(last) } catch (_: Exception) { null }
        val streak = if (last == today) p.getInt("streak", 0)
            else if (lastDate == date.minusDays(1)) p.getInt("streak", 0) + 1
            else 1
        val progress = p.edit().putInt("xp", newXP).putInt("streak", streak)
            .putString("last_completed", today)
            .putInt("total_tasks", p.getInt("total_tasks", 0) + 1)
        if (receiptKey != null) progress.putBoolean(receiptKey, true)
        progress.commit()
        updateWidget(c)
        // Minden ténylegesen befejezett személyes quest egyszer +5 percet ad a csapatnak.
        if (shareWithTeam) CloudTeams.questCompleted(c)
    }

    fun xp(c: Context) = sp(c).getInt("xp", 0)
    fun level(c: Context) = 1 + xp(c) / 250
    fun streak(c: Context): Int {
        val p = sp(c)
        val last = try { LocalDate.parse(p.getString("last_completed", "")) } catch (_: Exception) { null }
        return if (last == LocalDate.now() || last == LocalDate.now().minusDays(1)) p.getInt("streak", 0) else 0
    }
    fun totalStudy(c:Context)=sp(c).getInt("total_study",0)
    fun totalReps(c: Context) = sp(c).getInt("total_reps", 0)
    fun totalChess(c: Context) = sp(c).getInt("total_chess", 0)
    fun totalTasks(c: Context) = sp(c).getInt("total_tasks", 0)
    fun weekTasks(c: Context): Int = recent(c, LocalDate.now().dayOfWeek.value).sumOf { it.tasks }
    fun weekReps(c: Context): Int = recent(c, LocalDate.now().dayOfWeek.value).sumOf { it.exercises }
    fun weekPushups(c:Context):Int = (0 until LocalDate.now().dayOfWeek.value).sumOf {
        c.getSharedPreferences("fokusz_day_"+LocalDate.now().minusDays(it.toLong()),0)
            .getInt("pushups",0)
    }
    fun weekStudy(c: Context): Int = recent(c, LocalDate.now().dayOfWeek.value).sumOf { it.studying }
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
        val credited = Store.credit(c, 600, "Heti questbónusz")
        earned(c, credited)
        awardXp(c, 75, "quest_week_$key")
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
