package hu.fokusz

import android.content.Context
import java.time.LocalDate
import java.time.temporal.WeekFields
import java.util.Locale

/** Személyes virtuális időbank: tetszőleges pozitív másodperc helyezhető el,
 * nincs napi befizetési vagy 120 perces banki plafon. A ma betett idő holnap
 * válik kivehetővé, napi 10% növekedéssel. A számításhoz Int-műszaki maximum
 * tartozik, nem a korábbi, felhasználóra szabott limit.
 */
object TimeBank {
    private fun sp(c:Context)=c.getSharedPreferences("fokusz_timebank_v1",0)
    const val MAX_TOTAL=Store.MAX_SAFE_SECONDS
    const val MAX_DAILY_DEPOSIT=Store.MAX_SAFE_SECONDS
    const val DAILY_PERCENT=10
    @Synchronized private fun settle(c:Context) {
        val s=sp(c)
        val today=LocalDate.now()
        val saved=s.getString("lastSettledDate","")
        if(saved==today.toString())return
        var last=try{LocalDate.parse(saved)}catch(_:Exception){
            val first=s.getString("pendingDay","")?.takeIf{it.isNotBlank()}
                ?:s.getString("depositDay","")
            try{LocalDate.parse(first)}catch(_:Exception){today}
        }
        if(last.isAfter(today))last=today
        var ready=s.getInt("ready",0).coerceIn(0,MAX_TOTAL).toLong()
        var pending=s.getInt("pending",0).coerceIn(0,MAX_TOTAL).toLong()
        var interestToday=0L
        var days=0
        while(last.isBefore(today)&&days<366){
            last=last.plusDays(1);days++
            ready=(ready+pending).coerceAtMost(MAX_TOTAL.toLong())
            pending=0L
            val gain=(ready/10L).coerceAtMost(MAX_TOTAL.toLong()-ready)
            ready+=gain
            if(last==today)interestToday=gain
        }
        if(days==0&&pending>0){
            val pendingDate=try{LocalDate.parse(s.getString("pendingDay",""))}
                catch(_:Exception){today}
            if(pendingDate.isBefore(today)) {
                ready=(ready+pending).coerceAtMost(MAX_TOTAL.toLong())
                pending=0
            }
        }
        val deposited=s.getString("depositDay","")==today.toString()
        s.edit().putInt("ready",ready.toInt())
            .putInt("pending",pending.toInt())
            .putString("pendingDay",if(pending>0)today.toString() else "")
            .putString("lastSettledDate",today.toString())
            .putInt("lastInterestAwarded",interestToday.toInt())
            .putString("depositDay",today.toString())
            .putInt("depositedToday",if(deposited)s.getInt("depositedToday",0)else 0)
            .commit()
    }
    @Synchronized fun ready(c:Context):Int{settle(c);return sp(c).getInt("ready",0)}
    @Synchronized fun waiting(c:Context):Int{settle(c);return sp(c).getInt("pending",0)}
    @Synchronized fun earnedToday(c:Context):Int{settle(c);return sp(c).getInt("lastInterestAwarded",0)}
    @Synchronized fun projectedTomorrow(c:Context):Int {
        settle(c)
        val all=(ready(c).toLong()+waiting(c)).coerceAtMost(MAX_TOTAL.toLong())
        return (all+all/10).coerceAtMost(MAX_TOTAL.toLong()).toInt()
    }
    @Synchronized fun projectedInterest(c:Context):Int {
        settle(c)
        return (projectedTomorrow(c).toLong()-ready(c)-waiting(c))
            .coerceAtLeast(0).toInt()
    }
    @Synchronized fun remainingDaily(c:Context):Int = MAX_DAILY_DEPOSIT
    @Synchronized fun deposit(c:Context,seconds:Int):Boolean {
        settle(c)
        if(seconds<=0 || Store.bal(c)<seconds ||
            ready(c).toLong()+waiting(c).toLong()+seconds>MAX_TOTAL.toLong())return false
        val s=sp(c)
        val debited=Store.bal(c)-Store.addUncapped(c,-seconds.toLong())
        if(debited!=seconds)return false
        s.edit().putInt("pending",(waiting(c).toLong()+seconds).toInt())
            .putString("pendingDay",LocalDate.now().toString())
            .putInt("depositedToday",
                (s.getInt("depositedToday",0).toLong()+seconds)
                    .coerceAtMost(MAX_TOTAL.toLong()).toInt()).commit()
        return true
    }
    @Synchronized fun withdraw(c:Context,seconds:Int):Boolean {
        settle(c)
        if(seconds<=0 || ready(c)<seconds ||
            Store.bal(c).toLong()+seconds>Store.MAX_SAFE_SECONDS.toLong())return false
        val s=sp(c)
        s.edit().putInt("ready",ready(c)-seconds).commit()
        Store.addUncapped(c,seconds.toLong())
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
