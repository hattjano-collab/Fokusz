package hu.fokusz

import android.content.Context
import android.content.SharedPreferences
import android.os.SystemClock
import android.util.Base64
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.Transaction
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
    val syncedAt:Long=0L,val cached:Boolean=true,
    val lastBankEvent: String = ""
)

internal data class CloudWeeklyBoss(val teamId: String = "", val week: LocalDate? = null,
                                   val plans: Map<String, List<BossLine>> = emptyMap(),
                                   val claimed: Boolean = false, val allowed: Boolean? = null)

/** Internetes csapatok: Firebase Anonymous Auth + Cloud Firestore, Spark csomagon.
 * A közös egyenleget kizárólag Firestore-tranzakcióval módosítjuk.
 * A felvett 1 perces keret már a felvételkor levonódik a közös egyenlegből.
 * Nincs szükség gazdatelefonra vagy azonos Wi-Fi-re.
 */
object CloudTeams {
    // Nincs napi/24 órás keretplafon. A korlát kizárólag a 32 bites
    // Android időegyenlegek biztonságos tárolásához szükséges.
    private const val MAX_POOL=Store.MAX_SAFE_SECONDS
    private const val MAX_BANK=Store.MAX_SAFE_SECONDS
    private val io=CoroutineScope(SupervisorJob()+Dispatchers.IO)
    private val starting=AtomicBoolean(false)
    private val leasing=AtomicBoolean(false)
    private var listener:ListenerRegistration?=null
    private var weeklyBossListener: ListenerRegistration? = null
    @Volatile private var listenerId=""
    @Volatile private var lastPulse=0L
    @Volatile private var lastOwn=0L
    @Volatile private var lastSettle=0L
    @Volatile private var lastReward=0L
    @Volatile private var leaseRetryAt=0L
    private data class JournalFallback(val teamId: String, val retryAt: Long)
    @Volatile private var journalFallback: JournalFallback? = null
    var view by mutableStateOf(CloudTeamView())
        private set
    var status by mutableStateOf("Firebase-kapcsolat indítása…")
        private set
    internal var weeklyBoss by mutableStateOf(CloudWeeklyBoss())
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
    private fun quotaKey(id: String, day: Timestamp) = "allocated_${id}_${day.seconds}"
    fun leased(c: Context): Int = prefs(c).let {
        if (it.getLong("leaseDay", 0L) == todayUtc().seconds) it.getInt("lease", 0).coerceAtLeast(0) else 0
    }
    fun pendingContribution(c:Context)=prefs(c).getString("contribution","")?.isNotBlank()==true
    private suspend fun ensureAuth():String {
        val existing=auth().currentUser
        if(existing!=null)return existing.uid
        return auth().signInAnonymously().await().user?.uid
            ?: throw IllegalStateException("Nem sikerült az anonim Firebase-bejelentkezés.")
    }
    private fun teamRef(c:Context)=db().collection("teams").document(teamId(c))

    private suspend fun <T> bankTransaction(
        ref: DocumentReference,
        operation: (Transaction, Boolean) -> T
    ): T {
        val fallback = journalFallback
        if (fallback?.teamId == ref.id && SystemClock.elapsedRealtime() < fallback.retryAt) {
            return db().runTransaction { operation(it, false) }.await()
        }
        return try {
            db().runTransaction { operation(it, true) }.await()
        } catch (e: FirebaseFirestoreException) {
            if (e.code != FirebaseFirestoreException.Code.PERMISSION_DENIED) throw e
            val result = db().runTransaction { operation(it, false) }.await()
            journalFallback = JournalFallback(ref.id, SystemClock.elapsedRealtime() + 300_000L)
            result
        }
    }

    private fun updateBank(
        tx: Transaction,
        snap: DocumentSnapshot,
        changes: Map<String, Any>,
        journal: Boolean,
        kind: String,
        uid: String,
        eventId: String
    ) {
        val group = snap.reference
        if (!journal) {
            tx.update(group, changes)
            return
        }
        val oldPool = snap.getLong("pool") ?: 0L
        val oldBank = snap.getLong("bank") ?: 0L
        tx.update(group, changes + ("lastBankEvent" to eventId))
        tx.set(group.collection("bankEvents").document(eventId), mapOf(
            "uid" to uid,
            "kind" to kind,
            "beforePool" to oldPool,
            "beforeBank" to oldBank,
            "afterPool" to ((changes["pool"] as? Number)?.toLong() ?: oldPool),
            "afterBank" to ((changes["bank"] as? Number)?.toLong() ?: oldBank),
            "createdAt" to FieldValue.serverTimestamp()
        ))
    }

