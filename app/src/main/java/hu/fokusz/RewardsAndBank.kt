package hu.fokusz

import android.content.Context
import java.time.LocalDate
import java.time.temporal.WeekFields
import java.util.Locale

/** Későbbi napra eltett, nem szerencsejátékhoz kötött időkeret. */
object TimeBank {
    private fun sp(c:Context)=c.getSharedPreferences("fokusz_timebank_v1",0)
    private const val MAX_TOTAL=7200
    private const val MAX_DAILY_DEPOSIT=1800

    @Synchronized private fun settle(c:Context) {
        val s=sp(c)
        val current=LocalDate.now().toString()
        val date=s.getString("pendingDay","") ?: ""
        if(date.isNotEmpty() && date!=current) {
            s.edit()
                .putInt("ready",(s.getInt("ready",0)+s.getInt("pending",0)).coerceAtMost(MAX_TOTAL))
                .putInt("pending",0).putString("pendingDay","").apply()
        }
        if(s.getString("depositDay","")!=current) {
            s.edit().putString("depositDay",current).putInt("depositedToday",0).apply()
        }
    }
    @Synchronized fun ready(c:Context):Int {settle(c);return sp(c).getInt("ready",0)}
    @Synchronized fun waiting(c:Context):Int {settle(c);return sp(c).getInt("pending",0)}
    @Synchronized fun remainingDaily(c:Context):Int {
        settle(c);return (MAX_DAILY_DEPOSIT-sp(c).getInt("depositedToday",0)).coerceAtLeast(0)
    }
    @Synchronized fun deposit(c:Context,seconds:Int):Boolean {
        settle(c)
        if(seconds !in listOf(300,900) || remainingDaily(c)<seconds
            || Store.bal(c)<seconds || ready(c)+waiting(c)+seconds>MAX_TOTAL) return false
        val s=sp(c)
        Store.add(c,-seconds)
        s.edit().putInt("pending",s.getInt("pending",0)+seconds)
            .putString("pendingDay",LocalDate.now().toString())
            .putInt("depositedToday",s.getInt("depositedToday",0)+seconds).commit()
        return true
    }
    @Synchronized fun withdraw(c:Context,seconds:Int):Boolean {
        settle(c)
        if(seconds !in listOf(300,900) || ready(c)<seconds
            || Store.bal(c)+seconds>FocusPrefs.maxBalance(c)) return false
        val s=sp(c)
        s.edit().putInt("ready",s.getInt("ready",0)-seconds).commit()
        Store.add(c,seconds)
        return true
    }
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
        val before=Store.bal(c)
        Store.add(c,900)
        ProgressStore.earned(c,(Store.bal(c)-before).coerceAtLeast(0))
        return true
    }
    fun claimed(c:Context):Boolean=sp(c).getString("claimed","")==weekKey()
}
