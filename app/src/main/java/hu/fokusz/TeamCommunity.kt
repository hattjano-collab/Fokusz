package hu.fokusz

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.util.Base64
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import org.json.JSONObject
import java.text.Normalizer
import java.util.Locale
import java.util.UUID

internal data class PublicTeam(val id: String, val name: String, val owner: String)
internal data class TeamFriend(val id: String, val otherUid: String, val other: String, val incoming: Boolean, val state: String)

internal object TeamCommunity {
    private fun db() = FirebaseFirestore.getInstance()
    private fun prefs(c: Context) = c.getSharedPreferences("fokusz_community", Context.MODE_PRIVATE)
    fun hidden(c: Context, uid: String) = uid in prefs(c).getStringSet("muted", emptySet()).orEmpty()
    fun setHidden(c: Context, uid: String, hide: Boolean) {
        val p = prefs(c)
        val items = p.getStringSet("muted", emptySet()).orEmpty()
        p.edit().putStringSet("muted", if (hide) items + uid else items - uid).apply()
    }

    fun validName(value: String, size: IntRange): Boolean {
        val name = value.trim()
        val compared = Normalizer.normalize(name.lowercase(Locale.ROOT), Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "").split(Regex("[^a-z0-9]+"))
        return name.length in size && name.none { it.isISOControl() || it in "<>" } &&
            compared.none { it in setOf("kurva", "geci", "faszfej", "szarhazi", "buzi") }
    }

    suspend fun authenticate(): String {
        val auth = FirebaseAuth.getInstance()
        return auth.currentUser?.uid ?: checkNotNull(auth.signInAnonymously().await().user?.uid)
    }

    suspend fun setListed(c: Context, listed: Boolean) {
        val id = CloudTeams.teamId(c).takeIf(String::isNotBlank) ?: return
        require(CloudTeams.owner(c))
        val ref = db().collection("publicTeams").document(id)
        if (!listed) ref.delete().await() else {
            val name = CloudTeams.teamName(c)
            require(validName(name, 3..40))
            ref.set(mapOf("owner" to authenticate(), "name" to name, "updatedAt" to FieldValue.serverTimestamp())).await()
        }
    }

    suspend fun teams(): List<PublicTeam> {
        authenticate()
        return db().collection("publicTeams").limit(100).get().await().documents.mapNotNull { row ->
            val name = row.getString("name") ?: return@mapNotNull null
            val owner = row.getString("owner") ?: return@mapNotNull null
            PublicTeam(row.id, name, owner)
        }.sortedBy { it.name.lowercase(Locale.getDefault()) }
    }

    suspend fun join(c: Context, team: PublicTeam, nickname: String): String {
        require(validName(nickname, 2..24))
        val entry = JSONObject().put("v", 1).put("id", team.id).put("key", "public").put("name", team.name)
        val proof = Base64.encodeToString(entry.toString().toByteArray(), Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
        return CloudTeams.join(c, "fokusz-firebase:v1:$proof", nickname, false)
    }

    suspend fun register(name: String): String {
        require(validName(name, 2..24))
        val uid = authenticate()
        db().collection("friendProfiles").document(uid).set(mapOf("name" to name.trim())).await()
        return uid
    }

    suspend fun invite(code: String, name: String) {
        val from = register(name)
        val to = code.trim()
        require(from != to && to.matches(Regex("[A-Za-z0-9]{10,128}")))
        val ref = db().collection("friendRequests").document("${from}_$to")
        db().runTransaction { tx ->
            if (!tx.get(ref).exists()) {
                tx.set(ref, mapOf("from" to from, "to" to to, "state" to "pending",
                    "createdAt" to FieldValue.serverTimestamp()))
            }
        }.await()
    }

    suspend fun friends(c: Context): List<TeamFriend> {
        val uid = CloudTeams.currentUid().takeIf(String::isNotBlank) ?: return emptyList()
        val ref = db().collection("friendRequests")
        val all = ref.whereEqualTo("from", uid).limit(60).get().await().documents +
            ref.whereEqualTo("to", uid).limit(60).get().await().documents
        return all.mapNotNull { row ->
            val incoming = row.getString("to") == uid
            val other = row.getString(if (incoming) "from" else "to") ?: return@mapNotNull null
            if (hidden(c, other)) return@mapNotNull null
            val name = db().collection("friendProfiles").document(other).get().await().getString("name") ?: "Ismerős"
            TeamFriend(row.id, other, name, incoming, row.getString("state").orEmpty())
        }
    }

    suspend fun decideFriend(friend: TeamFriend, accept: Boolean) {
        val request = db().collection("friendRequests").document(friend.id)
        if (accept) request.update("state", "accepted").await() else request.delete().await()
    }

    suspend fun report(team: String, target: String, kind: String, reason: String) {
        val reporter = authenticate()
        require(kind in setOf("team", "person") && reason.trim().length in 5..180)
        db().collection("communityReports").document("${reporter}_${UUID.randomUUID()}")
            .set(mapOf("reporter" to reporter, "team" to team, "target" to target,
                "kind" to kind, "reason" to reason.trim(), "createdAt" to FieldValue.serverTimestamp())).await()
    }

    suspend fun reports(team: String): List<String> =
        db().collection("communityReports").whereEqualTo("team", team).limit(50).get().await().documents.map { report ->
            val target = report.getString("target").orEmpty()
            val reason = report.getString("reason").orEmpty()
            "$target · $reason"
        }
}

@Composable internal fun TeamCommunityPanel(team: CloudTeamView) {
    val c = LocalContext.current
    val scope = rememberCoroutineScope()
    var shown by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf(CloudTeams.nickname(c)) }
    var friends by remember { mutableStateOf(emptyList<TeamFriend>()) }
    var teams by remember { mutableStateOf(emptyList<PublicTeam>()) }
    var target by remember { mutableStateOf("") }
    var search by remember { mutableStateOf("") }

