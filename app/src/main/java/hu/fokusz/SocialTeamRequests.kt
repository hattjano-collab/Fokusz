package hu.fokusz

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.Source
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

internal enum class SocialRequestKind(val key: String, val label: String) {
    EXTRA("extra", "Pluszidő"),
    EMPTY("empty", "15 perc hosszabbítás"),
    LIMIT("limit", "Napi limit emelése"),
    SCHEDULE("schedule", "Egyszeri fókuszsáv-kivétel"),
    REMOVE("remove", "Zárolás kikapcsolása")
}

internal data class SocialTeamRequest(
    val id: String,
    val uid: String,
    val device: String,
    val kind: SocialRequestKind,
    val pkg: String,
    val app: String,
    val old: Int,
    val minutes: Int,
    val reason: String,
    val state: String,
)

internal object SocialTeamRequests {
    private val background = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val syncing = AtomicBoolean(false)
    @Volatile private var lastAttempt = -20_000L
    private val main by lazy { Handler(Looper.getMainLooper()) }

    private fun prefs(c: Context) = c.getSharedPreferences("fokusz_team_social_requests", Context.MODE_PRIVATE)
    private fun requests(team: String) =
        FirebaseFirestore.getInstance().collection("teams").document(team).collection("socialRequests")

    fun guarded(c: Context) = CloudTeams.teamId(c).let { id ->
        id.isNotBlank() && prefs(c).getString("guardTeam", null) == id
    }
    fun setGuarded(c: Context, guarded: Boolean) =
        prefs(c).edit().putString("guardTeam", CloudTeams.teamId(c).takeIf { guarded }).apply()

    private fun pendingKey(team: String, pkg: String, kind: String) = "pending_${team}_${pkg}_$kind"

    fun waitingForTime(c: Context, pkg: String): Boolean {
        val team = CloudTeams.teamId(c)
        return team.isNotBlank() && listOf(SocialRequestKind.EXTRA, SocialRequestKind.EMPTY).any { kind ->
            prefs(c).contains(pendingKey(team, pkg, kind.key))
        }
    }

    @Synchronized private fun device(c: Context): String {
        val file = File(c.noBackupFilesDir, "social-request-device")
        val prior = runCatching { file.readText() }.getOrNull()
        return prior?.takeIf { value -> runCatching { UUID.fromString(value).toString() == value }.getOrDefault(false) }
            ?: UUID.randomUUID().toString().also { file.writeText(it) }
    }

    @Synchronized fun ask(c: Context, kind: SocialRequestKind, app: SocialUnlockApp, minutes: Int,
                         reason: String = "", old: Int = 0): String {
        val team = CloudTeams.teamId(c)
        val uid = CloudTeams.currentUid()
        if (team.isBlank() || uid.isBlank()) return "Előbb csatlakozz a csapathoz."
        if (!SocialUnlockStore.selected(c, app.packageName)) return "Válassz ki egy zárolt közösségi appot."
        val approvedReason = reason.trim().take(160)
        val valid = when (kind) {
            SocialRequestKind.EXTRA -> minutes in 1..60 && approvedReason.length >= 3
            SocialRequestKind.EMPTY -> minutes == 15 && approvedReason.length >= 3
            SocialRequestKind.LIMIT -> old in 1..1440 && (minutes == 0 || minutes in (old + 1)..1440)
            SocialRequestKind.SCHEDULE -> old.toLong() == FocusSchedules.endEpoch(c) && old > 0 &&
                minutes in 1..120 && approvedReason.length >= 3
            SocialRequestKind.REMOVE -> minutes == 0 && old == 0
        }
        if (!valid) return "Ellenőrizd a megadott adatokat."
        if (kind in setOf(SocialRequestKind.EXTRA, SocialRequestKind.EMPTY) && waitingForTime(c, app.packageName)) {
            return "Ehhez az apphoz már tartozik egy függő időkérelem."
        }
        val key = pendingKey(team, app.packageName, kind.key)
        val p = prefs(c)
        if (p.contains(key)) return "Már van ilyen függő kérelmed."
        val pending = queued(c)
        if (pending.size >= 40) return "Túl sok kérelem vár szinkronra."
        val install = runCatching { device(c) }.getOrElse { return "A kérelmet nem sikerült menteni." }
        val id = "${uid}_${UUID.randomUUID()}"
        val row = JSONObject().put("id", id).put("team", team).put("uid", uid)
            .put("device", install).put("kind", kind.key).put("pkg", app.packageName)
            .put("app", app.name.take(80)).put("old", old).put("minutes", minutes).put("reason", approvedReason)
        if (!p.edit().putString(key, id).putString("queue", JSONArray(pending + row).toString()).commit()) {
            return "A kérelmet nem sikerült menteni."
        }
        if (kind == SocialRequestKind.EXTRA || kind == SocialRequestKind.EMPTY) {
            SocialUnlockStore.current(c, app.packageName)?.let { SocialUnlockStore.close(c, it) }
            FocusService.refreshUnlocks()
        }
        pulse(c, force = true)
        return "A kérelem függőben van, amíg a csapat dönt."
    }

    private fun queued(c: Context): List<JSONObject> = runCatching {
        val array = JSONArray(prefs(c).getString("queue", "[]"))
        (0 until array.length()).mapNotNull(array::optJSONObject)
    }.getOrDefault(emptyList())

