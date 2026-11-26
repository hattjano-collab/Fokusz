package hu.fokusz

import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.LocalDate

@Composable
internal fun PersonalBossPanel() {
    val c = LocalContext.current
    var edit by rememberSaveable { mutableStateOf(false) }
    var update by remember { mutableIntStateOf(0) }
    var message by rememberSaveable { mutableStateOf("") }
    LaunchedEffect(Unit) { while (true) { delay(1000); update++ } }
    val goals = remember(update) { BossQuest.goals(c) }
    val claimed = remember(update) { BossQuest.claimed(c) }
    val lines = remember(update, goals) { goals.map { BossLine(it, BossProgress.count(c, it.activity)) } }
    Text("A hét három céljáért 45 perc jár a saját képernyőidődhöz.")
    Text("A sakkpartikat a kapcsolt fiók, a gyakorlatokat a kamera, az időzített questeket az időzítő számolja.")
    if (claimed) Text("Az e heti 45 percet már jóváírtuk.", fontWeight = FontWeight.Bold)
    BossTimer()
    OutlinedButton(onClick = { edit = true }) { Text(if (goals.isEmpty()) "Három cél kiválasztása" else "Heti célok szerkesztése") }
    BossLines(lines, canStart = !claimed)
    if (lines.size == 3 && !claimed) {
        Button(enabled = lines.all { it.done >= it.goal.target }, onClick = {
            message = if (BossQuest.claim(c)) "Jóváírva: 45 perc." else "A jutalmat most nem sikerült jóváírni."
            update++
        }) { Text("45 perc felvétele") }
    }
    if (message.isNotEmpty()) Text(message)
    if (edit) BossEditor(goals, onDismiss = { edit = false }) {
        if (BossQuest.save(c, it)) {
            edit = false
            update++
        } else message = "A célokat nem sikerült menteni."
    }
}

@Composable
internal fun BossLines(lines: List<BossLine>, canStart: Boolean) {
    val c = LocalContext.current
    var notice by remember { mutableStateOf("") }
    for (line in lines) {
        val (activity, target) = line.goal
        Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(activity.title, Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                Text("${minOf(line.done, target)} / $target ${activity.unit.label}")
            }
            LinearProgressIndicator(progress = { (line.done.toFloat() / target).coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth())
            if (canStart && activity.unit == BossUnit.MINUTES && line.done < target) {
                val minutes = minOf(target - line.done, activity.session.coerceIn(1, 1440))
                TextButton(onClick = {
                    notice = when {
                        !FocusService.isRunning || !UsageGuard.allowed(c) || !Settings.canDrawOverlays(c) ->
                            "A Képernyőidő fülön indítsd el a háttérvédelmet."
                        UserQuests.startBoss(c, activity, minutes) -> ""
                        else -> "Előbb fejezd be a futó időzítőt."
                    }
                }) { Text("Indítás · $minutes perc") }
            }
        }
    }
    if (notice.isNotEmpty()) Text(notice)
}

@Composable
internal fun BossTimer(onFinished: () -> Unit = {}) {
    val c = LocalContext.current
    var current by remember { mutableStateOf(UserQuests.active(c)) }
    LaunchedEffect(Unit) { while (true) { current = UserQuests.active(c); delay(500L) } }
    val run = current?.takeIf { it.bossActivity != null } ?: return
    Text(run.quest.title + if (run.left > 0) " · ${clock(run.left)} van hátra" else " · Kész")
    if (run.left == 0) Button(onClick = {
        if (UserQuests.claim(c) != null) {
            current = UserQuests.active(c)
            onFinished()
        }
    }) { Text("Haladás rögzítése") }
    else if (!FocusService.isRunning) Text("A hátralévő idő a háttérvédelem elindítása után folytatódik.")
}