    var status by remember { mutableStateOf("") }
    var reports by remember { mutableStateOf(emptyList<String>()) }
    var reportTeam by remember { mutableStateOf<PublicTeam?>(null) }
    var reportReason by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }

    fun launch(action: suspend () -> Unit) {
        if (busy) return
        busy = true
        scope.launch {
            try { status = ""; action() }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { status = "A művelet most nem sikerült. Ellenőrizd az adatokat és a kapcsolatot." }
            finally { busy = false }
        }
    }
    LaunchedEffect(shown) {
        while (shown) {
            try { friends = TeamCommunity.friends(c) }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { /* The next refresh may reach the server. */ }
            delay(30_000L)
        }
    }
    FeatureBox("Barátok és nyilvános csapatok") {
        OutlinedButton(onClick = { shown = !shown }) { Text(if (shown) "Elrejtés" else "Barátok és csapatkereső") }
        if (!shown) return@FeatureBox
        OutlinedTextField(name, { name = it.take(24) }, singleLine = true, label = { Text("Beceneved") })
        val valid = TeamCommunity.validName(name, 2..24)
        OutlinedButton(enabled = valid && !busy, onClick = {
            launch {
                val code = TeamCommunity.register(name)
                c.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("Fókusz ismerőskód", code))
                status = "Az ismerőskódot a vágólapra másoltad."
                friends = TeamCommunity.friends(c)
            }
        }) { Text("Saját ismerőskód másolása") }
        OutlinedTextField(target, { target = it.take(128) }, singleLine = true, label = { Text("A barátod ismerőskódja") })
        OutlinedButton(enabled = valid && target.isNotBlank() && !busy, onClick = {
            launch {
                TeamCommunity.invite(target, name)
                status = "Az ismerőskérés függőben van."
                target = ""
                friends = TeamCommunity.friends(c)
            }
        }) { Text("Ismerőskérés küldése") }
        for (friend in friends) {
            Text("${friend.other} · ${if (friend.state == "accepted") "Ismerős" else if (friend.incoming) "Beérkező kérés" else "Függő kérés"}")
            Row {
                if (friend.incoming && friend.state == "pending") OutlinedButton(enabled = !busy, onClick = {
                    launch { TeamCommunity.decideFriend(friend, true); friends = TeamCommunity.friends(c) }
                }) { Text("Elfogadás") }
                OutlinedButton(enabled = !busy, onClick = {
                    launch { TeamCommunity.decideFriend(friend, false); friends = TeamCommunity.friends(c) }
                }) { Text(if (friend.state == "accepted") "Eltávolítás" else "Kérés törlése") }
            }
            OutlinedButton(onClick = {
                TeamCommunity.setHidden(c, friend.otherUid, true)
                friends = friends.filterNot { it.id == friend.id }
            }) { Text("Letiltás ezen a telefonon") }
        }
        if (CloudTeams.owner(c) && team.id.isNotBlank()) {
            OutlinedButton(enabled = !busy, onClick = {
                launch { TeamCommunity.setListed(c, true); status = "A csapat megjelenik a nyilvános csapatkeresőben." }
            }) { Text("Csapat megjelenítése nyilvánosan") }
            OutlinedButton(enabled = !busy, onClick = {
                launch { TeamCommunity.setListed(c, false); status = "A csapat privát. A QR-meghívó továbbra is használható." }
            }) { Text("Csapat privátra állítása") }
            OutlinedButton(enabled = !busy, onClick = { launch { reports = TeamCommunity.reports(team.id) } }) {
                Text("Csapatjelentések megnyitása")
            }
            for (report in reports) Text(report)
        }
        OutlinedButton(enabled = !busy, onClick = { launch { teams = TeamCommunity.teams() } }) { Text("Nyilvános csapatok keresése") }
        if (teams.isNotEmpty()) {
            OutlinedTextField(search, { search = it.take(40) }, singleLine = true, label = { Text("Szűrés név szerint") })
            for (item in teams.filter { it.name.contains(search.trim(), true) && !TeamCommunity.hidden(c, it.owner) }) {
                Text(item.name)
                if (!CloudTeams.active(c)) OutlinedButton(enabled = valid && !busy, onClick = {
                    launch { status = TeamCommunity.join(c, item, name) }
                }) { Text("Csatlakozás a csapathoz") }
                OutlinedButton(onClick = { reportTeam = item }) { Text("Jelentés") }
            }
        }
        reportTeam?.let { item ->
            Text("${item.name} jelentése a csapatgazdának")
            OutlinedTextField(reportReason, { reportReason = it.take(180) }, label = { Text("Mi történt?") },
                modifier = Modifier.fillMaxWidth())
            OutlinedButton(enabled = reportReason.trim().length >= 5 && !busy, onClick = {
                launch {
                    TeamCommunity.report(item.id, item.id, "team", reportReason)
                    reportTeam = null
                    reportReason = ""
                    status = "A jelentést elküldted a csapatgazdának."
                }
            }) { Text("Jelentés elküldése") }
            OutlinedButton(onClick = { reportTeam = null; reportReason = "" }) { Text("Mégse") }
        }
        if (status.isNotBlank()) Text(status)
    }
}

