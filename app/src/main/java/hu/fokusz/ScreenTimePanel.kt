package hu.fokusz

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.delay
import kotlin.math.abs

private val timeAccent = Color(0xFF7BE7C1)
private val timeMuted = Color(0xFFA7B4CC)
private val timeDecrease = Color(0xFFFFC78D)
private val statementTime = DateTimeFormatter.ofPattern("MM.dd. HH:mm")

@Composable fun ScreenTimePanel() {
    val ctx = LocalContext.current
    var section by rememberSaveable { mutableStateOf("") }
    var goals by remember { mutableStateOf(Store.goals(ctx)) }
    var history by remember { mutableStateOf(emptyList<ScreenTimeLedger.Entry>()) }
    var expanded by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(section, expanded) {
        while (section.isNotEmpty()) {
            if (section == "goals") goals = Store.goals(ctx)
            else history = ScreenTimeLedger.recent(ctx, if (expanded) 100 else 16)
            delay(2000L)
        }
    }

    FeatureBox("Képernyőidő kezelése") {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { section = if (section == "goals") "" else "goals" },
                modifier = Modifier.weight(1f)) { Text("Célperselyek", color = timeAccent) }
            OutlinedButton(onClick = { section = if (section == "history") "" else "history" },
                modifier = Modifier.weight(1f)) { Text("Kivonat", color = timeAccent) }
        }
        if (section == "goals") GoalsPanel(goals) { goals = Store.goals(ctx) }
        if (section == "history") {
            Text("A telefonon rögzített időmozgások az utolsó 30 napból.", color = timeMuted, fontSize = 13.sp)
            if (history.isEmpty()) Text("Még nincs időmozgás.", color = timeMuted)
            for (entry in history.take(if (expanded) 100 else 15)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text(entry.reason, color = Color.White, fontSize = 14.sp)
                        Text(Instant.ofEpochMilli(entry.at).atZone(ZoneId.systemDefault()).format(statementTime),
                            color = timeMuted, fontSize = 11.sp)
                    }
                    val value = abs(entry.seconds).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
                    Text((if (entry.seconds > 0) "+" else "−") + duration(value),
                        color = if (entry.seconds > 0) timeAccent else timeDecrease,
                        fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                }
            }
            if (expanded || history.size > 15) {
                OutlinedButton(onClick = { expanded = !expanded }) {
                    Text(if (expanded) "Kevesebb tétel" else "Korábbi tételek", color = timeAccent)
                }
            }
        }
    }
}

@Composable private fun GoalsPanel(goals: List<Store.Goal>, refresh: () -> Unit) {
    val ctx = LocalContext.current
    var creating by rememberSaveable { mutableStateOf(false) }
    var title by rememberSaveable { mutableStateOf("") }
    var minutes by rememberSaveable { mutableStateOf("") }
    var perReward by rememberSaveable { mutableStateOf("0") }
    var note by rememberSaveable { mutableStateOf("") }
    Text("A tanulási jutalmakból a megadott percek a perselyek sorrendjében gyűlnek. A megtakarítás másnap is megmarad.",
        color = timeMuted, fontSize = 13.sp)
    for (goal in goals) key(goal.id) {
        var allocation by rememberSaveable(goal.perReward) { mutableStateOf((goal.perReward / 60).toString()) }
        Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Text(goal.title, color = Color.White, fontWeight = FontWeight.Bold)
            Text("${duration(goal.saved)} / ${duration(goal.target)}", color = timeMuted, fontSize = 13.sp)
            LinearProgressIndicator(progress = { (goal.saved.toFloat() / goal.target).coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth(), color = timeAccent)
            GoalMinutesField(allocation, "Perc tanulási jutalmanként", { allocation = it })
            val amount = goalSeconds(allocation)
            OutlinedButton(enabled = amount != null && amount != goal.perReward && amount <= goal.target,
                onClick = {
                    note = if (Store.setGoalAllocation(ctx, goal.id, amount!!)) "A beállítás mentve."
                    else "A beállítást nem sikerült menteni."
                    refresh()
                }) { Text("Beállítás mentése", color = timeAccent) }
            OutlinedButton(onClick = {
                note = if (Store.closeGoal(ctx, goal.id)) {
                    if (goal.saved > 0) "A megtakarítás a mai keretbe került." else "Az üres persely lezárva."
                } else "A perselyt most nem sikerült lezárni."
                refresh()
            }) { Text(if (goal.saved > 0) "Kivét és lezárás" else "Üres persely lezárása", color = timeAccent) }
        }
    }
    if (creating) {
        OutlinedTextField(value = title, onValueChange = { title = it.take(80) },
            modifier = Modifier.fillMaxWidth(), singleLine = true, label = { Text("Cél neve") })
        GoalMinutesField(minutes, "Cél percben", { minutes = it })
        GoalMinutesField(perReward, "Perc tanulási jutalmanként", { perReward = it })
        val target = goalSeconds(minutes)
        val allocation = goalSeconds(perReward)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(enabled = title.isNotBlank() && target != null && target > 0 &&
                allocation != null && allocation <= target, onClick = {
                if (Store.createGoal(ctx, title, target!!, allocation!!)) {
                    refresh()
                    creating = false
                    title = ""
                    minutes = ""
                    perReward = "0"
                    note = "A célpersely elkészült."
                } else note = "A célperselyt nem sikerült menteni."
            }) { Text("Létrehozás") }
            OutlinedButton(onClick = { creating = false }) { Text("Mégse", color = timeAccent) }
        }
    } else {
        Button(onClick = { creating = true; note = "" }) { Text("Új célpersely") }
    }
    if (note.isNotBlank()) Text(note, color = timeMuted, fontSize = 13.sp)
}

@Composable private fun GoalMinutesField(value: String, label: String, onChange: (String) -> Unit) {
    OutlinedTextField(value = value, onValueChange = { onChange(it.filter(Char::isDigit).take(8)) },
        modifier = Modifier.fillMaxWidth(), singleLine = true, label = { Text(label) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
}

private fun goalSeconds(value: String): Int? =
    value.toIntOrNull()?.takeIf { it in 0..Store.MAX_SAFE_SECONDS / 60 }?.times(60)
