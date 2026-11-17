package hu.fokusz

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.security.SecureRandom
import java.time.LocalDate
import java.time.temporal.WeekFields
import java.util.UUID

data class TeamMember(
    val id:String,val name:String,val balance:Int,val dailyUsed:Int,
    val pushups:Int,val chess:Int,val study:Int,val updated:String,
    val share:Boolean
)

/**
 * Csapat-állapot. Csak a csoportot létrehozó telefon vezeti a közös főkönyvet.
 * A többi telefon közeli kapcsolatban szinkronizál. Szerver és regisztráció nélkül.
 */
object TeamStore {
    private const val GROUP_BANK_MAX=86400 // 24 óra
    private const val GROUP_POOL_MAX=86400
    private const val MEMBER_DAILY=600     // +10 perc / tag / nap
    const val QUEST_BONUS=300            // +5 perc / saját befejezett quest
    private fun p(c:Context)=c.getSharedPreferences("fokusz_teams_v1",0)
    fun active(c:Context)=p(c).getString("teamId","")?.isNotEmpty()==true
    fun host(c:Context)=p(c).getBoolean("host",false)
    fun teamId(c:Context)=p(c).getString("teamId","") ?: ""
    fun secret(c:Context)=p(c).getString("secret","") ?: ""
    fun name(c:Context)=p(c).getString("teamName","Fókusz csapat") ?: "Fókusz csapat"
    fun hostId(c:Context)=p(c).getString("hostId","") ?: ""
    fun memberId(c:Context):String {
        val store=p(c)
        var value=store.getString("deviceId","") ?: ""
        if(value.isEmpty()) {
            value=UUID.randomUUID().toString()
            store.edit().putString("deviceId",value).commit()
        }
        return value
    }
    fun nickname(c:Context)=p(c).getString("nickname","Tag") ?: "Tag"
    fun sharing(c:Context)=p(c).getBoolean("sharing",false)
    fun setNickname(c:Context,newName:String) {
        val n=newName.trim().take(24)
        if(n.isNotEmpty()) p(c).edit().putString("nickname",n).commit()
    }
    fun setSharing(c:Context,shared:Boolean) {
        p(c).edit().putBoolean("sharing",shared).commit()
    }
    private fun randomSecret():String {
        val b=ByteArray(24); SecureRandom().nextBytes(b)
        return android.util.Base64.encodeToString(b,
            android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP or android.util.Base64.NO_PADDING)
    }
    private fun roster(c:Context):JSONArray=try {
        JSONArray(p(c).getString("members","[]"))
    }catch(_:Exception){JSONArray()}
    private fun mine(c:Context):JSONObject {
        val member=JSONObject()
        member.put("id",memberId(c)).put("name",nickname(c))
        member.put("share",sharing(c))
        if(sharing(c)) {
            member.put("balance",Store.bal(c))
            member.put("usage",ProgressStore.recent(c,1).firstOrNull()?.usage ?: 0)
        }
        member.put("pushups",ProgressStore.weekPushups(c))
        member.put("chess",ChessVerifier.weekGames(c))
        member.put("study",ProgressStore.weekStudy(c))
        member.put("updated",LocalDate.now().toString())
        member.put("week",weekKey())
        return member
    }
    @Synchronized fun create(c:Context,title:String):Boolean {
        if(active(c) || title.trim().length !in 3..40)return false
        val id=UUID.randomUUID().toString()
        val user=memberId(c)
        val s=p(c)
        s.edit().putString("teamId",id).putString("secret",randomSecret())
            .putString("teamName",title.trim()).putBoolean("host",true)
            .putString("hostId",user).putString("ledgerDay",LocalDate.now().toString())
            .putInt("pool",MEMBER_DAILY).putInt("bank",0).putInt("bankInterest",0)
            .putString("members",JSONArray().put(mine(c)).toString())
            .putString("seenEvents","[]").putString("pendingEvents","[]")
            .putInt("lease",0).putString("bossClaimed","").commit()
        return true
    }
    @Synchronized fun join(c:Context,raw:String):Boolean {
        if(active(c) || !raw.startsWith("fokusz-team:v1:"))return false
        try {
            val src=String(android.util.Base64.decode(
                raw.removePrefix("fokusz-team:v1:"),
                android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP),
                Charsets.UTF_8)
            val o=JSONObject(src)
            val id=o.getString("id")
            val sec=o.getString("key")
            val host=o.getString("host")
            val team=o.getString("name")
            if(id.length !in 10..80 || sec.length !in 20..100 ||
                host.length !in 10..80 || team.length !in 3..40)return false
            memberId(c)
            p(c).edit().putString("teamId",id).putString("secret",sec)
                .putString("hostId",host).putString("teamName",team)
                .putBoolean("host",false)
                .putString("members","[]")
                .putInt("pool",0).putInt("bank",0).putInt("lease",0)
                .putString("pendingEvents","[]").commit()
            return true
        }catch(_:Exception){return false}
    }
    fun qr(c:Context):String {
        if(!host(c))return ""
        val o=JSONObject().put("id",teamId(c)).put("key",secret(c))
            .put("host",hostId(c)).put("name",name(c))
        val text=android.util.Base64.encodeToString(o.toString().toByteArray(),
            android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP or
                android.util.Base64.NO_PADDING)
        return "fokusz-team:v1:"+text
    }
    @Synchronized fun leave(c:Context) {
        val s=p(c)
        s.edit().remove("teamId").remove("secret").remove("host")
            .remove("hostId").remove("teamName").remove("pool").remove("bank")
            .remove("members").remove("pendingEvents").remove("lease").commit()
    }
    private fun date():String=LocalDate.now().toString()
    private fun knownMembers(c:Context):List<JSONObject> {
        val arr=roster(c);return (0 until arr.length()).mapNotNull{arr.optJSONObject(it)}
    }
    @Synchronized fun members(c:Context):List<TeamMember> {
        if(host(c))refreshHostMember(c)
        return knownMembers(c).map {
            TeamMember(it.optString("id"),it.optString("name","Tag"),
                it.optInt("balance",-1),it.optInt("usage",-1),
                if(it.optString("week")==weekKey())it.optInt("pushups",0)else 0,
                if(it.optString("week")==weekKey())it.optInt("chess",0)else 0,
                if(it.optString("week")==weekKey())it.optInt("study",0)else 0,
                it.optString("updated",""),it.optBoolean("share",false))
        }
    }
    @Synchronized private fun refreshHostMember(c:Context) {
        if(!active(c)||!host(c))return
        val list=roster(c)
        val own=memberId(c)
        val updated=JSONArray()
        var found=false
        for(i in 0 until list.length()) {
            val entry=list.optJSONObject(i)?:continue
            if(entry.optString("id")==own){updated.put(mine(c));found=true}
            else updated.put(entry)
        }
        if(!found)updated.put(mine(c))
        val updatedString=updated.toString()
        if(updatedString!=p(c).getString("members","[]"))
            p(c).edit().putString("members",updatedString).apply()
    }
    @Synchronized fun settle(c:Context) {
        if(!active(c)||!host(c))return
        val s=p(c)
        val now=LocalDate.now()
        val previous=try{LocalDate.parse(s.getString("ledgerDay",""))}catch(_:Exception){now}
        if(previous>=now)return
        val n=knownMembers(c).size.coerceAtLeast(1)
        var bank=s.getInt("bank",0).coerceIn(0,GROUP_BANK_MAX)
        var pool=s.getInt("pool",0).coerceIn(0,GROUP_POOL_MAX)
        var day=previous
        var todayInterest=0
        var iterations=0
        while(day.isBefore(now)&&iterations<366) {
            day=day.plusDays(1);iterations++
            bank=(bank+pool).coerceAtMost(GROUP_BANK_MAX)
            pool=0
            val interest=minOf(bank/10,GROUP_BANK_MAX-bank)
            bank+=interest
            if(day==now)todayInterest=interest
            pool=(n*MEMBER_DAILY).coerceAtMost(GROUP_POOL_MAX)
        }
        s.edit().putInt("pool",pool).putInt("bank",bank)
            .putInt("bankInterest",todayInterest).putString("ledgerDay",now.toString())
            .commit()
    }
    @Synchronized fun pool(c:Context):Int {settle(c);return p(c).getInt("pool",0)}
    @Synchronized fun bank(c:Context):Int {settle(c);return p(c).getInt("bank",0)}
    fun interest(c:Context)=p(c).getInt("bankInterest",0)
    fun leased(c:Context)=p(c).getInt("lease",0)
    fun available(c:Context):Int=pool(c)+bank(c)
    @Synchronized private fun deduct(c:Context,seconds:Int):Boolean {
        settle(c)
        if(seconds<=0||available(c)<seconds)return false
        val s=p(c);val fromPool=minOf(pool(c),seconds)
        s.edit().putInt("pool",pool(c)-fromPool)
            .putInt("bank",bank(c)-(seconds-fromPool)).commit()
        return true
    }
    /** Csak a gazda módosíthatja a közös főkönyvet. */
    @Synchronized fun take(c:Context,seconds:Int):Boolean =
        host(c)&&deduct(c,seconds)
    @Synchronized fun grant(c:Context,requested:Int):Int {
        if(!host(c))return 0
        val n=minOf(requested.coerceIn(1,120),available(c))
        return if(n>0&&deduct(c,n))n else 0
    }
    /** A tag előre lefoglalt 60-120 mp-ét offline is felhasználhatja,
     * de újabb keretet csak a gazdától kérhet. */
    @Synchronized fun spendTeamSecond(c:Context):Boolean {
        if(!active(c))return false
        if(host(c))return deduct(c,1)
        val s=p(c);val lease=s.getInt("lease",0)
        if(lease<=0)return false
        s.edit().putInt("lease",lease-1).commit()
        return true
    }
    @Synchronized fun acceptLease(c:Context,n:Int) {
        if(host(c)||n<=0)return
        p(c).edit().putInt("lease",(leased(c)+n).coerceAtMost(180)).commit()
    }
    @Synchronized fun addMember(c:Context,raw:JSONObject):Boolean {
        if(!host(c))return false
        settle(c)
        val id=raw.optString("id")
        if(id.length !in 10..80)return false
        val existing=knownMembers(c)
        val update=JSONArray();var found=false
        for(e in existing) {
            if(e.optString("id")==id) {update.put(raw);found=true}
            else update.put(e)
        }
        if(!found) {
            if(existing.size>=20)return false
            update.put(raw)
        }
        val editor=p(c).edit().putString("members",update.toString())
        if(!found)editor.putInt("pool",(pool(c)+MEMBER_DAILY)
            .coerceAtMost(GROUP_POOL_MAX))
        editor.commit()
        return true
    }
    @Synchronized fun removeMember(c:Context,id:String):Boolean {
        if(!host(c) || id==hostId(c))return false
        val list=knownMembers(c)
        if(list.none{it.optString("id")==id})return false
        val updated=JSONArray()
        list.filter{it.optString("id")!=id}.forEach{updated.put(it)}
        p(c).edit().putString("members",updated.toString()).commit()
        return true
    }
    @Synchronized fun addBonus(c:Context,requestId:String):Boolean {
        if(!host(c) || requestId.length !in 8..160)return false
        settle(c)
        val s=p(c)
        val arr=try{JSONArray(s.getString("seenEvents","[]"))}catch(_:Exception){JSONArray()}
        for(i in 0 until arr.length())if(arr.optString(i)==requestId)return false
        arr.put(requestId)
        val recent=JSONArray()
        val start=(arr.length()-600).coerceAtLeast(0)
        for(i in start until arr.length())recent.put(arr.optString(i))
        s.edit().putString("seenEvents",recent.toString())
            .putInt("pool",(pool(c)+QUEST_BONUS).coerceAtMost(GROUP_POOL_MAX)).commit()
        return true
    }
    /** Eseményeknek egyedi azonosítója van, így a két telefon nem tudja
     * ugyanazt a questet minden újracsatlakozáskor újra jóváírni. */
    @Synchronized fun questCompleted(c:Context) {
        if(!active(c))return
        val day=date()
        val s=p(c)
        val seq=s.getInt("questSeq",0)+1
        val id=memberId(c)+":"+day+":"+seq
        s.edit().putInt("questSeq",seq).commit()
        if(host(c)) {addBonus(c,id);TeamNearby.broadcast(c)}
        else {
            val pending=try{JSONArray(s.getString("pendingEvents","[]"))}catch(_:Exception){JSONArray()}
            pending.put(id)
            s.edit().putString("pendingEvents",pending.toString()).commit()
            TeamNearby.sendPending(c)
        }
    }
    fun pending(c:Context):List<String> {
        val a=try{JSONArray(p(c).getString("pendingEvents","[]"))}catch(_:Exception){JSONArray()}
        return (0 until a.length()).map{a.optString(it)}.filter{it.isNotEmpty()}
    }
    @Synchronized fun ack(c:Context,id:String) {
        val list=JSONArray()
        for(value in pending(c))if(value!=id)list.put(value)
        p(c).edit().putString("pendingEvents",list.toString()).commit()
    }
    fun report(c:Context):JSONObject=mine(c)
    fun weekBossProgress(c:Context):Triple<Int,Int,Int> {
        val m=members(c)
        return Triple(m.sumOf{it.pushups},m.sumOf{it.chess},m.sumOf{it.study})
    }
    fun bossGoal(c:Context):Triple<Int,Int,Int> {
        val n=members(c).size.coerceAtLeast(1)
        return Triple(100*n,10*n,10800*n)
    }
    private fun weekKey():String {
        val week=WeekFields.ISO
        val today=LocalDate.now()
        return today.get(week.weekBasedYear()).toString()+"-"+today.get(week.weekOfWeekBasedYear())
    }
    fun bossClaimed(c:Context):Boolean=p(c).getString("bossClaimed","")==weekKey()
    @Synchronized fun claimBoss(c:Context):Boolean {
        if(!host(c)||bossClaimed(c))return false
        val (a,b,d)=weekBossProgress(c)
        val (ga,gb,gd)=bossGoal(c)
        if(a<ga||b<gb||d<gd)return false
        val s=p(c)
        s.edit().putString("bossClaimed",weekKey())
            .putInt("pool",(pool(c)+900*members(c).size).coerceAtMost(GROUP_POOL_MAX))
            .commit()
        TeamNearby.broadcast(c)
        return true
    }
    @Synchronized fun snapshot(c:Context):JSONObject {
        settle(c);refreshHostMember(c)
        return JSONObject().put("type","snapshot").put("team",teamId(c))
            .put("name",name(c)).put("pool",pool(c)).put("bank",bank(c))
            .put("interest",interest(c)).put("day",date())
            .put("bossClaimed",p(c).getString("bossClaimed",""))
            .put("members",roster(c))
    }
    @Synchronized fun acceptSnapshot(c:Context,payload:JSONObject) {
        if(host(c)||payload.optString("team")!=teamId(c))return
        val roster=payload.optJSONArray("members")?:return
        p(c).edit().putInt("pool",payload.optInt("pool",0).coerceAtLeast(0))
            .putInt("bank",payload.optInt("bank",0).coerceAtLeast(0))
            .putInt("bankInterest",payload.optInt("interest",0).coerceAtLeast(0))
            .putString("teamName",payload.optString("name",name(c)))
            .putString("bossClaimed",payload.optString("bossClaimed",""))
            .putString("members",roster.toString()).commit()
    }
}
