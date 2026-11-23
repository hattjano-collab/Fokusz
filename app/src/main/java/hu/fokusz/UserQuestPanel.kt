package hu.fokusz

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.padding
import kotlinx.coroutines.delay

@Composable internal fun UserQuestPanel(serviceReady: Boolean) {
    val context = LocalContext.current
    var expanded by rememberSaveable { mutableStateOf(false) }
    var running by remember { mutableStateOf(UserQuests.active(context)) }
    var quests by remember { mutableStateOf(UserQuests.all(context)) }
    var editor by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<UserQuest?>(null) }
    var delete by remember { mutableStateOf<UserQuest?>(null) }
    var cancel by remember { mutableStateOf(false) }
    var message by rememberSaveable { mutableStateOf("") }
    LaunchedEffect(Unit) {
        while (true) {
            running = UserQuests.active(context)
            delay(500L)
        }
    }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(15.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Saját questek", style = MaterialTheme.typography.titleMedium)
            running?.let { run ->
                Text(run.quest.title, style = MaterialTheme.typography.titleMedium)
                if (run.left > 0) {
                    Text("Hátra: ${clock(run.left)}" + if (run.bossActivity != null) " · Heti Boss-cél"
                        else " · Jutalom: ${duration(run.quest.reward)}")
                    if (!serviceReady) Text("Az óra a háttérvédelem elindítása után folytatódik.")
                    TextButton(onClick = { cancel = true }) { Text("Quest megszakítása") }
                } else Button(onClick = {
                    val credited = UserQuests.claim(context)
                    message = if (credited == null) "A jutalom mentése nem sikerült."
                        else "Teljesítve. Jóváírva: ${duration(credited)}."
                    running = UserQuests.active(context)
                }) { Text(if (run.bossActivity != null) "Haladás rögzítése" else "Jutalom felvétele") }
            }
            if (message.isNotBlank()) Text(message)
            OutlinedButton(onClick = { expanded = !expanded }) {
                Text(if (expanded) "Saját questek elrejtése" else "Saját questek megnyitása")
            }
            if (expanded) {
                Text("A saját quest a Fókuszmód alkalmazásszabályait használja.")
                if (!serviceReady) Text("A kezdéshez kapcsold be a háttérvédelmet a Képernyőidő fülön.")
                Button(onClick = { editing = null; editor = true }) { Text("Új saját quest") }
                for (quest in quests) {
                    Text(quest.title, style = MaterialTheme.typography.titleSmall)
                    Text("Időtartam: ${duration(quest.seconds)} · Jutalom: ${duration(quest.reward)}")
                    Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                        OutlinedButton(enabled = serviceReady && running == null, onClick = {
                            message = if (UserQuests.start(context, quest.id)) "" else "Előbb fejezd be a futó időzítőt."
                            running = UserQuests.active(context)
                        }) { Text("Indítás") }
                        TextButton(onClick = { editing = quest; editor = true }) { Text("Szerkesztés") }
                        TextButton(onClick = { delete = quest }) { Text("Törlés") }
                    }
                }
            }
        }
    }
    if (editor) {
        var title by remember(editing) { mutableStateOf(editing?.title.orEmpty()) }
        var minutes by remember(editing) { mutableStateOf(editing?.let { (it.seconds / 60).toString() } ?: "15") }
        var reward by remember(editing) { mutableStateOf(editing?.let { (it.reward / 60).toString() } ?: "10") }
        val target = minutes.toIntOrNull()
        val requested = reward.toIntOrNull()
        val valid = target != null && target in 1..1440 && requested != null && requested in 0..target
        AlertDialog(onDismissRequest = { editor = false }, title = { Text("Saját quest") }, text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(title, { title = it.take(80) }, label = { Text("Név") }, singleLine = true)
                OutlinedTextField(minutes, { minutes = it.filter(Char::isDigit).take(4) },
                    label = { Text("Időtartam percben") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                OutlinedTextField(reward, { reward = it.filter(Char::isDigit).take(4) },
                    label = { Text("Jutalom percben") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    supportingText = { Text("A jutalom legfeljebb az időtartammal egyezhet meg.") })
            }
        }, confirmButton = {
            TextButton(enabled = valid && title.trim().length >= 3, onClick = {
                if (UserQuests.save(context, editing?.id, title, (target ?: 0) * 60, (requested ?: 0) * 60)) {
                    quests = UserQuests.all(context)
                    editor = false
                } else message = "A mentés nem sikerült."
            }) { Text("Mentés") }
        }, dismissButton = { TextButton(onClick = { editor = false }) { Text("Mégse") } })
    }
    delete?.let { selected ->
        AlertDialog(onDismissRequest = { delete = null }, title = { Text("Törlöd ezt a sablont?") },
            text = { Text(selected.title) }, confirmButton = {
                TextButton(onClick = {
                    UserQuests.remove(context, selected.id)
                    quests = UserQuests.all(context)
                    delete = null
                }) { Text("Törlés") }
            }, dismissButton = { TextButton(onClick = { delete = null }) { Text("Mégse") } })
    }
    if (cancel) AlertDialog(onDismissRequest = { cancel = false }, title = { Text("Megszakítod a questet?") },
        text = { Text("A megszakított questért nem jár jutalom.") }, confirmButton = {
            TextButton(onClick = { UserQuests.cancel(context); running = UserQuests.active(context); cancel = false }) {
                Text("Megszakítás")
            }
        }, dismissButton = { TextButton(onClick = { cancel = false }) { Text("Folytatom") } })
}