@Composable internal fun TeamModerationPanel(team: CloudTeamView) {
    val c = LocalContext.current
    val scope = rememberCoroutineScope()
    var open by remember(team.id) { mutableStateOf(false) }
    var revision by remember(team.id) { mutableIntStateOf(0) }
    var target by remember(team.id) { mutableStateOf<CloudMember?>(null) }
    var wording by remember(team.id) { mutableStateOf("") }
    var feedback by remember(team.id) { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    FeatureBox("Tagok tiltása és jelentése") {
        OutlinedButton(onClick = { open = !open }) { Text(if (open) "Bezárás" else "Tagok megjelenítése") }
        if (!open) return@FeatureBox
        val uid = CloudTeams.currentUid()
        for (member in team.members.filter { it.uid != uid }) key(member.uid) {
            Text(member.name)
            val hidden = remember(revision, member.uid) { TeamCommunity.hidden(c, member.uid) }
            OutlinedButton(onClick = {
                TeamCommunity.setHidden(c, member.uid, !hidden)
                revision++
            }) { Text(if (hidden) "Tiltás visszavonása ezen a telefonon" else "Letiltás ezen a telefonon") }
            OutlinedButton(onClick = { target = member; wording = "" }) { Text("Felhasználó jelentése") }
        }
        if (feedback.isNotBlank()) Text(feedback)
        target?.let { member ->
            OutlinedTextField(wording, { wording = it.take(180) }, label = { Text("Miért jelented ${member.name} felhasználót?") })
            OutlinedButton(enabled = wording.trim().length >= 5 && !busy, onClick = {
                busy = true
                scope.launch {
                    try {
                        TeamCommunity.report(team.id, member.uid, "person", wording)
                        target = null
                        wording = ""
                        feedback = "A jelentést elküldted a csapatgazdának."
                    } catch (e: CancellationException) { throw e }
                    catch (_: Exception) { feedback = "A jelentést most nem sikerült elküldeni." }
                    finally { busy = false }
                }
            }) { Text("Jelentés elküldése") }
            OutlinedButton(onClick = { target = null }) { Text("Mégse") }
        }
    }
}
