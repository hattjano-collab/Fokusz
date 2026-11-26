package hu.fokusz

import android.content.Context
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
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

internal data class TeamSocialEvent(
    val uid: String,
    val kind: String,
    val pkg: String,
    val app: String,
    val minutes: Int,
    val reason: SocialUnlockReason?,
    val submittedAt: Instant?,
)

internal object SocialTeamEvents {
    private val background = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val syncing = AtomicBoolean(false)
    @Volatile private var lastAttempt = -30_000L

    private fun prefs(c: Context) = c.getSharedPreferences("fokusz_team_social_events", Context.MODE_PRIVATE)
    fun sharing(c: Context) = prefs(c).getBoolean("shareEvents", false)

    @Synchronized fun setSharing(c: Context, share: Boolean) {
        val edit = prefs(c).edit().putBoolean("shareEvents", share)
        if (!share) edit.remove("outbox")
        edit.apply()
    }

    fun onGrant(c: Context, grant: SocialUnlockGrant) {
        enqueue(c, grant.id, "unlock", grant.packageName, grant.appName, grant.minutes, grant.reason.id)
    }

    fun onRest(c: Context) = enqueue(c, UUID.randomUUID().toString(), "rest", "", "", 1440, "")

    @Synchronized private fun enqueue(c: Context, id: String, kind: String, pkg: String, app: String,
                                      minutes: Int, reason: String) {
        if (!sharing(c)) return
        val team = CloudTeams.teamId(c).takeIf(String::isNotBlank) ?: return
        val uid = CloudTeams.currentUid().takeIf(String::isNotBlank) ?: return
        val existing = outbox(c)
        val payload = JSONObject().put("id", "${uid}_$id").put("uid", uid).put("team", team)
            .put("kind", kind).put("pkg", pkg).put("app", app.take(80)).put("minutes", minutes).put("reason", reason)
        if (!prefs(c).edit().putString("outbox", JSONArray(existing + payload).toString()).commit()) return
        pulse(c)
    }

    private fun outbox(c: Context): List<JSONObject> = runCatching {
        val pending = JSONArray(prefs(c).getString("outbox", "[]"))
        (0 until pending.length()).mapNotNull(pending::optJSONObject)
    }.getOrDefault(emptyList())

    fun pulse(c: Context) {
        val now = SystemClock.elapsedRealtime()
        if (!sharing(c) || now - lastAttempt < 30_000L || !syncing.compareAndSet(false, true)) return
        lastAttempt = now
        val app = c.applicationContext
        background.launch {
            try { flush(app) }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { /* A pending event will be retried on the next pulse. */ }
            finally { syncing.set(false) }
        }
    }

    private suspend fun flush(c: Context) {
        val team = CloudTeams.teamId(c)
        val uid = CloudTeams.currentUid()
        if (team.isBlank() || uid.isBlank()) return
        val database = FirebaseFirestore.getInstance()
        val collection = database.collection("teams").document(team).collection("socialEvents")
        for (row in outbox(c).take(20)) {
            if (!sharing(c) || CloudTeams.teamId(c) != team) return
            val id = row.getString("id")
            if (row.getString("team") == team && row.getString("uid") == uid) {
                val event = collection.document(id)
                database.runTransaction { tx ->
                    if (!tx.get(event).exists()) {
                        tx.set(event, mapOf("uid" to uid, "kind" to row.getString("kind"),
                            "pkg" to row.getString("pkg"), "app" to row.getString("app"),
                            "minutes" to row.getInt("minutes"), "reason" to row.getString("reason"),
                            "createdAt" to FieldValue.serverTimestamp()))
                    }
                }.await()
            }
            synchronized(this) {
                val remaining = outbox(c).filterNot { it.optString("id") == id }
                prefs(c).edit().putString("outbox", JSONArray(remaining).toString()).commit()
            }
        }
    }

    suspend fun latest(c: Context): List<TeamSocialEvent> = withContext(Dispatchers.IO) {
        val team = CloudTeams.teamId(c).takeIf(String::isNotBlank) ?: return@withContext emptyList()
        FirebaseFirestore.getInstance().collection("teams").document(team).collection("socialEvents")
            .orderBy("createdAt", Query.Direction.DESCENDING).limit(75).get(Source.DEFAULT).await().documents
            .mapNotNull { row ->
                val uid = row.getString("uid") ?: return@mapNotNull null
                val kind = row.getString("kind") ?: return@mapNotNull null
                TeamSocialEvent(uid, kind, row.getString("pkg").orEmpty(), row.getString("app").orEmpty(),
                    row.getLong("minutes")?.toInt() ?: 0,
                    row.getString("reason")?.let { runCatching { SocialUnlockReason.read(it) }.getOrNull() },
                    row.getTimestamp("createdAt")?.toDate()?.toInstant())
            }
    }
}
