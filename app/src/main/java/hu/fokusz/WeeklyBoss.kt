package hu.fokusz

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters
import java.time.temporal.WeekFields

internal enum class BossUnit(val key: String, val label: String) {
    MINUTES("minute", "perc"), SECONDS("second", "másodperc"),
    REPS("rep", "ismétlés"), GAMES("game", "parti")
}

internal data class BossActivity(val key: String, val title: String, val unit: BossUnit, val session: Int = 0)
internal data class BossGoal(val activity: BossActivity, val target: Int)
internal data class BossLine(val goal: BossGoal, val done: Int)

internal fun monday(date: LocalDate): LocalDate = date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))

internal object BossActivities {
    private val motion = listOf(
        BossActivity("pushups", "Fekvőtámasz", BossUnit.REPS),
        BossActivity("squats", "Guggolás", BossUnit.REPS),
        BossActivity("lunges", "Kitörés", BossUnit.REPS),
        BossActivity("plank", "Plank", BossUnit.SECONDS),
        BossActivity("chess", "Sakkparti", BossUnit.GAMES)
    )
    private val baseToQuest = mapOf(5 to 3, 6 to 6, 7 to 2, 8 to 15, 9 to 16, 10 to 13, 11 to 7,
        12 to 18, 13 to 19, 14 to 5, 15 to 4, 16 to 28, 17 to 29, 18 to 23, 19 to 34)
    private val timed by lazy {
        QuestStore.all.filter { it.kind == QuestKind.TIMED }
            .map { BossActivity("quest_${it.id}", it.title, BossUnit.MINUTES, it.goal / 60) }
    }

    fun all(c: Context): List<BossActivity> = motion + timed + UserQuests.all(c).map {
        BossActivity("custom_${it.id}", it.title, BossUnit.MINUTES, it.seconds / 60)
    }

    fun forQuest(quest: Quest, baseChoice: Int?): String? = when (quest.kind) {
        QuestKind.CHESS -> "chess"
        QuestKind.PUSHUPS, QuestKind.EXERCISE -> quest.mode.takeIf { it in setOf("pushups", "squats", "lunges", "plank") }
        QuestKind.TIMED -> if (baseChoice != null) baseToQuest[baseChoice]?.let { "quest_$it" }
            else "quest_${quest.id}".takeIf { quest.id > 0 }
    }
}

internal object BossProgress {
    private fun prefs(c: Context) = c.getSharedPreferences("fokusz_boss_progress_v1", Context.MODE_PRIVATE)

    @Synchronized
    fun add(c: Context, activity: String, amount: Int, receipt: String? = null,
            day: LocalDate = LocalDate.now()) {
        if (amount <= 0) return
        val p = prefs(c)
        val proof = receipt?.let { "receipt_$it" }
        if (proof != null && p.contains(proof)) return
        val key = "${day}_$activity"
        val total = (p.getInt(key, 0).toLong() + amount).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        val edit = p.edit().putInt(key, total)
        if (proof != null) edit.putBoolean(proof, true)
        if (receipt == null) edit.apply() else edit.commit()
    }

    fun count(c: Context, activity: BossActivity, week: LocalDate = monday(LocalDate.now())): Int {
        if (activity.key == "chess") return ChessVerifier.gamesSince(c, week, 7)
        val p = prefs(c)
        val total = (0L..6L).sumOf { p.getInt("${week.plusDays(it)}_${activity.key}", 0).toLong() }
        return (if (activity.unit == BossUnit.MINUTES) total / 60 else total)
            .coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }
}

internal object BossQuest {
    const val REWARD = 45 * 60
    private fun prefs(c: Context) = c.getSharedPreferences("fokusz_boss_v1", Context.MODE_PRIVATE)
    private fun weekKey(): String {
        val day = LocalDate.now()
        return "${day.get(WeekFields.ISO.weekBasedYear())}-${day.get(WeekFields.ISO.weekOfWeekBasedYear())}"
    }

    fun goals(c: Context): List<BossGoal> = runCatching {
        val list = JSONArray(prefs(c).getString("goals", "[]"))
        (0 until list.length()).map { index ->
            val row = list.getJSONObject(index)
            BossGoal(BossActivity(row.getString("key"), row.getString("title"),
                BossUnit.valueOf(row.getString("unit")), row.getInt("session")), row.getInt("target"))
        }
    }.getOrDefault(emptyList())

    fun valid(goals: List<BossGoal>): Boolean =
        goals.size == 3 && goals.map { it.activity.key }.toSet().size == 3 &&
            goals.all { it.target in 1..100_000 }

    fun save(c: Context, goals: List<BossGoal>): Boolean {
        if (!valid(goals)) return false
        val available = BossActivities.all(c).associateBy { it.key }
        if (goals.any { available[it.activity.key] != it.activity }) return false
        return prefs(c).edit().putString("goals", JSONArray(goals.map { goal ->
            JSONObject().put("key", goal.activity.key).put("title", goal.activity.title)
                .put("unit", goal.activity.unit.name).put("session", goal.activity.session).put("target", goal.target)
        }).toString()).commit()
    }

    fun claimed(c: Context): Boolean {
        val week = weekKey()
        return prefs(c).getString("claimed", "") == week || Store.hasPersonalBossReceipt(c, week)
    }

    @Synchronized
    fun claim(c: Context): Boolean {
        val goals = goals(c)
        if (claimed(c) || !valid(goals) || goals.any { BossProgress.count(c, it.activity) < it.target }) return false
        val week = weekKey()
        val received = Store.creditUserQuest(c, "personal_boss_$week", REWARD, "Személyes Boss Fight jutalma") ?: return false
        if (prefs(c).edit().putString("claimed", week).commit()) {
            ProgressStore.earned(c, received)
            ProgressStore.awardXp(c, 100, "boss_$week")
        }
        return claimed(c)
    }
}
