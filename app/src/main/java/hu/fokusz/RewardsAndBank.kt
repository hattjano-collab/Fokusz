package hu.fokusz

import android.content.Context
import java.time.LocalDate
import java.time.temporal.WeekFields
import java.util.Locale

/** Virtuális időbank: naponta egyszer 10% bónusz, legfeljebb 120 percig.
 * A ma berakott pénz csak a következő naptári napon elérhető.
 * A felhalmozás az utolsó elszámolási naptól a mai napig felzárkózik. */
object TimeBank {
    private fun sp(c:Context)=c.getSharedPreferences("fokusz_timebank_v1",0)
    const val MAX_TOTAL=7200
    const val MAX_DAILY_DEPOSIT=1800
    const val DAILY_PERCENT=10

    @Synchronized private fun settle(c:Context) {
        val s=sp(c)
        val today=LocalDate.now()
        val old=s.getString("lastSettledDate","")
        var last=try { LocalDate.parse(old) } catch(_:Exception) {
            val saved= s.getString("pendingDay","")
                ?.takeIf{it.isNotBlank()} ?: s.getString("depositDay","")
            try{ LocalDate.parse(saved)}catch(_:Exception){today}
        }
        if(last.isAfter(today))last=today
        var ready=s.getInt("ready",0).coerceIn(0,MAX_TOTAL)
        var pending=s.getInt("pending",0).coerceIn(0,MAX_TOTAL-ready)
        var interestToday=if(old==today.toString())s.getInt("lastInterestAwarded",0)else 0
        var days=0
        while(last.isBefore(today) && days < 366) {
            last=last.plusDays(1);days++
            ready=(ready+pending).coerceAtMost(MAX_TOTAL)
            pending=0
            val earned= (ready/10).coerceAtMost(MAX_TOTAL-ready)
            ready+=earned
            if(last==today)interestToday=earned
        }
        // A korábbi verziók függő befizetését is rendezzük.
        if(days==0 && pending>0) {
            val pendingDate=try{LocalDate.parse(s.getString("pendingDay",""))}
                catch(_:Exception){today}
            if(pendingDate.isBefore(today)) {
                ready=(ready+pending).coerceAtMost(MAX_TOTAL)
                pending=0
            }
        }
        val depositDay=s.getString("depositDay","")
        s.edit().putInt("ready",ready).putInt("pending",pending)
            .putString("pendingDay",if(pending>0)today.toString()else "")
            .putString("lastSettledDate",today.toString())
            .putInt("lastInterestAwarded",interestToday)
            .putString("depositDay",today.toString())
            .putInt("depositedToday",if(depositDay==today.toString())
                s.getInt("depositedToday",0) else 0).commit()
    }
    @Synchronized fun ready(c:Context):Int {settle(c);return sp(c).getInt("ready",0)}
    @Synchronized fun waiting(c:Context):Int {settle(c);return sp(c).getInt("pending",0)}
    @Synchronized fun earnedToday(c:Context):Int {settle(c);return sp(c).getInt("lastInterestAwarded",0)}
    @Synchronized fun projectedTomorrow(c:Context):Int {
        settle(c)
        val value=(ready(c)+waiting(c)).coerceAtMost(MAX_TOTAL)
        return (value+minOf(value/10,MAX_TOTAL-value)).coerceAtMost(MAX_TOTAL)
    }
    @Synchronized fun projectedInterest(c:Context):Int {
        settle(c)
        return projectedTomorrow(c)-ready(c)-waiting(c)
    }
    @Synchronized fun remainingDaily(c:Context):Int {
        settle(c)
        return (MAX_DAILY_DEPOSIT-sp(c).getInt("depositedToday",0)).coerceAtLeast(0)
    }
    @Synchronized fun deposit(c:Context,seconds:Int):Boolean {
        settle(c)
        if(seconds !in listOf(300,900) || remainingDaily(c)<seconds ||
            Store.bal(c)<seconds || ready(c)+waiting(c)+seconds>MAX_TOTAL)return false
        val s=sp(c)
        Store.add(c,-seconds)
        s.edit().putInt("pending",s.getInt("pending",0)+seconds)
            .putString("pendingDay",LocalDate.now().toString())
            .putInt("depositedToday",s.getInt("depositedToday",0)+seconds).commit()
        return true
    }
    @Synchronized fun withdraw(c:Context,seconds:Int):Boolean {
        settle(c)
        if(seconds !in listOf(300,900) || ready(c)<seconds ||
            Store.bal(c)+seconds>FocusPrefs.maxBalance(c))return false
        sp(c).edit().putInt("ready",sp(c).getInt("ready",0)-seconds).commit()
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
