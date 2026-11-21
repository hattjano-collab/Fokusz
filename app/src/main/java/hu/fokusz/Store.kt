package hu.fokusz

import android.content.Context
import java.time.LocalDate
import java.util.UUID
import org.json.JSONArray

object Store {
    const val DAILY = 1800 // 30 perc másodpercben
    // Gyakorlati, egész számokkal biztonságosan kezelhető műszaki plafon (~63 év),
    // nincs napi vagy játékalkalmakra vonatkozó korlátozás.
    const val MAX_SAFE_SECONDS = 2_000_000_000
    val BLOCKED = setOf(
        "com.zhiliaoapp.musically", "com.ss.android.ugc.trill", "com.instagram.android",
        "com.google.android.youtube", "com.snapchat.android", "com.twitter.android",
        "com.facebook.katana", "com.reddit.frontpage"
    )
    // További közösségi appok, melyek az aktív quest alatt blokkoltak.
    val QUEST_SOCIAL = setOf(
        "com.discord", "com.whatsapp", "org.telegram.messenger",
        "com.facebook.orca", "com.instagram.barcelona", "com.pinterest",
        "com.linkedin.android", "com.bereal.ft", "com.tumblr",
        "xyz.blueskyweb.app", "com.viber.voip", "com.skype.raider"
    )
    // Webes közösségi oldalak miatt az egyéni quest alatt böngészők sem használhatók.
    val BROWSERS = setOf(
        "com.android.chrome", "org.mozilla.firefox", "com.brave.browser",
        "com.microsoft.emmx", "com.opera.browser", "com.opera.mini.native",
        "com.sec.android.app.sbrowser", "com.duckduckgo.mobile.android",
        "com.mi.globalbrowser", "com.UCMobile.intl", "com.kiwibrowser.browser"
    )
    private fun sp(c: Context) = c.getSharedPreferences("f", 0)

    @Synchronized fun creditChessGame(c: Context, gameId: String, seconds: Int): Int {
        val prefs = sp(c)
        val rewardKey = "chess_reward_$gameId"
        if (prefs.contains(rewardKey)) return prefs.getInt(rewardKey, 0)
        val old = bal(c)
        val updated = (old + seconds).coerceAtMost(rewardCeiling(c, old))
        val credited = updated - old
        check(prefs.edit().putInt("b", updated).putInt(rewardKey, credited).commit()) {
            "A sakkjutalmat nem sikerült menteni."
        }
        ScreenTimeLedger.record(c, "Sakkjutalom", credited)
        return credited
    }

    @Synchronized fun bal(c: Context): Int {
        val s = sp(c)
        val today = LocalDate.now().toString()
        val previous = s.getString("d", null)
        if (previous != today) {
            val old = s.getInt("b", 0)
            s.edit().putString("d", today).putInt("b", DAILY).apply()
            if (previous != null) ScreenTimeLedger.record(c, "Előző napi keret lezárása", -old)
            ScreenTimeLedger.record(c, "Napi alapkeret", DAILY)
        }
        return s.getInt("b", DAILY)
    }

    fun add(c: Context, sec: Int, reason: String = "Egyenlegváltozás"): Int {
        val (balance) = updateBalance(c, sec.toLong(), reason, capReward = true)
        if (sec > 0 || balance % 15 == 0) ProgressStore.updateWidget(c)
        return balance
    }

    fun credit(c: Context, seconds: Int, reason: String): Int {
        if (seconds <= 0) return 0
        val (_, credited) = updateBalance(c, seconds.toLong(), reason, capReward = true)
        if (credited > 0) ProgressStore.updateWidget(c)
        return credited
    }

    /** A játéknyeremény és banki kivét a felhasználó által beállított plafon fölé is kerülhet. */
    fun addUncapped(c: Context, seconds: Long, reason: String = "Egyenlegváltozás"): Int {
        val (balance) = updateBalance(c, seconds, reason, capReward = false)
        ProgressStore.updateWidget(c)
        return balance
    }

    @Synchronized private fun updateBalance(c: Context, seconds: Long, reason: String, capReward: Boolean): Pair<Int, Int> {
        val old = bal(c)
        val ceiling = if (capReward && seconds > 0) rewardCeiling(c, old) else MAX_SAFE_SECONDS
        val delta = seconds.coerceIn(-old.toLong(), (ceiling - old).toLong()).toInt()
        val balance = old + delta
        if (!saveBalance(c, balance, delta, reason)) return old to 0
        return balance to delta
    }

    private fun saveBalance(c: Context, balance: Int, delta: Int, reason: String): Boolean {
        if (delta == 0) return true
        if (!sp(c).edit().putInt("b", balance).commit()) return false
        ScreenTimeLedger.record(c, reason, delta)
        return true
    }

