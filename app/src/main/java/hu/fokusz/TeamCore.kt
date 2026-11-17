package hu.fokusz

import android.content.Context
import android.os.SystemClock
import org.json.JSONArray
import org.json.JSONObject
import java.security.SecureRandom
import java.time.LocalDate
import java.time.temporal.WeekFields
import java.util.Locale
import java.util.UUID

/** Egy eszköz = egy telepítésazonosító. Nem gyűjtünk telefonszámot vagy Google-fiókot. */
object TeamPrefs {
    private fun p(c:Context)=c.getSharedPreferences("fokusz_team_v1",Context.MODE_PRIVATE)
    @Synchronized fun deviceId(c:Context):String {
        val prefs=p(c)
        var value=prefs.getString("id","")?:""
        if(value.isBlank()) {
            value=UUID.randomUUID().toString()
            prefs.edit().putString("id",value).commit()
        }
        return value
    }
    fun linked(c:Context)=p(c).getString("role","") in listOf("host","client")
    fun host(c:Context)=p(c).getString("role","")=="host"
    fun client(c:Context)=p(c).getString("role","")=="client"
    fun name(c:Context)=p(c).getString("name","Játékos") ?: "Játékos"
    fun setName(c:Context,name:String)=p(c).edit().putString("name",name.trim().take(24)).commit()
    fun share(c:Context)=p(c).getBoolean("share",false)
    fun setShare(c:Context,share:Boolean)=p(c).edit().putBoolean("share",share).commit()
    fun hostAddress(c:Context)=p(c).getString("host","") ?: ""
    fun secret(c:Context)=p(c).getString("secret","") ?: ""
    fun groupId(c:Context)=p(c).getString("groupId","") ?: ""
    fun groupName(c:Context)=p(c).getString("groupName","Csapat") ?: "Csapat"
    fun cached(c:Context):JSONObject=try {
        JSONObject(p(c).getString("cached","{}")?:"{}")
    } catch(_:Exception) {JSONObject()}
    fun lastSync(c:Context)=p(c).getLong("lastSync",0L)
    fun online(c:Context)=linked(c) && (host(c) || System.currentTimeMillis()-lastSync(c)<30000L)
    fun lastError(c:Context)=p(c).getString("error","") ?: ""
    fun cache(c:Context,value:JSONObject) {
        p(c).edit().putString("cached",value.toString())
            .putLong("lastSync",System.currentTimeMillis()).putString("error","").commit()
    }
    fun error(c:Context,text:String) {
        p(c).edit().putString("error",text.take(170)).commit()
    }
    fun prepaid(c:Context)=p(c).getInt("prepaid",0).coerceAtLeast(0)
    @Synchronized fun consumeOne(c:Context):Boolean {
        val count=prepaid(c)
        if(count<=0)return false
        p(c).edit().putInt("prepaid",count-1).commit()
        return true
    }
    @Synchronized fun addPrepaid(c:Context,seconds:Int) {
        if(seconds<=0)return
        p(c).edit().putInt("prepaid",(prepaid(c)+seconds).coerceAtMost(60)).commit()
    }
    fun pendingPrepaid(c:Context)=prepaid(c)
    fun teamLeft(c:Context):Int {
        val s=cached(c)
        return (s.optInt("shared",0)+s.optInt("bank",0)).coerceAtLeast(0)
    }
    fun setHost(c:Context,name:String) {
        val secret=ByteArray(24).also{SecureRandom().nextBytes(it)}
        val token=android.util.Base64.encodeToString(secret,
            android.util.Base64.NO_WRAP or android.util.Base64.URL_SAFE)
                .trimEnd('=')
        val id=UUID.randomUUID().toString()
        p(c).edit().putString("role","host").putString("groupId",id)
            .putString("groupName",name.trim().take(30))
            .putString("host","").putString("secret",token)
            .putInt("prepaid",0).putString("cached","{}").commit()
    }
    fun setClient(c:Context,group:String,host:String,secret:String,label:String) {
        p(c).edit().putString("role","client")
            .putString("groupId",group).putString("host",host)
            .putString("secret",secret).putString("groupName",label.take(30))
            .putInt("prepaid",0).putString("cached","{}").commit()
    }
    fun leave(c:Context) {
        p(c).edit().remove("role").remove("host").remove("secret")
            .remove("groupId").remove("cached").putInt("prepaid",0).commit()
    }
}

/** Csak a csoportot létrehozó telefon változtathatja az időnaplót.
 * Az ügyfelek az aktuális saját statisztikájukat küldik a helyi hálózaton.
 */
