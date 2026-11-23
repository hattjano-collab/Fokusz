package hu.fokusz

import android.content.Context
import java.time.LocalDate
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

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
    private const val WALLET_DEBITS = "backup_wallet_debits"
    private const val INSTALLATION = "backup_installation"
    private const val LAST_RESTORE = "backup_last_restore"
    private const val LAST_BACKUP_ID = "backup_last_id"
    private const val QUIZ_ATTEMPTS = "quiz_attempts_v1"
    private const val CHESS_REWARD = "chess_reward_"
    private const val STUDY_CLAIM = "study_claim_"

    internal data class BackupWallet(
        val day: LocalDate,
        val balance: Int,
        val debits: Long,
        val installation: String
    )

    internal data class BackupWalletData(
        val wallet: BackupWallet,
        val chessReceipts: Set<String>,
        val studyReceipts: Set<String>,
        val quizAttempts: JSONArray
    )

    @Synchronized internal fun walletBackup(c: Context): BackupWalletData {
        bal(c)
        val preferences = sp(c)
        val day = LocalDate.now()
        val dayKey = day.toString()
        val isCurrentDay = preferences.getString("d", null) == dayKey
        val installation = preferences.getString(INSTALLATION, null) ?: UUID.randomUUID().toString().also {
            check(preferences.edit().putString(INSTALLATION, it).commit())
        }
        val saved = preferences.all
        val wallet = BackupWallet(
            day,
            if (isCurrentDay) preferences.getInt("b", DAILY) else DAILY,
            if (isCurrentDay) preferences.getLong(WALLET_DEBITS, 0L) else 0L,
            installation
        )
        return BackupWalletData(
            wallet,
            saved.keys.filterTo(mutableSetOf()) { it.startsWith(CHESS_REWARD) },
            saved.keys.filterTo(mutableSetOf()) { it.startsWith(STUDY_CLAIM) && saved[it] == dayKey },
            JSONArray(preferences.getString(QUIZ_ATTEMPTS, "[]") ?: "[]")
        )
    }

    @Synchronized internal fun restoreWalletBackup(
        c: Context,
        backup: BackupWalletData,
        backupId: String,
        issuedAt: Long
    ): Boolean {
        val preferences = sp(c)
        require(backupId != preferences.getString(LAST_BACKUP_ID, null) &&
            issuedAt > preferences.getLong(LAST_RESTORE, 0L)) {
            "Ezt a mentést vagy egy frissebb változatát már visszaállítottad."
        }
        bal(c)
        val editor = preferences.edit().putString(LAST_BACKUP_ID, backupId).putLong(LAST_RESTORE, issuedAt)
        val localAttempts = JSONArray(preferences.getString(QUIZ_ATTEMPTS, "[]") ?: "[]")
        editor.putString(QUIZ_ATTEMPTS, mergeQuizAttempts(localAttempts, backup.quizAttempts).toString())
        // A már jutalmazott eredmény az import után sem adhat jóvá újra időt.
        for (key in backup.chessReceipts) {
            if (!preferences.contains(key)) editor.putInt(key, 0)
        }
        val today = LocalDate.now()
        val todayKey = today.toString()
        val restoreBalance = backup.wallet.day == today
        var delta = 0
        if (restoreBalance) {
            val sameDay = preferences.getString("d", null) == todayKey
            val localDebits = if (sameDay) preferences.getLong(WALLET_DEBITS, 0L) else 0L
            val sameInstallation = preferences.getString(INSTALLATION, null) == backup.wallet.installation
            val unrepresentedDebits = if (sameInstallation) {
                (localDebits - backup.wallet.debits).coerceAtLeast(0L)
            } else {
                localDebits
            }
            val newBalance = (backup.wallet.balance.toLong() - unrepresentedDebits).coerceAtLeast(0L).toInt()
            val oldBalance = if (sameDay) preferences.getInt("b", DAILY) else DAILY
            delta = newBalance - oldBalance
            editor.putString("d", todayKey).putInt("b", newBalance).putLong(WALLET_DEBITS, localDebits)
            for (key in backup.studyReceipts) editor.putString(key, todayKey)
        }
        check(editor.commit()) { "Az időkeretet és a jutalmak előzményeit nem sikerült menteni." }
        if (delta != 0) ScreenTimeLedger.record(c, "Időkeret visszaállítása mentésből", delta)
        return restoreBalance
    }

    private fun mergeQuizAttempts(local: JSONArray, imported: JSONArray): JSONArray {
        val entries = linkedMapOf<String, JSONObject>()
        for (array in listOf(local, imported)) {
            for (index in 0 until array.length()) {
                val row = array.getJSONObject(index)
                val id = row.getString("id")
                val existing = entries[id]
                if (existing == null) {
                    entries[id] = row
                } else {
                    val fingerprints = linkedSetOf<String>()
                    for (aliases in listOf(existing.optJSONArray("fingerprints"), row.optJSONArray("fingerprints"))) {
                        if (aliases == null) continue
                        for (alias in 0 until aliases.length()) fingerprints.add(aliases.getString(alias))
                    }
                    existing.put("fingerprints", JSONArray(fingerprints))
                }
            }
        }
        return JSONArray(entries.values)
    }

    @Synchronized fun creditChessGame(c: Context, gameId: String, seconds: Int): Int {
        val prefs = sp(c)
        val rewardKey = CHESS_REWARD + gameId
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

    @Synchronized internal fun creditUserQuest(c: Context, runId: String, seconds: Int): Int? {
        val p = sp(c)
        val receipt = "user_quest_reward_$runId"
        if (p.contains(receipt)) return p.getInt(receipt, 0)
        val old = bal(c)
        if (seconds !in 0..(MAX_SAFE_SECONDS - old)) return null
        if (!p.edit().putInt("b", old + seconds).putInt(receipt, seconds).commit()) return null
        ScreenTimeLedger.record(c, "Saját quest jutalma", seconds)
        return seconds
    }

    @Synchronized fun bal(c: Context): Int {
        val settings = sp(c)
        val today = LocalDate.now()
        val previous = settings.getString("d", null)?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
        if (previous != null && previous >= today) return settings.getInt("b", DAILY)
        val oldBalance = settings.getInt("b", 0)
        var bank = TimeBank.read(c, settings, previous ?: today)
        if (previous != null) {
            bank = bank.advance(previous)
            if (bank.day < today) {
                bank = bank.addRemainder(oldBalance)
            } else {
                // A korábbi bankverzió már elszámolhatta ezt a napot a napi egyenleg előtt.
                val added = minOf(oldBalance.toLong() * 11 / 10, (MAX_SAFE_SECONDS - bank.ready - bank.pending).toLong()).toInt()
                bank = bank.copy(ready = bank.ready + added)
            }
        }
        bank = bank.advance(today)
        val editor = settings.edit().putString("d", today.toString()).putInt("b", DAILY).remove(WALLET_DEBITS)
        TimeBank.write(editor, bank)
        if (!editor.commit()) return settings.getInt("b", DAILY)
        if (previous != null && oldBalance > 0) ScreenTimeLedger.record(c, "Napi maradék átvezetése a bankba", -oldBalance)
        ScreenTimeLedger.record(c, "Napi alapkeret", DAILY)
        return DAILY
    }

    @Synchronized internal fun bank(c: Context): TimeBankState {
        bal(c)
        val settings = sp(c)
        val state = TimeBank.read(c, settings, LocalDate.now())
        val rolled = state.advance(LocalDate.now())
        if (rolled != state || !TimeBank.migrated(settings)) {
            val edit = settings.edit()
            TimeBank.write(edit, rolled)
            edit.commit()
        }
        return rolled
    }

    internal fun transferBank(c: Context, seconds: Int, deposit: Boolean): Boolean {
        synchronized(this) {
            if (seconds <= 0) return false
            val balance = bal(c)
            val existing = bank(c)
            if (deposit && (seconds > balance || seconds > MAX_SAFE_SECONDS - existing.ready - existing.pending)) return false
            if (!deposit && (seconds > existing.ready || seconds > MAX_SAFE_SECONDS - balance)) return false
            val delta = if (deposit) -seconds else seconds
            val settings = sp(c)
            val editor = settings.edit().putInt("b", balance + delta)
            if (deposit) editor.putLong(WALLET_DEBITS, settings.getLong(WALLET_DEBITS, 0L) + seconds)
            TimeBank.write(editor, existing.copy(ready = existing.ready - delta))
            if (!editor.commit()) return false
            ScreenTimeLedger.record(c, if (deposit) "Befizetés az időbankba" else "Kivét az időbankból", delta)
        }
        ProgressStore.updateWidget(c)
        return true
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
        val preferences = sp(c)
        val editor = preferences.edit().putInt("b", balance)
        if (delta < 0) {
            editor.putLong(WALLET_DEBITS, preferences.getLong(WALLET_DEBITS, 0L) - delta)
        }
        if (!editor.commit()) return false
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

    internal enum class QuizWrite { SAVED, HISTORY_CHANGED, LIMIT_REACHED, STORAGE_ERROR }

    internal fun quizAttempts(c: Context): String = sp(c).getString(QUIZ_ATTEMPTS, "[]") ?: "[]"

    internal fun commitQuizAttempt(c: Context, previous: String, history: String, seconds: Int): QuizWrite {
        synchronized(this) {
            val prefs = sp(c)
            if (quizAttempts(c) != previous) return QuizWrite.HISTORY_CHANGED
            val balance = bal(c)
            if (seconds < 0 || seconds > MAX_SAFE_SECONDS - balance) return QuizWrite.LIMIT_REACHED
            val edit = prefs.edit().putString(QUIZ_ATTEMPTS, history)
            if (seconds > 0) edit.putInt("b", balance + seconds)
            if (!edit.commit()) return QuizWrite.STORAGE_ERROR
            ScreenTimeLedger.record(c, "Javított kvíz", seconds)
        }
        if (seconds > 0) ProgressStore.updateWidget(c)
        return QuizWrite.SAVED
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
        creditStudy(c, seconds, "Tanulási jutalom", STUDY_CLAIM + claimKey)

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
