package hu.fokusz

import android.content.Context
import android.os.SystemClock
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.util.UUID

internal data class UserQuest(val id: String, val title: String, val seconds: Int, val reward: Int)
internal data class UserQuestRun(val id: String, val quest: UserQuest, val left: Int, val bossActivity: String?)

internal object UserQuests {
    private fun prefs(c: Context) = c.getSharedPreferences("fokusz_user_quests", Context.MODE_PRIVATE)
    private fun encode(item: UserQuest) = JSONObject().put("id", item.id).put("title", item.title)
        .put("seconds", item.seconds).put("reward", item.reward)
    private fun decode(row: JSONObject) = UserQuest(row.getString("id"), row.getString("title"),
        row.getInt("seconds"), row.getInt("reward"))

    fun all(c: Context): List<UserQuest> = runCatching {
        val rows = JSONArray(prefs(c).getString("quests", "[]"))
        (0 until rows.length()).map { decode(rows.getJSONObject(it)) }
    }.getOrDefault(emptyList())

    @Synchronized
    fun save(c: Context, id: String?, title: String, seconds: Int, reward: Int): Boolean {
        if (title.trim().length !in 3..80 || seconds !in 60..86400 || reward !in 0..seconds) return false
        val saved = all(c)
        if (id != null && saved.none { it.id == id }) return false
        val item = UserQuest(id ?: UUID.randomUUID().toString(), title.trim(), seconds, reward)
        val next = if (id == null) saved + item else saved.map { if (it.id == id) item else it }
        return prefs(c).edit().putString("quests", JSONArray(next.map(::encode)).toString()).commit()
    }

    @Synchronized
    fun remove(c: Context, id: String): Boolean =
        prefs(c).edit().putString("quests", JSONArray(all(c).filterNot { it.id == id }.map(::encode)).toString()).commit()

    fun pending(c: Context): Boolean = prefs(c).contains("run")

    fun active(c: Context): UserQuestRun? {
        val p = prefs(c)
        val saved = p.getString("run", null) ?: return null
        return runCatching {
            val quest = decode(JSONObject(saved))
            val elapsed = p.getLong("elapsed", 0)
            val left = ((quest.seconds * 1000L - elapsed).coerceAtLeast(0L) + 999L).div(1000L).toInt()
            UserQuestRun(p.getString("runId", "").orEmpty(), quest, left, p.getString("bossActivity", null))
        }.getOrNull()
    }

    fun locked(c: Context): Boolean = (active(c)?.left ?: 0) > 0
    fun subjectApps(c: Context): Set<String> = prefs(c).getStringSet("subjectApps", emptySet()).orEmpty()

    fun start(c: Context, id: String): Boolean {
        val quest = all(c).firstOrNull { it.id == id } ?: return false
        return begin(c, quest, null)
    }

    fun startBoss(c: Context, activity: BossActivity, minutes: Int): Boolean {
        if (activity.unit != BossUnit.MINUTES || minutes !in 1..1440) return false
        return begin(c, UserQuest(activity.key, activity.title, minutes * 60, 0), activity.key)
    }

    private fun begin(c: Context, quest: UserQuest, activity: String?): Boolean {
        if (FocusPrefs.focusLocked(c) || QuestStore.isTimerLocked(c)) return false
        return synchronized(this) {
            if (pending(c)) return@synchronized false
            prefs(c).edit().putString("run", encode(quest).toString())
                .putString("runId", UUID.randomUUID().toString()).putLong("elapsed", 0)
                .putLong("clock", SystemClock.elapsedRealtime()).putString("bossActivity", activity)
                .putStringSet("subjectApps", FocusPrefs.selectedSubject(c).packages).commit()
        }
    }

    @Synchronized
    fun resetClock(c: Context) {
        if (pending(c)) prefs(c).edit().putLong("clock", SystemClock.elapsedRealtime()).apply()
    }

    @Synchronized
    fun tick(c: Context) {
        val running = active(c) ?: return
        if (running.left == 0) return
        val p = prefs(c)
        val now = SystemClock.elapsedRealtime()
        val last = p.getLong("clock", now)
        val elapsed = p.getLong("elapsed", 0) + (now - last).coerceIn(0L, 1500L)
        val edit = p.edit().putLong("clock", now).putLong("elapsed", elapsed)
        if (elapsed >= running.quest.seconds * 1000L) edit.putString("completedDay", LocalDate.now().toString())
        edit.apply()
    }

    @Synchronized
    fun cancel(c: Context): Boolean {
        if ((active(c)?.left ?: 0) <= 0) return false
        return clear(c)
    }

    private fun clear(c: Context) = prefs(c).edit().remove("run").remove("runId")
        .remove("clock").remove("elapsed").remove("completedDay").remove("subjectApps").remove("bossActivity").commit()

    @Synchronized
    fun claim(c: Context): Int? {
        val run = active(c) ?: return null
        if (run.left != 0 || run.id.isBlank()) return null
        val credited = Store.creditUserQuest(c, run.id, run.quest.reward) ?: return null
        val completed = prefs(c).getString("completedDay", null)?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
        BossProgress.add(c, run.bossActivity ?: "custom_${run.quest.id}", run.quest.seconds, run.id,
            completed ?: LocalDate.now())
        ProgressStore.taskFinished(c, "OTHER", run.quest.seconds, credited, title = run.quest.title,
            receipt = "custom_${run.id}", shareWithTeam = run.bossActivity == null)
        clear(c)
        return credited
    }
}