    private fun bankEventId(uid: String) = "${uid}_${UUID.randomUUID()}"

    data class BankEvent(val uid: String, val kind: String, val beforePool: Long, val beforeBank: Long,
                         val afterPool: Long, val afterBank: Long, val timestamp: Date)

    suspend fun bankHistory(c: Context): List<BankEvent>? = withContext(Dispatchers.IO) {
        if (!active(c)) return@withContext emptyList()
        try {
            ensureAuth()
            teamRef(c).collection("bankEvents").orderBy("createdAt", Query.Direction.DESCENDING)
                .limit(50).get().await().documents.mapNotNull { doc ->
                    val at = doc.getTimestamp("createdAt")?.toDate() ?: return@mapNotNull null
                    BankEvent(doc.getString("uid").orEmpty(), doc.getString("kind").orEmpty(),
                        doc.getLong("beforePool") ?: 0L, doc.getLong("beforeBank") ?: 0L,
                        doc.getLong("afterPool") ?: 0L, doc.getLong("afterBank") ?: 0L, at)
                }
        } catch (e: FirebaseFirestoreException) {
            if (e.code != FirebaseFirestoreException.Code.PERMISSION_DENIED) throw e
            null
        }
    }

    internal fun teamBossWeek(): LocalDate = monday(LocalDate.now(ZoneOffset.UTC))

    private fun weekTimestamp(week: LocalDate): Timestamp =
        Timestamp(Date.from(week.atStartOfDay(ZoneOffset.UTC).toInstant()))

    private fun weekly(ref: DocumentReference) = ref.collection("weeklyBoss").document("current")

    private fun plans(snap: DocumentSnapshot): Map<String, List<BossLine>> {
        val raw = snap.get("plans") as? Map<*, *> ?: return emptyMap()
        return raw.mapNotNull { (uid, value) ->
            val key = uid as? String ?: return@mapNotNull null
            val list = value as? List<*> ?: return@mapNotNull null
            val lines = list.mapNotNull { entry ->
                val data = entry as? Map<*, *> ?: return@mapNotNull null
                val activity = data["activity"] as? String ?: return@mapNotNull null
                val title = data["title"] as? String ?: return@mapNotNull null
                val unit = BossUnit.entries.firstOrNull { it.key == data["unit"] } ?: return@mapNotNull null
                val target = (data["target"] as? Number)?.toInt() ?: return@mapNotNull null
                val session = (data["session"] as? Number)?.toInt() ?: return@mapNotNull null
                val done = (data["done"] as? Number)?.toInt() ?: return@mapNotNull null
                BossLine(BossGoal(BossActivity(activity, title, unit, session), target), done)
            }
            if (lines.size == 3 && BossQuest.valid(lines.map { it.goal })) key to lines else null
        }.toMap()
    }

    private fun watchWeeklyBoss(ref: DocumentReference) {
        weeklyBossListener?.remove()
        weeklyBoss = CloudWeeklyBoss(teamId = ref.id)
        weeklyBossListener = weekly(ref).addSnapshotListener { snap, error ->
            if (listenerId != ref.id) return@addSnapshotListener
            if (error != null) {
                weeklyBoss = CloudWeeklyBoss(teamId = ref.id,
                    allowed = if (error.code == FirebaseFirestoreException.Code.PERMISSION_DENIED) false else null)
            } else if (snap != null) {
                weeklyBoss = CloudWeeklyBoss(ref.id,
                    snap.getTimestamp("week")?.toDate()?.toInstant()?.atZone(ZoneOffset.UTC)?.toLocalDate(),
                    plans(snap), snap.getTimestamp("claimedAt") != null, true)
            }
        }
    }

    internal fun refreshWeeklyBoss(c: Context) {
        if (!active(c)) return
        if (listenerId == teamId(c)) watchWeeklyBoss(teamRef(c)) else start(c)
    }