object TeamLedger {
    private const val MAX_SHARED=360000
    private const val MAX_BANK=864000
    private const val PORT=45817
    const val DAILY_MEMBER=600
    const val QUEST_BONUS=300
    private fun pref(c:Context)=c.getSharedPreferences("fokusz_team_ledger",0)
    private fun weekKey():String {
        val d=LocalDate.now()
        val w=WeekFields.ISO
        return d.get(w.weekBasedYear()).toString()+"-"+d.get(w.weekOfWeekBasedYear())
    }
    @Synchronized private fun load(c:Context):JSONObject=try {
        JSONObject(pref(c).getString("state","{}")?:"{}")
    }catch (_:Exception){JSONObject()}
    @Synchronized private fun save(c:Context,s:JSONObject) {
        pref(c).edit().putString("state",s.toString()).commit()
    }
    @Synchronized fun initialize(c:Context) {
        val state=JSONObject()
            .put("groupId",TeamPrefs.groupId(c)).put("name",TeamPrefs.groupName(c))
            .put("shared",0).put("bank",0)
            .put("day",LocalDate.now().toString())
            .put("week",weekKey()).put("bossClaimedWeek","")
            .put("members",JSONArray())
        save(c,state)
        handle(c,report(c,"sync"))
    }
    private fun members(s:JSONObject)=s.optJSONArray("members")?:JSONArray().also{s.put("members",it)}
    private fun find(arr:JSONArray,id:String):JSONObject? {
        for(i in 0 until arr.length()) {
            val m=arr.optJSONObject(i)?:continue
            if(m.optString("id")==id)return m
        }
        return null
    }
    private fun settlement(s:JSONObject) {
        val now=LocalDate.now()
        val last=try{LocalDate.parse(s.optString("day"))}catch(_:Exception){now}
        val arr=members(s)
        val count=arr.length().coerceAtMost(30)
        if(last.isBefore(now)) {
            var bank=s.optInt("bank",0).coerceIn(0,MAX_BANK)
            bank=(bank+s.optInt("shared",0).coerceAtLeast(0)).coerceAtMost(MAX_BANK)
            var d=last
            var changes=0
            while(d.isBefore(now) && changes<366) {
                d=d.plusDays(1)
                bank=(bank+bank/10).coerceAtMost(MAX_BANK)
                changes++
            }
            s.put("bank",bank)
            s.put("shared",(count*DAILY_MEMBER).coerceAtMost(MAX_SHARED))
            s.put("day",now.toString())
        }
        if(s.optString("week")!=weekKey()) {
            s.put("week",weekKey())
            for(i in 0 until arr.length()) {
                val m=arr.optJSONObject(i) ?: continue
                m.put("weekPush",0).put("weekChess",0).put("weekStudy",0)
            }
        }
    }
    fun report(c:Context,op:String,seconds:Int=0,removeId:String=""):JSONObject {
        val day=ProgressStore.recent(c,1).last()
        return JSONObject().put("op",op).put("token",TeamPrefs.secret(c))
            .put("groupId",TeamPrefs.groupId(c))
            .put("id",TeamPrefs.deviceId(c))
            .put("name",TeamPrefs.name(c))
            .put("share",TeamPrefs.share(c))
            .put("balance",Store.bal(c))
            .put("used",day.usage)
            .put("tasks",ProgressStore.totalTasks(c))
            .put("weekPush",ProgressStore.weekPushups(c))
            .put("weekChess",ChessVerifier.weekGames(c))
            .put("weekStudy",ProgressStore.weekStudy(c))
            .put("seconds",seconds)
            .put("removeId",removeId)
    }
    @Synchronized fun handle(c:Context,request:JSONObject):JSONObject {
        if(!TeamPrefs.host(c))return JSONObject().put("ok",false).put("error","Nem a csoportgazda telefonja.")
        if(request.optString("token")!=TeamPrefs.secret(c) ||
            request.optString("groupId")!=TeamPrefs.groupId(c))
            return JSONObject().put("ok",false).put("error","Érvénytelen csapatmeghívó.")
        val state=load(c)
        if(state.optString("groupId")!=TeamPrefs.groupId(c))
            return JSONObject().put("ok",false).put("error","Nem található a csapat naplója.")
        settlement(state)
        val arr=members(state)
        val id=request.optString("id")
        val name=request.optString("name").trim().take(24)
        if(!id.matches(Regex("[a-f0-9-]{36}")) || name.isEmpty())
            return JSONObject().put("ok",false).put("error","Hibás tagazonosító vagy név.")
        var member=find(arr,id)
        if(member==null) {
            if(arr.length()>=30)
                return JSONObject().put("ok",false).put("error","A csapat maximum 30 tagú.")
            member=JSONObject().put("id",id).put("lastTasks",request.optInt("tasks",0).coerceAtLeast(0))
                .put("weekPush",0).put("weekChess",0).put("weekStudy",0)
            arr.put(member)
            state.put("shared",(state.optInt("shared")+DAILY_MEMBER).coerceAtMost(MAX_SHARED))
        }
        if(request.optString("op")=="remove" && id==TeamPrefs.deviceId(c)) {
            val target=request.optString("removeId")
            if(target!=id) {
                val next=JSONArray()
                for(i in 0 until arr.length()) {
                    val m=arr.optJSONObject(i)?:continue
                    if(m.optString("id")!=target)next.put(m)
                }
                state.put("members",next)
            }
        } else {
            member.put("name",name)
                .put("share",request.optBoolean("share",false))
                .put("balance",request.optInt("balance",0).coerceIn(0,21600))
                .put("used",request.optInt("used",0).coerceIn(0,86400))
                .put("seen",System.currentTimeMillis())
            val previous=member.optInt("lastTasks",0)
            val current=request.optInt("tasks",0).coerceAtLeast(0)
            // A monoton feladat-számláló megakadályozza az újra-jóváírást.
            if(current>previous) {
                val reward=(current-previous).coerceAtMost(12)*QUEST_BONUS
                state.put("shared",(state.optInt("shared")+reward).coerceAtMost(MAX_SHARED))
                member.put("lastTasks",current)
            }
            for((field,max) in listOf("weekPush" to 10000,"weekChess" to 400,"weekStudy" to 604800)) {
                member.put(field,maxOf(member.optInt(field,0),
                    request.optInt(field,0).coerceIn(0,max)))
            }
        }
        val op=request.optString("op")
        var allowed=0
        if(op=="reserve") {
            var left=request.optInt("seconds",0).coerceIn(0,30)
            val fromShared=minOf(left,state.optInt("shared",0))
            state.put("shared",state.optInt("shared")-fromShared)
            left-=fromShared
            val fromBank=minOf(left,state.optInt("bank",0))
            state.put("bank",state.optInt("bank")-fromBank)
            allowed=fromShared+fromBank
        }
        val contributors=members(state)
        val size=contributors.length().coerceAtLeast(1)
        var push=0;var chess=0;var study=0
        for(i in 0 until contributors.length()) {
            val m=contributors.optJSONObject(i)?:continue
            push+=m.optInt("weekPush");chess+=m.optInt("weekChess");study+=m.optInt("weekStudy")
        }
        val complete=push>=100*size && chess>=10*size && study>=10800*size
        if(op=="claimBoss" && complete && state.optString("bossClaimedWeek")!=weekKey()) {
            state.put("bossClaimedWeek",weekKey())
            state.put("shared",(state.optInt("shared")+900*size).coerceAtMost(MAX_SHARED))
        }
        save(c,state)
        return JSONObject().put("ok",true).put("allowed",allowed)
            .put("state",snapshot(state))
    }

