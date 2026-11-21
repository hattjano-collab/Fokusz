package hu.fokusz

import android.app.DatePickerDialog
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

private val dateFormat = DateTimeFormatter.ofPattern("yyyy. MMM d., EEE", Locale.forLanguageTag("hu-HU"))

@Composable
fun StudyToolsPanel(onQuestStarted: () -> Unit, openTimeTab: () -> Unit) {
    var section by rememberSaveable { mutableIntStateOf(0) }
    val stateHolder = rememberSaveableStateHolder()
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("Dolgozat", "Kvíz", "Sablonok").forEachIndexed { index, label ->
                FilterChip(selected = section == index, onClick = { section = index }, label = { Text(label) })
            }
        }
        stateHolder.SaveableStateProvider(section) {
            when (section) {
                0 -> ExamTools()
                1 -> QuizTools()
                2 -> TemplateTools(onQuestStarted, openTimeTab)
            }
        }
    }
}

@Composable
private fun ToolsCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            content()
        }
    }
}

@Composable
private fun DeleteDialog(title: String, onDismiss: () -> Unit, onDelete: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Törlöd ezt az elemet?") },
        text = { Text(title) },
        confirmButton = { TextButton(onClick = onDelete) { Text("Törlés") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Mégse") } }
    )
}

@Composable
private fun ExamTools() {
    val context = LocalContext.current
    var today by rememberSaveable { mutableStateOf(LocalDate.now().toString()) }
    LaunchedEffect(Unit) {
        while (true) {
            today = LocalDate.now().toString()
            delay(30_000)
        }
    }
    val date = LocalDate.parse(today)
    var editing by rememberSaveable { mutableStateOf(false) }
    var title by rememberSaveable { mutableStateOf("") }
    var examDate by rememberSaveable { mutableStateOf(today) }
    var topics by rememberSaveable { mutableStateOf("") }
    var error by rememberSaveable { mutableStateOf("") }
    var expanded by rememberSaveable { mutableStateOf<String?>(null) }
    var deleteId by rememberSaveable { mutableStateOf<String?>(null) }
    var revision by rememberSaveable { mutableIntStateOf(0) }
    val exams = remember(context, today, revision) { StudyToolsStore.exams(context, date) }

    fun changed(success: Boolean) {
        if (success) {
            revision++
            error = ""
        } else error = "Nem sikerült elmenteni a változtatást."
    }

    if (!editing) {
        Button(onClick = { editing = true; examDate = today }, enabled = exams.size < StudyToolsStore.MAX_ITEMS) {
            Text("Új dolgozat")
        }
    } else {
        val scheduled = LocalDate.parse(examDate)
        val topicList = topics.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.distinct().toList()
        ToolsCard("Új dolgozat") {
            OutlinedTextField(title, { title = it.take(80) }, Modifier.fillMaxWidth(),
                label = { Text("Dolgozat neve") }, singleLine = true)
            OutlinedButton(onClick = {
                DatePickerDialog(context, { _, year, month, day ->
                    examDate = LocalDate.of(year, month + 1, day).toString()
                }, scheduled.year, scheduled.monthValue - 1, scheduled.dayOfMonth).apply {
                    val firstDay = java.util.Calendar.getInstance().apply {
                        set(date.year, date.monthValue - 1, date.dayOfMonth, 0, 0, 0)
                        set(java.util.Calendar.MILLISECOND, 0)
                    }
                    datePicker.minDate = firstDay.timeInMillis
                }.show()
            }) { Text("Dolgozat napja · ${scheduled.format(dateFormat)}") }
            OutlinedTextField(topics, { topics = it.take(6000) }, Modifier.fillMaxWidth(),
                label = { Text("Témák") }, minLines = 3, maxLines = 8,
                supportingText = { Text("Soronként egy téma, legfeljebb 60.") })
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    val saved = StudyToolsStore.addExam(context, title.trim(), scheduled, topicList, date)
                    changed(saved)
                    if (saved) { editing = false; title = ""; topics = "" }
                }, enabled = title.isNotBlank() && scheduled >= date && topicList.size in 1..60) {
                    Text("Terv készítése")
                }
                TextButton(onClick = { editing = false; error = "" }) { Text("Mégse") }
            }
        }
    }
    if (error.isNotEmpty()) Text(error, color = MaterialTheme.colorScheme.error)
    exams.forEach { exam ->
        ToolsCard(exam.title) {
            val left = ChronoUnit.DAYS.between(date, exam.examDate)
            val deadline = when {
                left < 0 -> "A dolgozat dátuma elmúlt"
                left == 0L -> "Ma van a dolgozat"
                else -> "$left nap a dolgozatig"
            }
            Text("${exam.examDate.format(dateFormat)} · $deadline")
            Text("${exam.topics.count { it.completed }} / ${exam.topics.size} téma kész")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { expanded = if (expanded == exam.id) null else exam.id }) {
                    Text(if (expanded == exam.id) "Terv becsukása" else "Napi terv")
                }
                TextButton(onClick = { deleteId = exam.id }) { Text("Törlés") }
            }
            if (expanded == exam.id) {
                exam.topics.withIndex().groupBy { it.value.due }.toSortedMap().forEach { (day, group) ->
                    Text(if (day == date) "Ma" else day.format(dateFormat),
                        color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                    group.forEach { (index, topic) ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = topic.completed, onCheckedChange = {
                                changed(StudyToolsStore.toggleTopic(context, exam.id, index, date))
                            })
                            Text(topic.title)
                        }
                    }
                }
            }
        }
    }
    exams.firstOrNull { it.id == deleteId }?.let { exam ->
        DeleteDialog(exam.title, { deleteId = null }) {
            changed(StudyToolsStore.deleteExam(context, exam.id))
            deleteId = null
        }
    }
}