@Composable
internal fun BossEditor(initial: List<BossGoal>, onDismiss: () -> Unit, onSave: (List<BossGoal>) -> Unit) {
    val c = LocalContext.current
    var choices by remember { mutableStateOf(BossActivities.all(c)) }
    var keys by remember {
        mutableStateOf(List(3) { index -> initial.getOrNull(index)?.activity?.let { saved ->
            choices.find { it.key == saved.key } ?: saved
        } })
    }
    var quantities by remember { mutableStateOf(List(3) { initial.getOrNull(it)?.target?.toString() ?: "" }) }
    var picker by remember { mutableIntStateOf(-1) }
    var creating by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    val values = keys.mapIndexedNotNull { i, activity ->
        val amount = quantities[i].toIntOrNull()
        if (activity != null && amount != null) BossGoal(activity, amount) else null
    }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Három heti cél") }, text = {
        Column(Modifier.heightIn(max = 465.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            for (i in 0..2) {
                val item = keys[i]
                OutlinedButton(onClick = { query = ""; picker = i }, modifier = Modifier.fillMaxWidth()) {
                    Text(item?.title ?: "${i + 1}. cél kiválasztása")
                }
                if (item != null) OutlinedTextField(value = quantities[i], onValueChange = { input ->
                    quantities = quantities.mapIndexed { index, old ->
                        if (index == i) input.filter(Char::isDigit).take(6) else old
                    }
                }, label = { Text("Heti mennyiség (${item.unit.label})") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth())
            }
            TextButton(onClick = { creating = true }) { Text("Új saját quest hozzáadása") }
        }
    }, confirmButton = {
        TextButton(enabled = BossQuest.valid(values), onClick = { onSave(values) }) { Text("Mentés") }
    }, dismissButton = { TextButton(onClick = onDismiss) { Text("Mégse") } })

    if (picker >= 0) {
        val slot = picker
        AlertDialog(onDismissRequest = { picker = -1 }, title = { Text("Válassz questet") }, text = {
            Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                OutlinedTextField(query, { query = it }, label = { Text("Keresés") }, singleLine = true)
                Column(Modifier.heightIn(max = 350.dp).verticalScroll(rememberScrollState())) {
                    for (item in choices.filter { it.title.contains(query.trim(), true) }) {
                        TextButton(enabled = keys.withIndex().none { it.index != slot && it.value?.key == item.key },
                            onClick = {
                                keys = keys.mapIndexed { index, old -> if (index == slot) item else old }
                                quantities = quantities.mapIndexed { index, old -> if (index == slot) "" else old }
                                picker = -1
                            }) { Text("${item.title} · ${item.unit.label}") }
                    }
                }
            }
        }, confirmButton = { TextButton(onClick = { picker = -1 }) { Text("Bezárás") } })
    }

    if (creating) {
        var title by remember { mutableStateOf("") }
        var minutes by remember { mutableStateOf("15") }
        var failed by remember { mutableStateOf(false) }
        val length = minutes.toIntOrNull()
        AlertDialog(onDismissRequest = { creating = false }, title = { Text("Új saját quest") }, text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(title, { title = it.take(80) }, label = { Text("Feladat neve") }, singleLine = true)
                OutlinedTextField(minutes, { minutes = it.filter(Char::isDigit).take(4) },
                    label = { Text("Egy alkalom hossza percben") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                Text("A heti célt külön adod meg. A quest a Saját questek listába is bekerül.")
                if (failed) Text("A mentés nem sikerült.")
            }
        }, confirmButton = {
            TextButton(enabled = title.trim().length >= 3 && length != null && length in 1..1440, onClick = {
                if (UserQuests.save(c, null, title, (length ?: 0) * 60, 0)) {
                    choices = BossActivities.all(c)
                    creating = false
                    picker = keys.indexOfFirst { it == null }
                } else failed = true
            }) { Text("Hozzáadás") }
        }, dismissButton = { TextButton(onClick = { creating = false }) { Text("Mégse") } })
    }
}

@Composable
internal fun WeeklyTeamBossPanel(team: CloudTeamView) {
    val c = LocalContext.current
    val scope = rememberCoroutineScope()
    var edit by rememberSaveable(team.id) { mutableStateOf(false) }
    var busy by remember(team.id) { mutableStateOf(false) }
    var message by rememberSaveable(team.id) { mutableStateOf("") }
    val round = CloudTeams.weeklyBoss
    val uid = CloudTeams.currentUid()
    var week by remember { mutableStateOf(CloudTeams.teamBossWeek()) }
    LaunchedEffect(team.id) { while (true) { week = CloudTeams.teamBossWeek(); delay(30_000L) } }
    val active = round.week == week && round.teamId == team.id
    val plans = if (active) round.plans else emptyMap()
    val own = plans[uid].orEmpty()
    val claimed = active && round.claimed
    val ready = team.members.isNotEmpty() && team.members.all { person ->
        plans[person.uid]?.let { lines -> lines.size == 3 && lines.all { it.done >= it.goal.target } } == true
    }

    Text("Minden tag három heti célt ad meg. A közös jutalom 30 perc tagonként, összesen ${duration(team.members.size * 1800)}.")
    if (round.allowed != true) {
        if (round.allowed == false) Text("Az új heti célokhoz a 3.2-es Firebase-szabályokat kell közzétenni.")
        TextButton(onClick = { CloudTeams.refreshWeeklyBoss(c) }) { Text("Kapcsolat frissítése") }
    }
    if (claimed) Text("A csapat az e heti jutalmat már felvette.", fontWeight = FontWeight.Bold)
    BossTimer { scope.launch { CloudTeams.syncBossProgress(c) } }
    OutlinedButton(enabled = !busy && !claimed && round.allowed == true && uid.isNotBlank(),
        onClick = { edit = true }) { Text(if (own.isEmpty()) "Saját három cél megadása" else "Saját célok szerkesztése") }
    for (member in team.members) {
        Text(member.name, fontWeight = FontWeight.Bold)
        val lines = plans[member.uid].orEmpty()
        if (lines.isEmpty()) Text("Még nem adott meg heti célokat.")
        else {
            val display = if (member.uid == uid) lines.map { line ->
                BossLine(line.goal, maxOf(line.done, BossProgress.count(c, line.goal.activity, week)))
            } else lines
            BossLines(display, canStart = member.uid == uid && !claimed)
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        OutlinedButton(enabled = !busy && own.isNotEmpty() && !claimed, onClick = {
            busy = true
            scope.launch {
                try { message = CloudTeams.syncBossProgress(c) ?: "Haladás szinkronizálva." }
                finally { busy = false }
            }
        }) { Text("Haladás frissítése") }
        Button(enabled = ready && !claimed && !busy, onClick = {
            busy = true
            scope.launch {
                try { message = CloudTeams.claimBoss(c) }
                finally { busy = false }
            }
        }) { Text("Jutalom felvétele") }
    }
    if (message.isNotEmpty()) Text(message)
    if (edit) BossEditor(own.map { it.goal }, onDismiss = { edit = false }) { goals ->
        busy = true
        edit = false
        scope.launch {
            try { message = CloudTeams.saveWeeklyBoss(c, goals) }
            finally { busy = false }
        }
    }
}