    private fun dropQueued(c: Context, id: String, key: String? = null) = synchronized(this) {
        val edit = prefs(c).edit().putString("queue", JSONArray(queued(c).filter { it.optString("id") != id }).toString())
        if (key != null) edit.remove(key)
        edit.commit()
    }

    fun pulse(c: Context, force: Boolean = false) {
        val team = CloudTeams.teamId(c)
        val elapsed = SystemClock.elapsedRealtime()
        if (team.isBlank() || (!force && elapsed - lastAttempt < 20_000L) ||
            !prefs(c).all.keys.any { it.startsWith("pending_${team}_") } || !syncing.compareAndSet(false, true)) return
        lastAttempt = elapsed
        val app = c.applicationContext
        background.launch {
            try { sync(app, team, CloudTeams.currentUid()) }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { /* The local request remains pending until a server decision arrives. */ }
            finally { syncing.set(false) }
        }
    }

    private suspend fun sync(c: Context, team: String, uid: String) {
        if (uid.isBlank()) return
        val install = device(c)
        val collection = requests(team)
        val database = FirebaseFirestore.getInstance()
        for (row in queued(c).filter { it.optString("team") == team && it.optString("uid") == uid }) {
            if (CloudTeams.teamId(c) != team) return
            val id = row.getString("id")
            val pkg = row.getString("pkg")
            val kind = row.getString("kind")
            val doc = collection.document(id)
            try {
                database.runTransaction { tx ->
                    if (!tx.get(doc).exists()) {
                        tx.set(doc, mapOf("uid" to uid, "device" to install, "kind" to kind, "pkg" to pkg,
                            "app" to row.getString("app"), "old" to row.getInt("old"),
                            "minutes" to row.getInt("minutes"), "reason" to row.getString("reason"),
                            "state" to "pending", "createdAt" to FieldValue.serverTimestamp()))
                    }
                }.await()
                dropQueued(c, id)
            } catch (error: com.google.firebase.firestore.FirebaseFirestoreException) {
                if (kind != "empty" || error.code != com.google.firebase.firestore.FirebaseFirestoreException.Code.PERMISSION_DENIED) throw error
                val current = FirebaseFirestore.getInstance().collection("teams").document(team).get(Source.SERVER).await()
                if ((current.getLong("pool") ?: 0) + (current.getLong("bank") ?: 0) > 0) {
                    dropQueued(c, id, pendingKey(team, pkg, kind))
                    main.post { FocusService.refreshUnlocks() }
                } else throw error
            }
        }
        val docs = collection.whereEqualTo("uid", uid).get(Source.SERVER).await()
        for (doc in docs.documents) {
            val request = parse(doc) ?: continue
            if (request.device != install || request.state == "pending") continue
            val p = prefs(c)
            val key = pendingKey(team, request.pkg, request.kind.key)
            if (p.getString(key, null) != request.id || CloudTeams.teamId(c) != team) continue
            if (request.state == "approved") {
                val applied = when (request.kind) {
                    SocialRequestKind.EXTRA, SocialRequestKind.EMPTY ->
                        SocialUnlockStore.creditApproved(c, request.id, request.pkg, request.minutes * 60)
                    SocialRequestKind.LIMIT -> {
                        if (SocialUsage.dailyLimitMinutes(c, request.pkg) == request.old) {
                            SocialUsage.setDailyLimit(c, request.pkg, request.minutes)
                        }
                        true
                    }
                    SocialRequestKind.SCHEDULE ->
                        FocusSchedules.acceptException(c, request.pkg, request.old, request.minutes)
                    SocialRequestKind.REMOVE -> SocialUnlockStore.setSelected(c, request.pkg, false)
                }
                if (!applied) continue
            }
            p.edit().remove(key).commit()
            main.post { FocusService.refreshUnlocks() }
        }
    }

    suspend fun latest(c: Context): List<SocialTeamRequest> {
        val team = CloudTeams.teamId(c).takeIf(String::isNotBlank) ?: return emptyList()
        return requests(team).orderBy("createdAt", Query.Direction.DESCENDING).limit(100).get().await()
            .documents.mapNotNull(::parse)
    }

    suspend fun decide(c: Context, request: SocialTeamRequest, approve: Boolean): Boolean {
        val team = CloudTeams.teamId(c).takeIf(String::isNotBlank) ?: return false
        val uid = CloudTeams.currentUid()
        if (uid.isBlank() || request.uid == uid) return false
        val ref = requests(team).document(request.id)
        return FirebaseFirestore.getInstance().runTransaction { tx ->
            val current = tx.get(ref)
            if (current.getString("state") != "pending") return@runTransaction false
            tx.update(ref, mapOf("state" to if (approve) "approved" else "rejected",
                "decidedBy" to uid, "decidedAt" to FieldValue.serverTimestamp()))
            true
        }.await()
    }

    private fun parse(row: com.google.firebase.firestore.DocumentSnapshot): SocialTeamRequest? {
        val kind = SocialRequestKind.entries.firstOrNull { it.key == row.getString("kind") } ?: return null
        return SocialTeamRequest(row.id, row.getString("uid") ?: return null,
            row.getString("device").orEmpty(), kind, row.getString("pkg").orEmpty(), row.getString("app").orEmpty(),
            row.getLong("old")?.toInt() ?: 0, row.getLong("minutes")?.toInt() ?: 0,
            row.getString("reason").orEmpty(), row.getString("state") ?: return null)
    }
}
