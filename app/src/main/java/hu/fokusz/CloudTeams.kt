package hu.fokusz

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.Timestamp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.security.SecureRandom
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.Date
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

data class CloudMember(
    val uid:String,val name:String,val share:Boolean,val personalLeft:Int,
    val used:Int,val push:Int,val chess:Int,val study:Int,val updatedAt:Date?
)
data class CloudTeamView(
    val id:String="",val name:String="",val pool:Int=0,val bank:Int=0,
    val members:List<CloudMember> = emptyList(),
    val push:Int=0,val chess:Int=0,val study:Int=0,
    val targetPush:Int=100,val targetChess:Int=10,val targetStudy:Int=10800,
    val bossClaimed:Date?=null,val syncedAt:Long=0L,val cached:Boolean=true
)

/** Internetes csapatok: Firebase Anonymous Auth + Cloud Firestore, Spark csomagon.
 * A közös egyenleget kizárólag Firestore-tranzakcióval módosítjuk.
 * A felvett 1 perces keret már a felvételkor levonódik a közös egyenlegből.
 * Nincs szükség gazdatelefonra vagy azonos Wi-Fi-re.
 */
object CloudTeams {
    private const val MAX_POOL=86400
    private const val MAX_BANK=86400
    private val io=CoroutineScope(SupervisorJob()+Dispatchers.IO)
    private val starting=AtomicBoolean(false)
    private val leasing=AtomicBoolean(false)
    private var listener:ListenerRegistration?=null
    @Volatile private var listenerId=""
    @Volatile private var lastPulse=0L
    @Volatile private var lastOwn=0L
    @Volatile private var lastSettle=0L
    @Volatile private var lastReward=0L
    var view by mutableStateOf(CloudTeamView())
        private set
    var status by mutableStateOf("Firebase-kapcsolat indítása…")
        private set
    private fun prefs(c:Context):SharedPreferences =
        c.getSharedPreferences("fokusz_cloud_team_v1",Context.MODE_PRIVATE)
    private fun auth()=FirebaseAuth.getInstance()
    private fun db()=FirebaseFirestore.getInstance()
    fun active(c:Context)=prefs(c).getString("teamId","")?.isNotBlank()==true
    fun teamId(c:Context)=prefs(c).getString("teamId","")?:""
    fun owner(c:Context)=prefs(c).getBoolean("owner",false)
    fun teamName(c:Context)=prefs(c).getString("teamName","Csapat")?:"Csapat"
    fun nickname(c:Context)=prefs(c).getString("nick","Tag")?:"Tag"
    fun sharing(c:Context)=prefs(c).getBoolean("share",false)
    fun setNickname(c:Context,n:String)=prefs(c).edit().putString("nick",n.trim().take(24)).apply()
    fun setSharing(c:Context,yes:Boolean){
        prefs(c).edit().putBoolean("share",yes).apply()
        lastOwn=0
        if(active(c))io.launch{syncProfile(c.applicationContext)}
    }
    fun currentUid()=auth().currentUser?.uid ?: ""
    fun leased(c:Context)=prefs(c).getInt("lease",0).coerceAtLeast(0)
    private suspend fun ensureAuth():String {
        val existing=auth().currentUser
        if(existing!=null)return existing.uid
        return auth().signInAnonymously().await().user?.uid
            ?: throw IllegalStateException("Nem sikerült az anonim Firebase-bejelentkezés.")
    }
    private fun teamRef(c:Context)=db().collection("teams").document(teamId(c))
    private fun todayUtc():Timestamp {
        val start=LocalDate.now(ZoneOffset.UTC).atStartOfDay(ZoneOffset.UTC).toInstant()
        return Timestamp(Date.from(start))
    }
    private fun profile(c:Context):Map<String,Any> {
        val p=prefs(c)
        val share=sharing(c)
        return mapOf(
            "name" to nickname(c).ifBlank{"Tag"},
            "share" to share,
            "personalLeft" to if(share)Store.bal(c) else -1,
            "used" to if(share)(ProgressStore.recent(c,1).lastOrNull()?.usage ?: 0) else -1,
            "push" to (ProgressStore.totalReps(c)-p.getInt("basePush",0)).coerceAtLeast(0),
            "chess" to (ProgressStore.totalChess(c)-p.getInt("baseChess",0)).coerceAtLeast(0),
            "study" to (ProgressStore.totalStudy(c)-p.getInt("baseStudy",0)).coerceAtLeast(0),
            "updatedAt" to FieldValue.serverTimestamp()
        )
    }
    private fun saveBasics(c:Context,id:String,label:String,key:String,owner:Boolean) {
        prefs(c).edit()
            .putString("teamId",id).putString("teamName",label)
            .putString("key",key).putBoolean("owner",owner)
            .putInt("basePush",ProgressStore.totalReps(c))
            .putInt("baseChess",ProgressStore.totalChess(c))
            .putInt("baseStudy",ProgressStore.totalStudy(c))
            .putInt("lease",0).putString("pending","[]").commit()
    }
    private fun randomSecret():String {
        val bytes=ByteArray(32)
        SecureRandom().nextBytes(bytes)
        return Base64.encodeToString(bytes,Base64.URL_SAFE or
            Base64.NO_PADDING or Base64.NO_WRAP)
    }
    fun invite(c:Context):String {
        if(!active(c) || !owner(c))return ""
        val data=JSONObject().put("v",1).put("id",teamId(c))
            .put("key",prefs(c).getString("key","")?:"")
            .put("name",teamName(c))
        return "fokusz-firebase:v1:"+Base64.encodeToString(
            data.toString().toByteArray(Charsets.UTF_8),
            Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
    }

    suspend fun create(c:Context,label:String,user:String,share:Boolean):String =
        withContext(Dispatchers.IO) {
            try {
                if(active(c))return@withContext "Már tagja vagy egy csapatnak."
                if(label.trim().length !in 3..40 || user.trim().length !in 2..24)
                    return@withContext "A csapatnév és a becenév túl rövid."
                val uid=ensureAuth()
                setNickname(c,user);setSharing(c,share)
                val id=db().collection("teams").document().id
                val key=randomSecret()
                val ownerProfile=mapOf<String,Any>(
                    "name" to nickname(c),"share" to share,
                    "personalLeft" to if(share)Store.bal(c) else -1,
                    "used" to if(share)(ProgressStore.recent(c,1).lastOrNull()?.usage?:0) else -1,
                    "push" to 0,"chess" to 0,"study" to 0,
                    "updatedAt" to FieldValue.serverTimestamp())
                val data=mapOf<String,Any>(
                    "ownerUid" to uid,"name" to label.trim(),
                    "inviteKey" to key,"joinProof" to "",
                    "createdAt" to FieldValue.serverTimestamp(),
                    "lastDay" to todayUtc(),
                    "members" to mapOf(uid to ownerProfile),
                    "pool" to 600,"bank" to 0,"lastRewardEvent" to "",
                    "bossPush" to 0,"bossChess" to 0,"bossStudy" to 0,
                    "bossTargetPush" to 100,"bossTargetChess" to 10,
                    "bossTargetStudy" to 10800,
                    "bossClaimedAt" to Timestamp(Date(0L))
                )
                db().collection("teams").document(id).set(data).await()
                saveBasics(c,id,label.trim(),key,true)
                status="Csapat létrehozva. QR-kód megosztható."
                start(c)
                "✓ Csapat létrehozva."
            }catch(e:Exception) {status=e.localizedMessage?:"Firebase-hiba";"Hiba: "+status}
        }

    suspend fun join(c:Context,qr:String,user:String,share:Boolean):String =
        withContext(Dispatchers.IO) {
            try {
                if(active(c))return@withContext "Már egy csapat tagja vagy."
                if(user.trim().length !in 2..24)return@withContext "Adj meg legalább 2 karakteres becenevet."
                if(!qr.startsWith("fokusz-firebase:v1:") || qr.length>1200)
                    return@withContext "Nem megfelelő Fókusz Firebase QR-kód."
                val payload=String(Base64.decode(
                    qr.removePrefix("fokusz-firebase:v1:"),
                    Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP),
                    Charsets.UTF_8)
                val args=JSONObject(payload)
                val id=args.getString("id")
                val key=args.getString("key")
                val name=args.getString("name")
                if(!id.matches(Regex("[A-Za-z0-9]{12,40}")) ||
                    key.length !in 35..100 || name.length !in 3..40)
                    return@withContext "Hibás meghívókód."
                val uid=ensureAuth()
                setNickname(c,user);setSharing(c,share)
                val profile=mapOf<String,Any>(
                    "name" to nickname(c),"share" to share,
                    "personalLeft" to if(share)Store.bal(c) else -1,
                    "used" to if(share)(ProgressStore.recent(c,1).lastOrNull()?.usage ?: 0) else -1,
                    "push" to 0,"chess" to 0,"study" to 0,
                    "updatedAt" to FieldValue.serverTimestamp())
                // Első alkalommal csak a QR kulcs ismeretében lehet írni.
                // A csapat adatlapja a tagság előtt nem olvasható.
                db().collection("teams").document(id).update(mapOf(
                    "members.$uid" to profile,
                    "pool" to FieldValue.increment(600),
                    "joinProof" to "$key:$uid",
                    "bossTargetPush" to FieldValue.increment(100),
                    "bossTargetChess" to FieldValue.increment(10),
                    "bossTargetStudy" to FieldValue.increment(10800)
                )).await()
                saveBasics(c,id,name,key,false)
                start(c)
                status="✓ Távolról csatlakoztál a csapathoz."
                status
            }catch(e:Exception) {
                status="Csatlakozási hiba: "+(e.localizedMessage?:"ellenőrizd a Firebase-szabályokat")
                status
            }
        }

    @Synchronized fun start(c:Context) {
        if(!active(c)||!starting.compareAndSet(false,true))return
        val app=c.applicationContext
        io.launch {
            try {
                ensureAuth()
                val id=teamId(app)
                if(id.isNotBlank() && listenerId!=id) {
                    listener?.remove()
                    listenerId=id
                    listener=teamRef(app).addSnapshotListener { snap,err ->
                        if(err!=null) {
                            status="Szinkronhiba: "+(err.localizedMessage ?: "nincs hozzáférés")
                            return@addSnapshotListener
                        }
                        if(snap!=null && snap.exists()) {
                            val data=snap.data ?: emptyMap()
                            val raw=data["members"] as? Map<*,*> ?: emptyMap<Any,Any>()
                            val people=raw.mapNotNull { (uid,value) ->
                                val m=value as? Map<*,*> ?: return@mapNotNull null
                                CloudMember(
                                    uid.toString(),m["name"]?.toString()?:"Tag",
                                    m["share"] as? Boolean ?:false,
                                    (m["personalLeft"] as? Number)?.toInt() ?: -1,
                                    (m["used"] as? Number)?.toInt() ?: -1,
                                    (m["push"] as? Number)?.toInt() ?: 0,
                                    (m["chess"] as? Number)?.toInt() ?: 0,
                                    (m["study"] as? Number)?.toInt() ?: 0,
                                    (m["updatedAt"] as? Timestamp)?.toDate()
                                )
                            }.sortedBy { it.name }
                            fun num(key:String)=(data[key] as? Number)?.toInt() ?: 0
                            view=CloudTeamView(
                                id=snap.id,name=data["name"]?.toString()?:"Csapat",
                                pool=num("pool"),bank=num("bank"),
                                members=people,
                                push=num("bossPush"),chess=num("bossChess"),
                                study=num("bossStudy"),
                                targetPush=num("bossTargetPush"),
                                targetChess=num("bossTargetChess"),
                                targetStudy=num("bossTargetStudy"),
                                bossClaimed=(data["bossClaimedAt"] as? Timestamp)?.toDate(),
                                syncedAt=System.currentTimeMillis(),
                                cached=snap.metadata.isFromCache
                            )
                            status=if(snap.metadata.isFromCache)
                                "Az utolsó szinkronizált állapot látható (offline)."
                                else "✓ Firebase: a csapat távolról szinkronizál."
                        }
                    }
                }
                pulse(app,true)
            }catch(e:Exception){status="Firebase-hiba: "+(e.localizedMessage ?: "nem elérhető")}
            finally {starting.set(false)}
        }
    }

    fun pulse(c:Context,force:Boolean=false) {
        if(!active(c))return
        val now=System.currentTimeMillis()
        if(!force && now-lastPulse<5000L)return
        lastPulse=now
        if(listenerId!=teamId(c))start(c)
        if(force || now-lastSettle>55000L) {
            lastSettle=now
            io.launch {settle(c.applicationContext)}
        }
        if(force || now-lastOwn>90000L) {
            lastOwn=now
            io.launch{syncProfile(c.applicationContext)}
        }
        if(force || now-lastReward>45000L) {
            lastReward=now
            io.launch{flushRewards(c.applicationContext)}
        }
        // Közös időt csak ténylegesen korlátozott app használatakor kérünk.
    }
    private suspend fun settle(c:Context) {
        if(!active(c))return
        try {
            ensureAuth()
            repeat(8) {
                val changed=db().runTransaction { tx ->
                    val ref=teamRef(c)
                    val snap=tx.get(ref)
                    if(!snap.exists())return@runTransaction false
                    val last=snap.getTimestamp("lastDay") ?:return@runTransaction false
                    val next=Timestamp(Date(last.toDate().time+86400000L))
                    if(next>todayUtc())return@runTransaction false
                    val pool=(snap.getLong("pool")?:0L).coerceAtLeast(0)
                    val bank=(snap.getLong("bank")?:0L).coerceAtLeast(0)
                    val total=(pool+bank).coerceAtMost(MAX_BANK.toLong())
                    val nextBank=(total+total/10).coerceAtMost(MAX_BANK.toLong())
                    val size=(snap.get("members") as? Map<*,*>)?.size?:1
                    tx.update(ref,mapOf("pool" to size*600,
                        "bank" to nextBank,"lastDay" to next))
                    true
                }.await()
                if(!changed)return
            }
        }catch(e:Exception){status="Napi bankfrissítés: "+(e.localizedMessage?:"hiba")}
    }

    private suspend fun syncProfile(c:Context) {
        if(!active(c))return
        try {
            val uid=ensureAuth()
            val data=profile(c)
            db().runTransaction {tx ->
                val ref=teamRef(c)
                val snap=tx.get(ref)
                val members=snap.get("members") as? Map<*,*>
                    ?:return@runTransaction false
                val old=members[uid] as? Map<*,*> ?:return@runTransaction false
                fun oldInt(k:String)=(old[k] as? Number)?.toInt() ?: 0
                fun nowInt(k:String)=(data[k] as? Number)?.toInt() ?: 0
                val pushDelta=(nowInt("push")-oldInt("push")).coerceIn(0,1000)
                val chessDelta=(nowInt("chess")-oldInt("chess")).coerceIn(0,100)
                val studyDelta=(nowInt("study")-oldInt("study")).coerceIn(0,86400)
                val safe=data.toMutableMap()
                safe["push"]=oldInt("push")+pushDelta
                safe["chess"]=oldInt("chess")+chessDelta
                safe["study"]=oldInt("study")+studyDelta
                tx.update(ref,mapOf(
                    "members.$uid" to safe,
                    "bossPush" to ((snap.getLong("bossPush")?:0L)+pushDelta),
                    "bossChess" to ((snap.getLong("bossChess")?:0L)+chessDelta),
                    "bossStudy" to ((snap.getLong("bossStudy")?:0L)+studyDelta)
                ))
                true
            }.await()
        }catch(e:Exception){status="Tagstatisztika: "+(e.localizedMessage?:"szinkronhiba")}
    }
    @Synchronized fun questCompleted(c:Context) {
        if(!active(c))return
        val p=prefs(c)
        val raw=try{JSONArray(p.getString("pending","[]"))}catch(_:Exception){JSONArray()}
        if(raw.length()<100) {
            raw.put(UUID.randomUUID().toString())
            p.edit().putString("pending",raw.toString()).commit()
        }
        io.launch {flushRewards(c.applicationContext)}
    }
    private suspend fun flushRewards(c:Context) {
        if(!active(c))return
        try {
            val uid=ensureAuth()
            val p=prefs(c)
            val items=try{JSONArray(p.getString("pending","[]"))}catch(_:Exception){JSONArray()}
            for(i in 0 until minOf(items.length(),15)) {
                val uuid=items.optString(i)
                if(uuid.isBlank())continue
                val eventId=uid+"_"+uuid
                val ref=teamRef(c)
                val ev=ref.collection("events").document(eventId)
                val ok=db().runTransaction {tx ->
                    val snap=tx.get(ref)
                    val existing=tx.get(ev)
                    if(existing.exists())return@runTransaction true
                    val pool=snap.getLong("pool")?:0L
                    if(pool+300>MAX_POOL)return@runTransaction false
                    tx.update(ref,mapOf(
                        "pool" to pool+300,
                        "lastRewardEvent" to eventId))
                    tx.set(ev,mapOf("uid" to uid,"amount" to 300,
                        "createdAt" to FieldValue.serverTimestamp()))
                    true
                }.await()
                if(!ok)break
                val remaining=JSONArray()
                for(j in 0 until items.length())if(items.optString(j)!=uuid)
                    remaining.put(items.optString(j))
                p.edit().putString("pending",remaining.toString()).commit()
            }
        }catch(e:Exception){status="Quest szinkron: "+(e.localizedMessage?:"hiba")}
    }
    private fun requestLease(c:Context) {
        if(!active(c) || !leasing.compareAndSet(false,true))return
        io.launch {
            try {
                ensureAuth()
                val taken=db().runTransaction {tx ->
                    val ref=teamRef(c)
                    val snap=tx.get(ref)
                    val pool=snap.getLong("pool")?:0L
                    val bank=snap.getLong("bank")?:0L
                    val n=minOf(60L,pool+bank)
                    if(n<=0L)return@runTransaction 0
                    val fromPool=minOf(pool,n)
                    tx.update(ref,mapOf("pool" to pool-fromPool,
                        "bank" to bank-(n-fromPool)))
                    n.toInt()
                }.await()
                if(taken>0) synchronized(this@CloudTeams) {
                    val p=prefs(c)
                    p.edit().putInt("lease",(p.getInt("lease",0)+taken)
                        .coerceAtMost(120)).commit()
                }
            }catch(e:Exception){
                status="Közös idő nem elérhető: "+(e.localizedMessage?:"kapcsolati hiba")
            }finally{leasing.set(false)}
        }
    }
    @Synchronized fun spendSecond(c:Context):Boolean {
        if(!active(c))return false
        val p=prefs(c)
        val n=p.getInt("lease",0)
        if(n<=0) {
            requestLease(c);return false
        }
        p.edit().putInt("lease",n-1).commit()
        if(n<=20)requestLease(c)
        return true
    }
    suspend fun claimBoss(c:Context):String=withContext(Dispatchers.IO) {
        if(!active(c))return@withContext "Nem vagy csapattag."
        try {
            ensureAuth()
            val won=db().runTransaction {tx ->
                val ref=teamRef(c)
                val snap=tx.get(ref)
                val members=snap.get("members") as? Map<*,*> ?:return@runTransaction false
                fun v(k:String)=snap.getLong(k)?:0L
                if(v("bossPush")<v("bossTargetPush") ||
                    v("bossChess")<v("bossTargetChess") ||
                    v("bossStudy")<v("bossTargetStudy"))return@runTransaction false
                val prior=snap.getTimestamp("bossClaimedAt")?.toDate()?.time?:0L
                if(System.currentTimeMillis()-prior<7*86400000L)return@runTransaction false
                val reward=members.size.toLong()*900L
                if(v("pool")+reward>MAX_POOL)return@runTransaction false
                tx.update(ref,mapOf(
                    "pool" to v("pool")+reward,
                    "bossClaimedAt" to FieldValue.serverTimestamp(),
                    "bossTargetPush" to v("bossPush")+members.size*100L,
                    "bossTargetChess" to v("bossChess")+members.size*10L,
                    "bossTargetStudy" to v("bossStudy")+members.size*10800L
                ))
                true
            }.await()
            if(won)"🏆 Boss Fight teljesítve! A csapat jutalma jóváírva."
            else "Még nincs teljesítve, 7 nap még nem telt el, vagy tele van a keret."
        }catch(e:Exception){"Boss Fight hiba: "+(e.localizedMessage?:"nincs hozzáférés")}
    }
    suspend fun removeMember(c:Context,id:String):String=withContext(Dispatchers.IO) {
        try {
            val uid=ensureAuth()
            if(!active(c))return@withContext "Nincs csapat."
            if(!owner(c) && id!=uid)return@withContext "Csak saját magadat léptetheted ki."
            if(id==uid && owner(c))return@withContext "A csapatgazda csak a csapat törlésével léphet ki."
            val ref=teamRef(c)
            ref.update(mapOf(
                "members.$id" to FieldValue.delete(),
                "bossTargetPush" to FieldValue.increment(-100),
                "bossTargetChess" to FieldValue.increment(-10),
                "bossTargetStudy" to FieldValue.increment(-10800)
            )).await()
            if(id==uid)clear(c)
            "✓ Tag eltávolítva."
        }catch(e:Exception){"Eltávolítási hiba: "+(e.localizedMessage?:"nem sikerült")}
    }
    suspend fun leave(c:Context):String=withContext(Dispatchers.IO) {
        try {
            if(!active(c))return@withContext "Nincs csapat."
            if(owner(c)) {
                ensureAuth()
                teamRef(c).delete().await()
                clear(c)
                "✓ Csapat megszüntetve."
            }else {
                removeMember(c,ensureAuth())
            }
        }catch(e:Exception){"Nem sikerült kilépni: "+(e.localizedMessage?:"hiba")}
    }
    @Synchronized fun clear(c:Context) {
        listener?.remove();listener=null;listenerId=""
        prefs(c).edit().remove("teamId").remove("teamName").remove("key")
            .remove("owner").putInt("lease",0).putString("pending","[]").commit()
        view=CloudTeamView()
        status="Nincs csapat."
    }
}
