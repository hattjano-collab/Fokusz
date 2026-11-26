package hu.fokusz

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.MetadataChanges
import com.google.firebase.firestore.Query

internal enum class TeamNotice(val key: String, val label: String, val collection: String) {
    UNLOCK("unlock", "Feloldások és pihenőnapok", "socialEvents"),
    REQUEST("request", "Új engedélykérések", "socialRequests"),
    DECISION("decision", "Döntések a kérelmeimről", "socialRequests"),
    CHAT("chat", "Csapatbeszélgetés", "chat"),
    FRIEND("friend", "Barátkérelmek", "friendRequests")
}

internal object TeamNotifications {
    private const val CHANNEL = "fokusz-team-activity"
    private fun prefs(c: Context) = c.getSharedPreferences("fokusz_team_notices", Context.MODE_PRIVATE)
    private var subscribed = ""
    private var listeners = emptyList<ListenerRegistration>()

    fun enabled(c: Context, type: TeamNotice) = prefs(c).getBoolean(type.key, false)
    fun setEnabled(c: Context, type: TeamNotice, enabled: Boolean) =
        prefs(c).edit().putBoolean(type.key, enabled).apply()

    @Synchronized fun watch(c: Context) {
        val app = c.applicationContext
        val team = CloudTeams.teamId(c)
        val enabled = TeamNotice.entries.filter { enabled(c, it) && (team.isNotBlank() || it == TeamNotice.FRIEND) }
        val uid = if (enabled.isNotEmpty()) CloudTeams.currentUid() else ""
        val key = if (uid.isBlank() || enabled.isEmpty() ||
            (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(c, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED)) "" else "${team}_${uid}_${enabled.joinToString { it.key }}"
        if (key == subscribed) return
        listeners.forEach(ListenerRegistration::remove)
        listeners = emptyList()
        subscribed = key
        if (key.isEmpty()) return

        val database = FirebaseFirestore.getInstance()
        listeners = enabled.map { type ->
            val keyForSeen = "seen_${team}_${uid}_${type.key}"
            val query = if (type == TeamNotice.FRIEND) {
                database.collection(type.collection).whereEqualTo("to", uid)
            } else database.collection("teams").document(team).collection(type.collection)
                .orderBy("createdAt", Query.Direction.DESCENDING)
            query.limit(40).addSnapshotListener(MetadataChanges.INCLUDE) { snap, error ->
                    if (error != null || snap == null || snap.metadata.isFromCache ||
                        (type != TeamNotice.FRIEND && CloudTeams.teamId(app) != team) || !enabled(app, type)) return@addSnapshotListener
                    val storage = prefs(app)
                    val seen = storage.getStringSet(keyForSeen, emptySet()).orEmpty()
                    val selected = snap.documents.mapNotNull { document ->
                        val author = document.getString(if (type == TeamNotice.FRIEND) "from" else "uid").orEmpty()
                        if (author.isBlank() || TeamCommunity.hidden(app, author)) return@mapNotNull null
                        when (type) {
                            TeamNotice.UNLOCK -> if (author == uid) null
                                else document.id to "Új közösségi feloldás vagy pihenőnap a csapatban."
                            TeamNotice.REQUEST -> if (author == uid || document.getString("state") != "pending") null
                                else document.id to "Egy csapattag a döntésedre vár."
                            TeamNotice.DECISION -> if (author != uid || document.getString("state") == "pending") null
                                else document.id to "Döntés született a kérelmedről."
                            TeamNotice.CHAT -> if (author == uid) null
                                else document.id to "Új üzenet érkezett a csapatodban."
                            TeamNotice.FRIEND -> if (document.getString("state") != "pending") null
                                else document.id to "Új ismerőskérés érkezett."
                        }
                    }
                    if (storage.contains(keyForSeen)) {
                        selected.filterNot { it.first in seen }.take(3).forEach { (id, message) ->
                            notify(app, type, id, message)
                        }
                    }
                    val updated = (selected.map { it.first } + seen).distinct().take(120).toSet()
                    storage.edit().putStringSet(keyForSeen, updated).apply()
                }
        }
    }

    private fun notify(c: Context, type: TeamNotice, id: String, message: String) = runCatching {
        val manager = c.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "Csapatesemények", NotificationManager.IMPORTANCE_DEFAULT))
        val handle = (type.key + id).hashCode()
        val intent = Intent(c, MainActivity::class.java).putExtra("focus_tab", 9)
        val pending = PendingIntent.getActivity(c, handle, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        manager.notify(handle, Notification.Builder(c, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Fókusz · ${type.label}")
            .setContentText(message)
            .setContentIntent(pending)
            .setAutoCancel(true).build())
    }
}

@Composable internal fun TeamNotificationPanel() {
    val c = LocalContext.current
    val requestPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        TeamNotifications.watch(c)
    }
    var expanded by remember { mutableStateOf(false) }
    var checked by remember { mutableStateOf(TeamNotice.entries.filter { TeamNotifications.enabled(c, it) }.toSet()) }
    FeatureBox("Csapatértesítések") {
        OutlinedButton(onClick = { expanded = !expanded }) { Text(if (expanded) "Értesítések elrejtése" else "Értesítések beállítása") }
        if (expanded) for (item in TeamNotice.entries) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(item.label, Modifier.weight(1f))
                Switch(item in checked, { isChecked ->
                    checked = if (isChecked) checked + item else checked - item
                    TeamNotifications.setEnabled(c, item, isChecked)
                    if (isChecked && Build.VERSION.SDK_INT >= 33 &&
                        ContextCompat.checkSelfPermission(c, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                        requestPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                    } else TeamNotifications.watch(c)
                })
            }
        }
    }
}