    private suspend fun writeBossPlan(c: Context, requested: List<BossGoal>?): Boolean {
        val account = ensureAuth()
        val group = teamRef(c)
        val ref = weekly(group)
        val week = teamBossWeek()
        val timestamp = weekTimestamp(week)
        return db().runTransaction { tx ->
            val groupData = tx.get(group)
            val previous = tx.get(ref)
            val members = groupData.get("members") as? Map<*, *> ?: return@runTransaction false
            if (account !in members) return@runTransaction false
            val sameWeek = previous.getTimestamp("week") == timestamp
            if (sameWeek && previous.getTimestamp("claimedAt") != null) return@runTransaction false
            val old = if (sameWeek) plans(previous)[account].orEmpty() else emptyList()
            val selected = requested ?: old.map { it.goal }
            if (!BossQuest.valid(selected)) return@runTransaction false
            val lines = selected.map { goal ->
                val persisted = old.firstOrNull { it.goal.activity.key == goal.activity.key }?.done ?: 0
                BossLine(goal, minOf(goal.target, maxOf(persisted, BossProgress.count(c, goal.activity, week))))
            }
            if (lines == old) return@runTransaction true
            val encoded = lines.map { line ->
                val item = line.goal.activity
                mapOf("activity" to item.key, "title" to item.title, "unit" to item.unit.key,
                    "session" to item.session, "target" to line.goal.target, "done" to line.done)
            }
            val allDone = lines.all { it.done >= it.goal.target }
            if (sameWeek) {
                tx.update(ref, mapOf("plans.$account" to encoded,
                    "ready.$account" to (if (allDone) true else FieldValue.delete())))
            } else {
                tx.set(ref, mapOf("week" to timestamp, "plans" to mapOf(account to encoded),
                    "ready" to (if (allDone) mapOf(account to true) else emptyMap<String, Boolean>()),
                    "claimedAt" to null))
            }
            true
        }.await()
    }

    private fun bossError(error: Exception): String =
        if (error is FirebaseFirestoreException && error.code == FirebaseFirestoreException.Code.PERMISSION_DENIED)
            "A közös heti célokhoz közzé kell tenni az új Firebase-szabályokat."
        else "A heti Boss Fight most nem szinkronizálható."

