package hu.fokusz

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/** Kizárólag virtuális képernyőidővel játszható mini-játékok összesítője.
 * A teljes rendelkezésre álló időegyenleg feltehető; valódi pénz soha.
 */
object GambleStats {
    private fun sp(c:Context)=c.getSharedPreferences("fokusz_gambling_v3",Context.MODE_PRIVATE)
    @Synchronized private fun today(c:Context) {
        val s=sp(c)
        val now=LocalDate.now().toString()
        if(s.getString("day","")!=now) {
            s.edit().putString("day",now).putInt("staked",0)
                .putInt("won",0).putInt("lost",0).putInt("draw",0).putInt("rounds",0).commit()
        }
    }
    @Synchronized fun staked(c:Context):Int {today(c);return sp(c).getInt("staked",0)}
    @Synchronized fun canBet(c:Context,amount:Int):Boolean =
        amount > 0 && amount <= Store.bal(c)
    @Synchronized fun reserve(c:Context,amount:Int):Boolean {
        if(!canBet(c,amount))return false
        val s=sp(c)
        s.edit().putInt("staked",(s.getInt("staked",0).toLong()+amount)
            .coerceAtMost(Int.MAX_VALUE.toLong()).toInt()).commit()
        return true
    }
    /** Az eredményt a tét levonása és a kifizetés után rögzítjük. */
    @Synchronized fun record(c:Context,game:String,net:Int) {
        today(c)
        val s=sp(c)
        val key=if(net>0)"won" else if(net<0)"lost" else "draw"
        val history=try {JSONArray(s.getString("history","[]"))} catch (_:Exception){JSONArray()}
        val items=JSONArray()
        val new=JSONObject().put("game",game).put("delta",net)
            .put("when",LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm")))
            .put("day",LocalDate.now().toString())
        items.put(new)
        for(i in 0 until minOf(history.length(),11))items.put(history.getJSONObject(i))
        s.edit().putInt(key,s.getInt(key,0)+1)
            .putInt("rounds",s.getInt("rounds",0)+1)
            .putString("history",items.toString()).commit()
    }
    data class Entry(val game:String,val delta:Int,val time:String)
    fun history(c:Context):List<Entry> {
        val array=try{JSONArray(sp(c).getString("history","[]"))}catch (_:Exception){JSONArray()}
        return (0 until array.length()).mapNotNull { i->
            val o=array.optJSONObject(i)?:return@mapNotNull null
            Entry(o.optString("game"),o.optInt("delta",0),
                o.optString("day")+" "+o.optString("when"))
        }
    }
    fun todayTotals(c:Context):Triple<Int,Int,Int> {
        today(c)
        val s=sp(c)
        return Triple(s.getInt("won",0),s.getInt("lost",0),s.getInt("draw",0))
    }
}