@Composable
private fun QuizTools() {
    val context = LocalContext.current
    var revision by rememberSaveable { mutableIntStateOf(0) }
    val decks = remember(context, revision) { StudyToolsStore.quizzes(context) }
    var editing by rememberSaveable { mutableStateOf(false) }
    var editId by rememberSaveable { mutableStateOf<String?>(null) }
    var title by rememberSaveable { mutableStateOf("") }
    var minutes by rememberSaveable { mutableStateOf("5") }
    var questionText by rememberSaveable { mutableStateOf("") }
    var error by rememberSaveable { mutableStateOf("") }
    var deleteId by rememberSaveable { mutableStateOf<String?>(null) }
    var activeId by rememberSaveable { mutableStateOf<String?>(null) }
    var order by rememberSaveable { mutableStateOf(IntArray(0)) }
    var position by rememberSaveable { mutableIntStateOf(0) }
    var score by rememberSaveable { mutableIntStateOf(0) }
    var answer by rememberSaveable { mutableStateOf("") }
    var checked by rememberSaveable { mutableStateOf<Boolean?>(null) }
    var result by rememberSaveable { mutableStateOf("") }

    fun edit(deck: QuizDeck?) {
        editing = true
        editId = deck?.id
        title = deck?.title.orEmpty()
        minutes = (deck?.minutes ?: 5).toString()
        questionText = deck?.questions?.joinToString("\n") { "${it.prompt} | ${it.answer}" }.orEmpty()
        error = ""
    }

    val active = decks.firstOrNull { it.id == activeId }
    val question = order.getOrNull(position)?.let { active?.questions?.getOrNull(it) }
    if (active != null && question != null) {
        ToolsCard(active.title) {
            if (result.isEmpty()) {
                Text("${position + 1} / ${order.size}. kérdés")
                Text(question.prompt, style = MaterialTheme.typography.titleLarge)
                OutlinedTextField(answer, { answer = it.take(500) }, Modifier.fillMaxWidth(),
                    label = { Text("Válaszod") }, readOnly = checked != null)
            }
            checked?.let { correct ->
                Text(if (correct) "Helyes válasz." else "Helyes válasz · ${question.answer}",
                    color = if (correct) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
            }
            when {
                result.isNotEmpty() -> {
                    Text(result)
                    Button(onClick = { activeId = null }) { Text("Vissza a kvízekhez") }
                }
                checked == null -> Button(onClick = checkAnswer@{
                    if (checked != null || result.isNotEmpty()) return@checkAnswer
                    val correct = question.accepts(answer)
                    checked = correct
                    if (correct) score++
                    if (position == order.lastIndex) {
                        val summary = "$score / ${order.size} helyes."
                        result = if (score < StudyToolsStore.QUIZ_PASS) {
                            "$summary A jutalomhoz 4 helyes válasz kell."
                        } else {
                            val reward = Store.claimStudyReward(context, active.minutes * 60, "custom_quiz")
                            if (reward == null) "$summary A mai kvízjutalmat már megszerezted."
                            else {
                                ProgressStore.taskFinished(context, "OTHER", 0, reward, title = "Kvíz · ${active.title}")
                                if (reward > 0) "$summary Jutalom: +${duration(reward)}."
                                else "$summary A kvíz sikerült. Az időkeret megtelt."
                            }
                        }
                    }
                }, enabled = answer.isNotBlank()) { Text("Ellenőrzés") }
                else -> Button(onClick = {
                    if (checked != null && position < order.lastIndex) {
                        position++
                        checked = null
                        answer = ""
                    }
                }) { Text("Következő kérdés") }
            }
            if (result.isEmpty()) TextButton(onClick = { activeId = null }) { Text("Kvíz befejezése") }
        }
        return
    }

    if (editing) {
        val lines = questionText.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
        val questions = lines.mapNotNull { line ->
            val parts = line.split('|', limit = 2).map { it.trim() }
            if (parts.size == 2 && parts.all { it.length in 1..500 }) StudyQuestion(parts[0], parts[1]) else null
        }
        val rewardMinutes = minutes.toIntOrNull() ?: 0
        val unique = questions.map { it.prompt.lowercase(Locale.ROOT) }.distinct().size
        val invalidLines = lines.size != unique || unique > 100
        val valid = title.isNotBlank() && rewardMinutes in 1..10 && !invalidLines && unique >= StudyToolsStore.QUIZ_SIZE
        ToolsCard(if (editId == null) "Új kvíz" else "Kvíz szerkesztése") {
            OutlinedTextField(title, { title = it.take(80) }, Modifier.fillMaxWidth(),
                label = { Text("Kvíz neve") }, singleLine = true)
            OutlinedTextField(minutes, { minutes = it.filter(Char::isDigit).take(2) }, Modifier.fillMaxWidth(),
                label = { Text("Jutalom percben (1–10)") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
            OutlinedTextField(questionText, { questionText = it.take(16_000) }, Modifier.fillMaxWidth(),
                label = { Text("Kérdések és válaszok") }, minLines = 4, maxLines = 10,
                isError = invalidLines, supportingText = {
                    Text(if (invalidLines) "Ellenőrizd a sorok formátumát, hosszát és az ismétlődő kérdéseket."
                        else "Soronként egyedi kérdés, legalább öt. Írd így: kérdés | válasz")
                })
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    if (StudyToolsStore.saveQuiz(context, editId, title.trim(), rewardMinutes, questions)) {
                        editing = false
                        revision++
                    } else error = "Nem sikerült elmenteni a kvízt."
                }, enabled = valid) { Text("Mentés") }
                TextButton(onClick = { editing = false; error = "" }) { Text("Mégse") }
            }
        }
    } else {
        Text("Egy próbában öt kérdést kapsz. Négy helyes válaszért naponta egyszer jár időjutalom.")
        Button(onClick = { edit(null) }, enabled = decks.size < StudyToolsStore.MAX_ITEMS) { Text("Új kvíz") }
    }
    if (error.isNotEmpty()) Text(error, color = MaterialTheme.colorScheme.error)
    if (!editing) decks.forEach { deck ->
        ToolsCard(deck.title) {
            Text("${deck.questions.size} kérdés · +${deck.minutes} perc")
            Button(onClick = {
                order = deck.questions.indices.shuffled().take(StudyToolsStore.QUIZ_SIZE).toIntArray()
                position = 0
                score = 0
                answer = ""
                checked = null
                result = ""
                activeId = deck.id
            }) { Text("Kvíz indítása") }
            Row {
                TextButton(onClick = { edit(deck) }) { Text("Szerkesztés") }
                TextButton(onClick = { deleteId = deck.id }) { Text("Törlés") }
            }
        }
    }
    decks.firstOrNull { it.id == deleteId }?.let { deck ->
        DeleteDialog(deck.title, { deleteId = null }) {
            if (StudyToolsStore.deleteQuiz(context, deck.id)) revision++
            else error = "Nem sikerült törölni a kvízt."
            deleteId = null
        }
    }
}