    internal suspend fun saveWeeklyBoss(c: Context, goals: List<BossGoal>): String = withContext(Dispatchers.IO) {
        if (!active(c) || !BossQuest.valid(goals)) return@withContext "Válassz három különböző célt."
        try {
            if (writeBossPlan(c, goals)) "A három heti cél mentve."
            else "A csapat erre a hétre már felvette a jutalmat."
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (error: Exception) { bossError(error) }
    }

    internal suspend fun syncBossProgress(c: Context): String? = withContext(Dispatchers.IO) {
        if (!active(c)) return@withContext null
        try {
            val current = weeklyBoss
            if (current.teamId != teamId(c) || current.week != teamBossWeek() || current.claimed ||
                currentUid() !in current.plans) return@withContext null
            if (writeBossPlan(c, null)) null else "A heti célok haladása még nem menthető."
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (error: Exception) { bossError(error) }
    }

    private fun todayUtc():Timestamp {
        val start=LocalDate.now(ZoneOffset.UTC).atStartOfDay(ZoneOffset.UTC).toInstant()
        return Timestamp(Date.from(start))
    }
    private fun totalChessCount(c:Context):Int =
        maxOf(ProgressStore.totalChess(c),
            ChessVerifier.wins(c)+ChessVerifier.losses(c)+ChessVerifier.draws(c))
    private fun profile(c:Context):Map<String,Any> {
        val p=prefs(c)
        val share=sharing(c)
        return mapOf(
            "name" to nickname(c).ifBlank{"Tag"},
            "share" to share,
            "personalLeft" to if(share)Store.bal(c) else -1,
            "used" to if(share)(ProgressStore.recent(c,1).lastOrNull()?.usage ?: 0) else -1,
            "push" to (ProgressStore.totalReps(c)-p.getInt("basePush",0)).coerceAtLeast(0),
            "chess" to (totalChessCount(c)-p.getInt("baseChess",0)).coerceAtLeast(0),
            "study" to (ProgressStore.totalStudy(c)-p.getInt("baseStudy",0)).coerceAtLeast(0),
            "updatedAt" to FieldValue.serverTimestamp()
        )
    }
    private fun saveBasics(c:Context,id:String,label:String,key:String,owner:Boolean) {
        prefs(c).edit()
            .putString("teamId",id).putString("teamName",label)
            .putString("key",key).putBoolean("owner",owner)
            .putInt("basePush",ProgressStore.totalReps(c))
            .putInt("baseChess",totalChessCount(c))
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
                    watchWeeklyBoss(teamRef(app))
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
                                syncedAt=System.currentTimeMillis(),
                                cached=snap.metadata.isFromCache,
                                lastBankEvent=snap.getString("lastBankEvent").orEmpty()
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
            io.launch {
                syncProfile(c.applicationContext)
                syncBossProgress(c.applicationContext)
            }
        }
        if(force || now-lastReward>45000L) {
            lastReward=now
            io.launch{flushRewards(c.applicationContext)}
        }
        if(pendingContribution(c))io.launch{flushContribution(c.applicationContext)}
        // Közös időt csak ténylegesen korlátozott app használatakor kérünk.
    }
    private suspend fun settle(c:Context) {
        if(!active(c))return
        try {
            val uid = ensureAuth()
            val ref = teamRef(c)
            repeat(8) {
                val eventId = bankEventId(uid)
                val changed = bankTransaction(ref) { tx, journal ->
                    val snap=tx.get(ref)
                    if(!snap.exists())return@bankTransaction false
                    val last=snap.getTimestamp("lastDay") ?:return@bankTransaction false
                    val next=Timestamp(Date(last.toDate().time+86400000L))
                    if(next>todayUtc())return@bankTransaction false
                    val pool=(snap.getLong("pool")?:0L).coerceAtLeast(0)
                    val bank=(snap.getLong("bank")?:0L).coerceAtLeast(0)
                    val total=(pool+bank).coerceAtMost(MAX_BANK.toLong())
                    val nextBank=(total+total/10).coerceAtMost(MAX_BANK.toLong())
                    val size=(snap.get("members") as? Map<*,*>)?.size?:1
                    updateBank(tx, snap, mapOf("pool" to size*600, "bank" to nextBank, "lastDay" to next),
                        journal, "daily", uid, eventId)
                    true
                }
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
                val ok = bankTransaction(ref) { tx, journal ->
                    val snap=tx.get(ref)
                    val existing=tx.get(ev)
                    if(existing.exists())return@bankTransaction true
                    val pool=snap.getLong("pool")?:0L
                    if(pool+300>MAX_POOL)return@bankTransaction false
                    updateBank(tx, snap, mapOf(
                        "pool" to pool+300,
                        "lastRewardEvent" to eventId), journal, "quest", uid, eventId)
                    tx.set(ev,mapOf("uid" to uid,"amount" to 300,
                        "createdAt" to FieldValue.serverTimestamp()))
                    true
                }
                if(!ok)break
                val remaining=JSONArray()
                for(j in 0 until items.length())if(items.optString(j)!=uuid)
                    remaining.put(items.optString(j))
                p.edit().putString("pending",remaining.toString()).commit()
            }
        }catch(e:Exception){status="Quest szinkron: "+(e.localizedMessage?:"hiba")}
    }
    private fun requestLease(c: Context, maximum: Int) {
        val id = teamId(c)
        val day = todayUtc()
        if (id.isBlank() || maximum <= 0 || SystemClock.elapsedRealtime() < leaseRetryAt ||
            !leasing.compareAndSet(false, true)) return
        io.launch {
            try {
                val uid = ensureAuth()
                val ref = db().collection("teams").document(id)
                val eventId = bankEventId(uid)
                val taken = bankTransaction(ref) { tx, journal ->
                    val snap = tx.get(ref)
                    if (snap.getTimestamp("lastDay") != day) return@bankTransaction 0
                    val members = snap.get("members") as? Map<*, *> ?: return@bankTransaction 0
                    if (uid !in members) return@bankTransaction 0
                    val pool = snap.getLong("pool") ?: 0L
                    val bank = snap.getLong("bank") ?: 0L
                    val quota = ref.collection("dailyUsage").document(uid)
                    val serverQuota = if (journal) tx.get(quota) else null
                    val localAllocated = prefs(c).getInt(quotaKey(id, day), 0).toLong()
                    val already = if (serverQuota == null) localAllocated else {
                        val remote = if (serverQuota.getTimestamp("day") == day) serverQuota.getLong("used") ?: 0L else 0L
                        remote
                    }
                    val remaining = members.size * 300L - maxOf(already, localAllocated)
                    val n = minOf(60L, maximum.toLong(), pool + bank, remaining)
                    if (n <= 0L) return@bankTransaction 0
                    val fromPool = minOf(pool, n)
                    updateBank(tx, snap, mapOf("pool" to pool - fromPool, "bank" to bank - (n - fromPool)),
                        journal, "usage", uid, eventId)
                    if (journal) tx.set(quota, mapOf("day" to day, "used" to already + n))
                    n.toInt()
                }
                synchronized(this@CloudTeams) {
                    if (teamId(c) == id && taken > 0) {
                        val p = prefs(c)
                        if (day != todayUtc()) return@synchronized
                        val key = quotaKey(id, day)
                        val edit = p.edit().putLong("leaseDay", day.seconds)
                            .putInt("lease", (leased(c) + taken).coerceAtMost(120))
                            .putInt(key, p.getInt(key, 0) + taken)
                        p.all.keys.filter { it.startsWith("allocated_") && !it.endsWith("_${day.seconds}") }
                            .forEach { edit.remove(it) }
                        edit.commit()
                    } else if (teamId(c) == id) leaseRetryAt = SystemClock.elapsedRealtime() + 10_000L
                }
            }catch(e:Exception){
                if (teamId(c) == id) leaseRetryAt = SystemClock.elapsedRealtime() + 10_000L
                status="Közös idő nem elérhető: "+(e.localizedMessage?:"kapcsolati hiba")
            }finally{leasing.set(false)}
        }
    }
    @Synchronized fun spendSecond(c: Context, sessionRemaining: Int? = null): Boolean {
        if (!active(c) || sessionRemaining == 0) return false
        val p=prefs(c)
        val n=leased(c)
        val unreserved = (sessionRemaining ?: 120) - n
        if(n<=0) {
            requestLease(c, unreserved)
            return false
        }
        p.edit().putInt("lease",n-1).commit()
        if(n<=20) requestLease(c, unreserved)
        return true
    }
    /** Idempotens átutalás a saját képernyőidőből: a helyi időt előbb
     * lefoglaljuk, majd Firebase-tranzakcióval jóváírjuk. Hálózati hiba
     * esetén a könyvelési művelet megmarad, és újra próbáljuk. Nem
     * állítunk ki visszatérítést bizonytalan hálózati eredménynél.
     */
    suspend fun contribute(c:Context,seconds:Int,toBank:Boolean):String =
        withContext(Dispatchers.IO) {
            if(!active(c))return@withContext "Előbb lépj be egy csapatba."
            if(seconds<=0)return@withContext "Adj meg pozitív percösszeget."
            val p=prefs(c)
            if(pendingContribution(c))return@withContext "Van egy folyamatban lévő átutalás. Előbb azt szinkronizáljuk."
            val current=Store.bal(c)
            if(seconds>current)return@withContext "Nincs elég saját képernyőidőd."
            val uid=try{ensureAuth()}catch(e:Exception){
                return@withContext "Firebase-kapcsolat szükséges: "+(e.localizedMessage?:"hiba")
            }
            val id=uid+"_"+UUID.randomUUID().toString()
            val operation=JSONObject()
                .put("eventId",id)
                .put("amount",seconds)
                .put("bank",toBank)
                .put("teamId",teamId(c))
            // A küldésre lefoglalt pénz nem költhető el közben.
            val reason = if (toBank) "Befizetés a csapatbankba" else "Befizetés a közös keretbe"
            if (!Store.spend(c, seconds, reason)) return@withContext "Nem sikerült lefoglalni az időt."
            p.edit().putString("contribution",operation.toString()).commit()
            val done=flushContribution(c)
            if(done) "✓ "+duration(seconds)+" a csapat "+
                (if(toBank)"bankjába"else"közös keretébe")+" került."
            else "Az idő lefoglalva; internetes kapcsolatkor a csapat automatikusan megkapja."
        }

    private suspend fun flushContribution(c:Context):Boolean {
        val p=prefs(c)
        val raw=p.getString("contribution","")?:""
        if(raw.isBlank())return true
        if(!active(c))return false
        return try {
            val op=JSONObject(raw)
            if(op.getString("teamId")!=teamId(c))return false
            val id=op.getString("eventId")
            val amount=op.getInt("amount")
            val toBank=op.optBoolean("bank")
            val uid = ensureAuth()
            val group=teamRef(c)
            val event=group.collection("events").document(id)
            val ok = bankTransaction(group) { tx, journal ->
                val snap=tx.get(group)
                val existing=tx.get(event)
                if(existing.exists())return@bankTransaction true
                val members=snap.get("members") as? Map<*,*>
                if(members==null||uid !in members)
                    throw IllegalStateException("Már nem vagy tagja a csapatnak.")
                val field=if(toBank)"bank"else"pool"
                val old=snap.getLong(field)?:0L
                if(old+amount>Store.MAX_SAFE_SECONDS.toLong())
                    throw IllegalStateException("A technikai időhatár elérve.")
                updateBank(tx, snap, mapOf(field to old+amount, "lastRewardEvent" to id), journal,
                    if (toBank) "personal_bank" else "personal_pool", uid, id)
                tx.set(event,mapOf("uid" to uid,"amount" to amount,
                    "createdAt" to FieldValue.serverTimestamp()))
                true
            }
            if(ok){
                p.edit().remove("contribution").commit()
                status="✓ A saját idő befizetése szinkronizálva."
            }
            ok
        }catch(e:Exception){
            status="Befizetés függőben: "+(e.localizedMessage?:"szinkronhiba")
            false
        }
    }

    /** A közös idő egy részét bármely tag áthelyezheti a közös bankba.
     * Nem ad hozzá új időt; csak átmozgatja, Firestore-tranzakcióval.
     */
    suspend fun movePoolToBank(c:Context,seconds:Int):String =
        withContext(Dispatchers.IO){
            if(!active(c))return@withContext "Nem vagy csapattag."
            if(seconds<=0)return@withContext "Adj meg pozitív percösszeget."
            try {
                val uid=ensureAuth()
                val ref=teamRef(c)
                val eventId = bankEventId(uid)
                val success = bankTransaction(ref) { tx, journal ->
                    val snap=tx.get(ref)
                    val members=snap.get("members") as? Map<*,*>
                    if(members==null || uid !in members)return@bankTransaction false
                    val pool=snap.getLong("pool")?:0L
                    val bank=snap.getLong("bank")?:0L
                    if(seconds.toLong()>pool||
                        bank+seconds>MAX_BANK.toLong())return@bankTransaction false
                    updateBank(tx, snap, mapOf("pool" to pool-seconds, "bank" to bank+seconds),
                        journal, "transfer", uid, eventId)
                    true
                }
                if(success)"✓ "+duration(seconds)+" átkerült a közös bankba."
                else "A közös keretben nincs ennyi felhasználható idő."
            }catch(e:Exception){"Átvezetési hiba: "+(e.localizedMessage?:"Firebase-hiba")}
        }

    suspend fun claimBoss(c: Context): String = withContext(Dispatchers.IO) {
        if (!active(c)) return@withContext "Nem vagy csapattag."
        try {
            val uid = ensureAuth()
            val ref = teamRef(c)
            val weekDoc = weekly(ref)
            val timestamp = weekTimestamp(teamBossWeek())
            val eventId = bankEventId(uid)
            val received = db().runTransaction { tx ->
                val team = tx.get(ref)
                val week = tx.get(weekDoc)
                val members = team.get("members") as? Map<*, *> ?: return@runTransaction 0L
                val done = week.get("ready") as? Map<*, *> ?: return@runTransaction 0L
                val pool = team.getLong("pool") ?: 0L
                if (uid !in members || week.getTimestamp("week") != timestamp ||
                    week.getTimestamp("claimedAt") != null || members.keys.any { done[it] != true } ||
                    team.getTimestamp("bossClaimedAt")?.let { it >= timestamp } == true) {
                    return@runTransaction 0L
                }
                val reward = members.size * 1800L
                if (reward <= 0 || pool + reward > MAX_POOL) return@runTransaction 0L
                tx.update(weekDoc, "claimedAt", FieldValue.serverTimestamp())
                updateBank(tx, team, mapOf("pool" to pool + reward, "bossClaimedAt" to FieldValue.serverTimestamp()),
                    true, "boss", uid, eventId)
                reward
            }.await()
            if (received > 0) "Jóváírva: ${duration(received.toInt())} a közös kerethez."
            else "A csapat céljai még nem készültek el, a jutalom már jóvá van írva, vagy megtelt a keret."
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (error: Exception) { bossError(error) }
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
        leaseRetryAt = 0L
        listener?.remove();listener=null;listenerId=""
        weeklyBossListener?.remove()
        weeklyBossListener = null
        weeklyBoss = CloudWeeklyBoss()
        prefs(c).edit().remove("teamId").remove("teamName").remove("key")
            .remove("owner").putInt("lease",0).putString("pending","[]").commit()
        view=CloudTeamView()
        status="Nincs csapat."
    }
}
