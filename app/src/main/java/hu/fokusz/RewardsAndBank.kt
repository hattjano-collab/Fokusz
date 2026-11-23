package hu.fokusz

import android.content.Context
import java.time.LocalDate
import java.time.temporal.WeekFields

internal data class TimeBankState(val day: LocalDate, val ready: Int, val pending: Int, val interest: Int) {
    fun addRemainder(seconds: Int): TimeBankState = copy(
        pending = minOf((Store.MAX_SAFE_SECONDS - ready).toLong(), pending + seconds.toLong()).toInt()
    )

    fun advance(target: LocalDate): TimeBankState {
        var state = this
        while (state.day < target) {
            val stock = minOf(Store.MAX_SAFE_SECONDS.toLong(), state.ready.toLong() + state.pending).toInt()
            val interest = minOf(stock / 10, Store.MAX_SAFE_SECONDS - stock)
            if (interest == 0 && state.pending == 0) return TimeBankState(target, stock, 0, 0)
            state = TimeBankState(state.day.plusDays(1), stock + interest, 0, interest)
        }
        return state
    }
}

object TimeBank {
    private const val DAY = "timebank_day"
    private const val READY = "timebank_ready"
    private const val PENDING = "timebank_pending"
    private const val INTEREST = "timebank_interest"

    private fun parseDate(value: String?): LocalDate? = value?.let { runCatching { LocalDate.parse(it) }.getOrNull() }

    internal fun read(c: Context, wallet: android.content.SharedPreferences, fallback: LocalDate): TimeBankState {
        parseDate(wallet.getString(DAY, null))?.let {
            return TimeBankState(it, wallet.getInt(READY, 0), wallet.getInt(PENDING, 0), wallet.getInt(INTEREST, 0))
        }
        val old = c.getSharedPreferences("fokusz_timebank_v1", Context.MODE_PRIVATE)
        val day = parseDate(old.getString("lastSettledDate", null))
            ?: parseDate(old.getString("pendingDay", null)) ?: fallback
        val ready = old.getInt("ready", 0).coerceIn(0, Store.MAX_SAFE_SECONDS)
        val pending = old.getInt("pending", 0).coerceIn(0, Store.MAX_SAFE_SECONDS - ready)
        return TimeBankState(day, ready, pending, old.getInt("lastInterestAwarded", 0).coerceAtLeast(0))
    }

    internal fun write(editor: android.content.SharedPreferences.Editor, state: TimeBankState) {
        editor.putString(DAY, state.day.toString()).putInt(READY, state.ready)
            .putInt(PENDING, state.pending).putInt(INTEREST, state.interest)
    }

    internal fun migrated(wallet: android.content.SharedPreferences): Boolean = wallet.contains(DAY)
    fun ready(c: Context): Int = Store.bank(c).ready
    fun waiting(c: Context): Int = Store.bank(c).pending
    fun earnedToday(c: Context): Int = Store.bank(c).interest
    private fun projection(c: Context): TimeBankState {
        val state = Store.bank(c)
        return state.addRemainder(Store.bal(c)).advance(state.day.plusDays(1))
    }
    fun projectedTomorrow(c: Context): Int = projection(c).ready
    fun projectedInterest(c: Context): Int = projection(c).interest
    fun deposit(c: Context, seconds: Int): Boolean = Store.transferBank(c, seconds, deposit = true)
    fun withdraw(c: Context, seconds: Int): Boolean = Store.transferBank(c, seconds, deposit = false)
}

/** Heti főkihívás: a megadott célokat mind teljesíteni kell. */
object BossQuest {
    private fun sp(c:Context)=c.getSharedPreferences("fokusz_boss_v1",0)
    private fun weekKey():String {
        val d=LocalDate.now()
        val week=WeekFields.ISO
        return d.get(week.weekBasedYear()).toString()+"-"+d.get(week.weekOfWeekBasedYear())
    }
    fun progress(c:Context):Triple<Int,Int,Int> =
        Triple(ProgressStore.weekPushups(c),ChessVerifier.weekGames(c),ProgressStore.weekStudy(c))
    fun ready(c:Context):Boolean {
        val (reps,chess,study)=progress(c)
        return reps>=100 && chess>=10 && study>=10800 && sp(c).getString("claimed","")!=weekKey()
    }
    @Synchronized fun claim(c:Context):Boolean {
        if(!ready(c))return false
        sp(c).edit().putString("claimed",weekKey()).commit()
        val credited = Store.credit(c, 900, "Heti főkihívás jutalma")
        ProgressStore.earned(c, credited)
        ProgressStore.awardXp(c, 100, "boss_${weekKey()}")
        return true
    }
    fun claimed(c:Context):Boolean=sp(c).getString("claimed","")==weekKey()
}
