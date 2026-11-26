package hu.fokusz

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable internal fun TeamSocialRequestsPanel(team: CloudTeamView) {
    val c = LocalContext.current
    val scope = rememberCoroutineScope()
    var opened by remember(team.id) { mutableStateOf(false) }
    var requests by remember(team.id) { mutableStateOf<List<SocialTeamRequest>?>(null) }
    var error by remember(team.id) { mutableStateOf(false) }
    var refresh by remember(team.id) { mutableIntStateOf(0) }
    var actionBusy by remember(team.id) { mutableStateOf(false) }
    var status by remember(team.id) { mutableStateOf("") }
    var chosen by remember(team.id) { mutableStateOf<SocialUnlockApp?>(null) }
    var minutesText by remember(team.id) { mutableStateOf("5") }
    var reason by remember(team.id) { mutableStateOf("") }
    var confirmGuard by remember(team.id) { mutableStateOf(false) }
    var guarded by remember(team.id) { mutableStateOf(SocialTeamRequests.guarded(c)) }
    val appOptions by produceState(emptyList<SocialUnlockApp>(), opened, refresh) {
        if (opened) value = withContext(Dispatchers.IO) {
            SocialUnlockStore.apps(c).filter { SocialUnlockStore.selected(c, it.packageName) }
        }
    }
    val uid = CloudTeams.currentUid()
    LaunchedEffect(opened, team.id, refresh) {
        while (opened && team.id.isNotBlank()) {
            try {
                requests = SocialTeamRequests.latest(c)
                error = false
            } catch (ex: CancellationException) {
                throw ex
            } catch (_: Exception) {
                error = true
            }
            delay(10_000L)
        }
    }

    FeatureBox("Csapatengedélyek") {
        OutlinedButton(onClick = { opened = !opened }) {
            Text(if (opened) "Engedélykérések elrejtése" else "Pluszidő és engedélykérések")
        }
        if (!opened) return@FeatureBox
        if (error) Text("A csapatengedélyek most nem érhetők el.")
        if (requests == null && !error) Text("Kérelmek betöltése…")
        if (requests == null) return@FeatureBox

        if (guarded) Text("A csapat dönt a kijelölt appok zárolásának kikapcsolásáról és napi korlátjának emeléséről.")
        else OutlinedButton(enabled = !error, onClick = { confirmGuard = true }) { Text("Csapatvédelem bekapcsolása") }

        if (appOptions.isEmpty()) {
            Text("Pluszidőt a Képernyőidő fülön kijelölt közösségi apphoz kérhetsz.")
        } else {
            Text("Melyik apphoz kérsz pluszidőt?", fontWeight = FontWeight.Bold)
            for (app in appOptions) FilterChip(chosen?.packageName == app.packageName,
                onClick = { chosen = app }, label = { Text(app.name) })
            val minutes = minutesText.toIntOrNull()?.takeIf { it in 1..60 }
            OutlinedTextField(minutesText, { minutesText = it.filter(Char::isDigit).take(2) },
                label = { Text("Kért idő percben") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
            OutlinedTextField(reason, { reason = it.take(160) }, label = { Text("Miért kéred?") },
                modifier = Modifier.fillMaxWidth())
            fun requestTime(kind: SocialRequestKind, amount: Int, end: Int = 0) {
                chosen?.let { app ->
                    status = SocialTeamRequests.ask(c, kind, app, amount, reason, end)
                    if (status.startsWith("A kérelem függőben")) {
                        minutesText = "5"
                        reason = ""
                        refresh++
                    }
                }
            }
            val valid = chosen != null && reason.trim().length >= 3
            OutlinedButton(enabled = valid && minutes != null, onClick = {
                requestTime(SocialRequestKind.EXTRA, checkNotNull(minutes))
            }) { Text("Pluszidő kérése") }
            val scheduleEnd = FocusSchedules.endEpoch(c)
            if (scheduleEnd in 1..Int.MAX_VALUE.toLong()) {
                OutlinedButton(enabled = valid && minutes != null, onClick = {
                    requestTime(SocialRequestKind.SCHEDULE, checkNotNull(minutes), scheduleEnd.toInt())
                }) { Text("Egyszeri fókuszsáv-kivételt kérek") }
            }
            if (!team.cached && team.pool == 0 && team.bank == 0) {
                OutlinedButton(enabled = valid, onClick = { requestTime(SocialRequestKind.EMPTY, 15) }) {
                    Text("15 perc hosszabbítást kérek")
                }
            }
        }
        if (status.isNotBlank()) Text(status)
        for (request in requests.orEmpty()) key(request.id) {
            val name = team.members.firstOrNull { it.uid == request.uid }?.name ?: "Korábbi tag"
            val detail = when (request.kind) {
                SocialRequestKind.REMOVE -> "Zárolás kikapcsolása"
                SocialRequestKind.LIMIT -> {
                    val proposed = if (request.minutes == 0) "korlát nélkül" else "${request.minutes} perc"
                    "Jelenlegi: ${request.old} perc · Kért: $proposed"
                }
                else -> "${request.minutes} perc · ${request.reason}"
            }
            Text("$name · ${request.app} · ${request.kind.label}", fontWeight = FontWeight.SemiBold)
            Text(detail)
            val state = when (request.state) {
                "approved" -> "Jóváhagyva"
                "rejected" -> "Elutasítva"
                else -> "Függőben"
            }
            Text(state)
            TeamChatPanel(team, request.id)
            if (request.state == "pending" && uid.isNotBlank() && request.uid != uid) {
                fun decide(approved: Boolean) {
                    actionBusy = true
                    scope.launch {
                        try {
                            status = if (SocialTeamRequests.decide(c, request, approved)) "" else "Erről a kérésről már döntöttek."
                            refresh++
                        } catch (exception: CancellationException) {
                            throw exception
                        } catch (_: Exception) {
                            status = "A döntést most nem sikerült elküldeni."
                        } finally { actionBusy = false }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedButton(onClick = { decide(true) }, enabled = !actionBusy) { Text("Engedélyezem") }
                    OutlinedButton(onClick = { decide(false) }, enabled = !actionBusy) { Text("Elutasítom") }
                }
            }
        }
    }
    if (confirmGuard) AlertDialog(onDismissRequest = { confirmGuard = false },
        title = { Text("Bekapcsolod a csapatvédelmet?") },
        text = { Text("Ebben a csapatban másik tag jóváhagyása kell a kijelölt appok korlátjának emeléséhez vagy a zárolás kikapcsolásához.") },
        confirmButton = {
            OutlinedButton(onClick = {
                SocialTeamRequests.setGuarded(c, true)
                guarded = true
                confirmGuard = false
            }) { Text("Bekapcsolás") }
        },
        dismissButton = { OutlinedButton(onClick = { confirmGuard = false }) { Text("Mégse") } })
}