    private fun snapshot(s:JSONObject):JSONObject {
        val publicMembers=JSONArray()
        val entries=members(s)
        for(i in 0 until entries.length()) {
            val m=entries.optJSONObject(i)?:continue
            val share=m.optBoolean("share",false)
            publicMembers.put(JSONObject().put("id",m.optString("id"))
                .put("name",m.optString("name"))
                .put("share",share)
                .put("balance",if(share)m.optInt("balance") else -1)
                .put("used",if(share)m.optInt("used")else -1)
                .put("seen",m.optLong("seen",0))
                .put("weekPush",m.optInt("weekPush"))
                .put("weekChess",m.optInt("weekChess"))
                .put("weekStudy",m.optInt("weekStudy")))
        }
        val n=publicMembers.length().coerceAtLeast(1)
        var ps=0;var cs=0;var ss=0
        for(i in 0 until n) {
            val m=publicMembers.optJSONObject(i)?:continue
            ps+=m.optInt("weekPush")
            cs+=m.optInt("weekChess")
            ss+=m.optInt("weekStudy")
        }
        return JSONObject()
            .put("name",s.optString("name")).put("day",s.optString("day"))
            .put("shared",s.optInt("shared")).put("bank",s.optInt("bank"))
            .put("members",publicMembers).put("weekPush",ps)
            .put("weekChess",cs).put("weekStudy",ss)
            .put("goalPush",100*n).put("goalChess",10*n)
            .put("goalStudy",10800*n)
            .put("bossClaimed",s.optString("bossClaimedWeek")==weekKey())
            .put("bossReady",ps>=100*n && cs>=10*n && ss>=10800*n)
            .put("bossReward",900*n)
    }
}