    fun spend(c: Context, seconds: Int, reason: String): Boolean {
        val balance = synchronized(this) {
            val old = bal(c)
            if (seconds <= 0 || seconds > old || !saveBalance(c, old - seconds, -seconds, reason)) return false
            old - seconds
        }
        if (seconds > 1 || balance % 15 == 0) ProgressStore.updateWidget(c)
        return true
    }

    fun creditUncappedExact(c: Context, seconds: Int, reason: String): Boolean {
        synchronized(this) {
            val old = bal(c)
            if (seconds <= 0 || seconds > MAX_SAFE_SECONDS - old ||
                !saveBalance(c, old + seconds, seconds, reason)) return false
        }
        ProgressStore.updateWidget(c)
        return true
    }

    private fun rewardCeiling(c: Context, balance: Int): Int =
        maxOf(balance, FocusPrefs.maxBalance(c)).coerceAtMost(MAX_SAFE_SECONDS)

    data class Goal(val id: String, val title: String, val target: Int, val perReward: Int, val saved: Int)

    @Synchronized fun goals(c: Context): List<Goal> {
        val raw = sp(c).getString("savings_goals", "[]") ?: "[]"
        val array = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()
        return (0 until array.length()).mapNotNull { index ->
            val item = array.optJSONArray(index) ?: return@mapNotNull null
            Goal(item.optString(0), item.optString(1), item.optInt(2), item.optInt(3), item.optInt(4))
        }
    }

    private fun encodeGoals(goals: List<Goal>): String {
        val array = JSONArray()
        for (goal in goals) {
            array.put(JSONArray().put(goal.id).put(goal.title).put(goal.target).put(goal.perReward).put(goal.saved))
        }
        return array.toString()
    }

    @Synchronized fun createGoal(c: Context, title: String, target: Int, perReward: Int): Boolean {
        val name = title.trim()
        if (name.isBlank() || target !in 1..MAX_SAFE_SECONDS || perReward !in 0..target) return false
        val goal = Goal(UUID.randomUUID().toString(), name, target, perReward, 0)
        return sp(c).edit().putString("savings_goals", encodeGoals(goals(c) + goal)).commit()
    }

    @Synchronized fun setGoalAllocation(c: Context, id: String, perReward: Int): Boolean {
        val all = goals(c)
        val goal = all.firstOrNull { it.id == id } ?: return false
        if (perReward !in 0..goal.target) return false
        val next = all.map { if (it.id == id) it.copy(perReward = perReward) else it }
        return sp(c).edit().putString("savings_goals", encodeGoals(next)).commit()
    }

    fun closeGoal(c: Context, id: String): Boolean {
        synchronized(this) {
            val all = goals(c)
            val goal = all.firstOrNull { it.id == id } ?: return false
            val balance = bal(c)
            if (balance.toLong() + goal.saved > MAX_SAFE_SECONDS) return false
            if (!sp(c).edit().putInt("b", balance + goal.saved)
                    .putString("savings_goals", encodeGoals(all.filter { it.id != id })).commit()) return false
            ScreenTimeLedger.record(c, "Célpersely kivétele: ${goal.title}", goal.saved)
        }
        ProgressStore.updateWidget(c)
        return true
    }

    fun addStudyReward(c: Context, seconds: Int, reason: String = "Tanulási jutalom"): Int =
        creditStudy(c, seconds, reason, null) ?: 0

    fun claimStudyReward(c: Context, seconds: Int, claimKey: String): Int? =
        creditStudy(c, seconds, "Tanulási jutalom", "study_claim_$claimKey")

    private fun creditStudy(c: Context, seconds: Int, reason: String, claimKey: String?): Int? {
        if (seconds <= 0) return 0
        val credited = synchronized(this) {
            val old = bal(c)
            val prefs = sp(c)
            val date = prefs.getString("d", null)
            if (claimKey != null && prefs.getString(claimKey, null) == date) return null
            var unallocated = seconds
            val all = goals(c)
            val next = all.map { goal ->
                val amount = minOf(unallocated, goal.perReward, (goal.target - goal.saved).coerceAtLeast(0))
                unallocated -= amount
                goal.copy(saved = goal.saved + amount)
            }
            val available = minOf(unallocated, rewardCeiling(c, old) - old)
            val editor = prefs.edit().putInt("b", old + available)
            if (next != all) editor.putString("savings_goals", encodeGoals(next))
            if (claimKey != null) editor.putString(claimKey, date)
            if (!editor.commit()) return 0
            val total = seconds - unallocated + available
            ScreenTimeLedger.record(c, reason, total)
            for ((previous, goal) in all.zip(next)) {
                ScreenTimeLedger.record(c, "Célpersely befizetése: ${goal.title}", previous.saved - goal.saved)
            }
            total
        }
        if (credited > 0) ProgressStore.updateWidget(c)
        return credited
    }
}