@Composable
private fun TemplateTools(onQuestStarted: () -> Unit, openTimeTab: () -> Unit) {
    val context = LocalContext.current
    var revision by rememberSaveable { mutableIntStateOf(0) }
    val templates = remember(context, revision) { StudyToolsStore.templates(context) }
    var editing by rememberSaveable { mutableStateOf(false) }
    var editId by rememberSaveable { mutableStateOf<String?>(null) }
    var title by rememberSaveable { mutableStateOf("") }
    var minutes by rememberSaveable { mutableStateOf("15") }
    var error by rememberSaveable { mutableStateOf("") }
    var showTimeTab by rememberSaveable { mutableStateOf(false) }
    var deleteId by rememberSaveable { mutableStateOf<String?>(null) }

    fun edit(template: QuestTemplate?) {
        editing = true
        editId = template?.id
        title = template?.title.orEmpty()
        minutes = (template?.minutes ?: 15).toString()
        error = ""
        showTimeTab = false
    }

    if (editing) {
        val time = minutes.toIntOrNull() ?: 0
        ToolsCard(if (editId == null) "Új questsablon" else "Questsablon szerkesztése") {
            OutlinedTextField(title, { title = it.take(80) }, Modifier.fillMaxWidth(),
                label = { Text("Feladat neve") }, singleLine = true)
            OutlinedTextField(minutes, { minutes = it.filter(Char::isDigit).take(2) }, Modifier.fillMaxWidth(),
                label = { Text("Időtartam percben (5–60)") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    if (StudyToolsStore.saveTemplate(context, editId, title.trim(), time)) {
                        editing = false
                        revision++
                    } else error = "Nem sikerült elmenteni a sablont."
                }, enabled = title.trim().length >= 3 && time in 5..60) { Text("Mentés") }
                TextButton(onClick = { editing = false; error = "" }) { Text("Mégse") }
            }
        }
    } else {
        Text("Naponta egy alap- vagy extra questet válthatsz ki a saját időzített feladatoddal.")
        Button(onClick = { edit(null) }, enabled = templates.size < StudyToolsStore.MAX_ITEMS) { Text("Új sablon") }
    }
    if (error.isNotEmpty()) Text(error, color = MaterialTheme.colorScheme.error)
    if (showTimeTab) OutlinedButton(onClick = openTimeTab) { Text("Képernyőidő megnyitása") }
    if (!editing) templates.forEach { template ->
        ToolsCard(template.title) {
            val seconds = template.minutes * 60
            val history = QuestStore.templateSeconds(context, template.id)
            Text("${template.minutes} perc · Jutalom +${duration(QuestStore.customReward(seconds))}")
            Text("Eddig teljesítve: ${history / 60} perc")
            Button(onClick = {
                showTimeTab = false
                error = when {
                    !FocusService.isRunning || !UsageGuard.allowed(context) || !Settings.canDrawOverlays(context) -> {
                        showTimeTab = true
                        "Az indításhoz előbb kapcsold be a háttérvédelmet."
                    }
                    QuestStore.customUsed(context) -> "A mai egyéni questet már felhasználtad."
                    QuestStore.startTemplate(context, template) -> { onQuestStarted(); "" }
                    else -> "Az indításhoz fejezd be a folyamatban lévő questet vagy fókuszt."
                }
            }) { Text("Quest indítása") }
            Row {
                TextButton(onClick = { edit(template) }) { Text("Szerkesztés") }
                TextButton(onClick = { deleteId = template.id }) { Text("Törlés") }
            }
        }
    }
    templates.firstOrNull { it.id == deleteId }?.let { template ->
        DeleteDialog(template.title, { deleteId = null }) {
            if (StudyToolsStore.deleteTemplate(context, template.id)) revision++
            else error = "Nem sikerült törölni a sablont."
            deleteId = null
        }
    }
}
