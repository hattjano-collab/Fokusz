package hu.fokusz

import android.app.DatePickerDialog
import android.content.ActivityNotFoundException
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

private val quizDate = DateTimeFormatter.ofPattern("yyyy. MM. dd.")
private val quizTime = DateTimeFormatter.ofPattern("yyyy. MM. dd. HH:mm")

private fun quizDuration(seconds: Int): String {
    val parts = mutableListOf<String>()
    if (seconds >= 60) parts += "${seconds / 60} perc"
    if (seconds % 60 != 0 || parts.isEmpty()) parts += "${seconds % 60} másodperc"
    return parts.joinToString(" ")
}

@Composable private fun QuizCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            content()
        }
    }
}

@Composable
internal fun QuizPhotoPanel(shared: QuizShare?, onShareConsumed: (String) -> Unit) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    var section by rememberSaveable { mutableIntStateOf(0) }
    var photos by remember { mutableStateOf(QuizDraft.photos(context)) }
    var subject by remember { mutableStateOf(QuizDraft.subject(context)) }
    var sharedText by remember { mutableStateOf(QuizDraft.text(context)) }
    var target by remember { mutableStateOf(QuizDraft.targetMinutes(context)) }
    var editingText by rememberSaveable { mutableStateOf(false) }
    var cropId by rememberSaveable { mutableStateOf<String?>(null) }
    var historyRevision by remember { mutableIntStateOf(0) }
    var running by remember { mutableIntStateOf(0) }
    var cameraPending by rememberSaveable { mutableStateOf(false) }
    var claiming by remember { mutableStateOf(false) }
    var draftRevision by remember { mutableIntStateOf(0) }
    val busy = running > 0 || cameraPending
    var message by rememberSaveable { mutableStateOf("") }
    var result by remember { mutableStateOf<QuizAnalysis.Result?>(null) }
    var unread by remember { mutableStateOf(emptyList<Int>()) }
    var lastCount by rememberSaveable { mutableIntStateOf(0) }

    fun resetRead() {
        draftRevision++
        result = null
        unread = emptyList()
        lastCount = 0
    }

    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        cameraPending = false
        if (QuizDraft.finishCamera(context, success)) {
            photos = QuizDraft.photos(context)
            resetRead()
            message = "Fotó hozzáadva a piszkozathoz."
        } else if (success) message = "A fényképet nem sikerült menteni. Készíts új fotót vagy válassz képet."
    }
    val gallery = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(QuizDraft.MAX_IMAGES)
    ) { uris ->
        if (uris.isNotEmpty()) scope.launch {
            running++
            resetRead()
            try {
                val accepted = QuizDraft.import(context, uris)
                photos = QuizDraft.photos(context)
                message = if (accepted == uris.size) "$accepted kép hozzáadva."
                    else "$accepted kép hozzáadva. Képenként 32 MB és piszkozatonként ${QuizDraft.MAX_IMAGES} kép fér el."
            } finally { running-- }
        }
    }

    LaunchedEffect(shared?.id) {
        if (shared == null) return@LaunchedEffect
        section = 0
        while (claiming) kotlinx.coroutines.delay(75L)
        running++
        resetRead()
        try {
            val additional = shared.text?.trim().orEmpty()
            if (additional.isNotBlank()) {
                val merged = if (sharedText.contains(additional)) sharedText
                    else listOf(sharedText, additional).filter(String::isNotBlank).joinToString("\n\n")
                sharedText = merged.take(QuizDraft.MAX_TEXT)
                QuizDraft.setText(context, sharedText)
                editingText = true
                message = "A megosztott szöveg a piszkozatban van."
            }
            if (shared.images.isNotEmpty()) {
                val count = QuizDraft.import(context, shared.images)
                photos = QuizDraft.photos(context)
                message = if (count > 0) "$count megosztott kép hozzáadva."
                    else "A megosztott képeket nem sikerült hozzáadni. Válaszd ki őket a galériából."
            }
            onShareConsumed(shared.id)
        } finally {
            running--
        }
    }

    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        listOf("Beküldés", "Korábbi kvízek", "Gyakorlás").forEachIndexed { index, name ->
            FilterChip(selected = section == index, onClick = { section = index },
                label = { Text(name, maxLines = 1) })
        }
    }
    when (section) {
        1 -> QuizHistory(historyRevision)
        2 -> QuizPractice(historyRevision) { historyRevision++ }
        else -> {
            QuizCard("Kijavított kvíz") {
                Text("Fotózd le a ChatGPT javítását, válassz képernyőképeket, vagy oszd meg az értékelést a Fókusszal.")
                OutlinedTextField(subject, {
                    subject = it.take(60)
                    QuizDraft.setSubject(context, subject)
                }, label = { Text("Tantárgy") }, singleLine = true, modifier = Modifier.fillMaxWidth(), enabled = !busy)
                CameraPermissionGate {
                    OutlinedButton(enabled = !busy && photos.size < QuizDraft.MAX_IMAGES, onClick = {
                        try {
                            val uri = QuizDraft.startCamera(context)
                            if (uri == null) message = "Nem sikerült előkészíteni a fotót."
                            else {
                                cameraPending = true
                                camera.launch(uri)
                            }
                        } catch (_: ActivityNotFoundException) {
                            cameraPending = false
                            QuizDraft.finishCamera(context, false)
                            message = "Nem található kameraalkalmazás. Válassz képet a galériából."
                        } catch (_: Exception) {
                            cameraPending = false
                            QuizDraft.finishCamera(context, false)
                            message = "Nem indult el a kamera. Ellenőrizd a telefon kameraengedélyét."
                        }
                    }) { Text("Fotó készítése") }
                }
                OutlinedButton(enabled = !busy && photos.size < QuizDraft.MAX_IMAGES, onClick = {
                    gallery.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                }) { Text("Képek kiválasztása a galériából") }
                TextButton(onClick = { editingText = !editingText }, enabled = !busy) {
                    Text(if (editingText) "Értékelési szöveg elrejtése" else "Értékelési szöveg hozzáadása")
                }
                if (editingText) {
                    OutlinedTextField(sharedText, {
                        sharedText = it.take(QuizDraft.MAX_TEXT)
                        QuizDraft.setText(context, sharedText)
                        resetRead()
                    }, modifier = Modifier.fillMaxWidth(), label = { Text("A ChatGPT javítása") },
                        minLines = 2, maxLines = 5, enabled = !busy)
                }
                TextButton(onClick = {
                    clipboard.setText(AnnotatedString(QuizAnalysis.PROMPT))
                    message = "A ChatGPT-utasítást kimásoltad."
                }) { Text("ChatGPT-utasítás másolása") }
            }
            if (photos.isNotEmpty() || sharedText.isNotBlank()) {
                QuizCard("Mentett piszkozat") {
                    Text("A képek, a kijelölések és az értékelési szöveg megmaradnak, amikor bezárod az appot.")
                    for ((index, photo) in photos.withIndex()) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text("${index + 1}. kép" + if (photo.crop != QuizCrop.Full) " · körbevágva" else "",
                                Modifier.weight(1f))
                            TextButton(enabled = !busy, onClick = { cropId = photo.id }) { Text("Kivágás") }
                            TextButton(enabled = !busy, onClick = {
                                QuizDraft.remove(context, photo.id)
                                photos = QuizDraft.photos(context)
                                resetRead()
                            }) { Text("Eltávolítás") }
                        }
                    }
                    Button(enabled = !busy, onClick = {
                        scope.launch {
                            running++
                            resetRead()
                            val scanRevision = draftRevision
                            val scanPhotos = photos
                            val scanText = sharedText
                            message = ""
                            try {
                                val read = QuizDraft.readPhotos(context, scanPhotos)
                                val analyzed = withContext(Dispatchers.Default) {
                                    QuizAnalysis.analyze(read.texts + listOf(scanText).filter(String::isNotBlank))
                                }
                                if (draftRevision == scanRevision) {
                                    unread = read.unreadPages
                                    result = analyzed
                                }
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (_: Exception) {
                                message = "Nem sikerült beolvasni az értékelést. Próbálj élesebb képet használni."
                            } finally { running-- }
                        }
                    }) { Text("Eredmény beolvasása") }
                }
            }
            if (busy) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    CircularProgressIndicator(Modifier.width(20.dp).height(20.dp), strokeWidth = 2.dp)
                    Text("Feldolgozás…")
                }
            }
            result?.let { analyzed ->
                QuizCard("Jóváírás előtti ellenőrzés") {
                    if (analyzed.total != null) {
                        Text("${analyzed.correct} helyes válasz a ${analyzed.total}-ből · Jutalom: ${quizDuration(analyzed.rewardSeconds)}",
                            fontWeight = FontWeight.Bold)
                    }
                    for (issue in analyzed.issues) Text(issue)
                    if (unread.isNotEmpty()) Text("Nem olvasható kép: ${unread.joinToString()}. Cseréld ki vagy távolítsd el.")
                    for (row in analyzed.rows.take(50)) {
                        val label = when (row.grade) {
                            QuizAnalysis.Grade.CORRECT -> "Helyes"
                            QuizAnalysis.Grade.WRONG -> "Hibás"
                            QuizAnalysis.Grade.BLANK -> "Üres"
                            QuizAnalysis.Grade.UNCLEAR -> "Bizonytalan"
                        }
                        Text("${row.number}. $label" + row.text.takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty())
                    }
                    if (analyzed.canClaim && unread.isEmpty()) {
                        Button(enabled = !busy, onClick = {
                            scope.launch {
                                if (running > 0 || cameraPending) return@launch
                                running++
                                claiming = true
                                try {
                                    val claim = withContext(Dispatchers.IO) { QuizStore.claim(context, analyzed, subject) }
                                    message = when (claim.status) {
                                        QuizStore.ClaimStatus.SAVED -> "Jóváírva: ${quizDuration(claim.entry?.rewardSeconds ?: 0)}."
                                        QuizStore.ClaimStatus.DUPLICATE -> "Ezt a kvízkitöltést már elszámoltad."
                                        QuizStore.ClaimStatus.INVALID -> "Kérlek, adj hozzá képet a teljes javításról."
                                        QuizStore.ClaimStatus.LIMIT_REACHED -> "A technikai egyenleghatár miatt most nem írható jóvá a teljes jutalom."
                                        QuizStore.ClaimStatus.STORAGE_ERROR -> "A mentés sikertelen. Próbáld újra."
                                    }
                                    if (claim.status == QuizStore.ClaimStatus.SAVED) {
                                        lastCount = analyzed.correct
                                        QuizDraft.clear(context)
                                        photos = emptyList()
                                        sharedText = ""
                                        result = null
                                        unread = emptyList()
                                        historyRevision++
                                    }
                                } finally {
                                    claiming = false
                                    running--
                                }
                            }
                        }) { Text("Jóváírás") }
                    } else {
                        Text("A jóváíráshoz adj hozzá olvasható képet vagy szöveget a ChatGPT értékeléséről.")
                    }
                }
            }
            if (message.isNotBlank()) Text(message, color = MaterialTheme.colorScheme.primary)
            QuizCard("Képernyőidő-cél") {
                OutlinedTextField(target, {
                    target = it.filter(Char::isDigit).take(4)
                    QuizDraft.setTarget(context, target)
                }, label = { Text("Kívánt képernyőidő (perc)") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                target.toIntOrNull()?.takeIf { it > 0 }?.let { minutes ->
                    val needed = minutes * 2
                    val have = result?.takeIf { it.canClaim && unread.isEmpty() }?.correct ?: lastCount
                    val left = (needed - have).coerceAtLeast(0)
                    Text("$needed helyes válasz kell. " +
                        if (left == 0) "Elérted a célt." else "$have megvan, $left hiányzik.")
                }
            }
        }
    }

    photos.firstOrNull { it.id == cropId }?.let { photo ->
        QuizCropDialog(photo, onDismiss = { cropId = null }) { crop ->
            QuizDraft.setCrop(context, photo.id, crop)
            photos = QuizDraft.photos(context)
            resetRead()
            cropId = null
        }
    }
}

@Composable
private fun QuizHistory(revision: Int) {
    val context = LocalContext.current
    val history = remember(revision) { QuizStore.history(context) }.sortedByDescending { it.completedAt }
    var selection by rememberSaveable { mutableStateOf<String?>(null) }
    val subjects = history.map { it.subject }.distinct()
    QuizCard("Tantárgyankénti fejlődés") {
        if (history.isEmpty()) Text("Az első jóváírt kvíz után itt láthatod az eredményeidet.")
        for (subject in subjects) {
            TextButton(onClick = { selection = if (selection == subject) null else subject }) {
                Text(subject)
            }
        }
        selection?.let { selected ->
            history.filter { it.subject == selected }.take(4).reversed().forEach { attempt ->
                val percent = if (attempt.total > 0) 100 * attempt.correct / attempt.total else 0
                Text("${attempt.completedAt.format(quizDate)} · ${attempt.correct}/${attempt.total} · $percent%")
            }
        }
    }
    QuizCard("Korábbi kvízeim") {
        if (history.isEmpty()) Text("Még nincs elszámolt kvízed.")
        for (attempt in history) {
            Text(attempt.subject, fontWeight = FontWeight.Bold)
            Text("${attempt.completedAt.format(quizTime)} · ${attempt.correct}/${attempt.total} helyes · " +
                quizDuration(attempt.rewardSeconds))
        }
    }
}

@Composable
private fun QuizPractice(revision: Int, changed: () -> Unit) {
    val context = LocalContext.current
    val items = remember(revision) { QuizStore.practice(context).filterNot { it.reviewed } }
    val exams = remember(revision) { QuizStore.exams(context) }
    var examSubject by rememberSaveable { mutableStateOf("") }
    var notice by rememberSaveable { mutableStateOf("") }
    var now by remember { mutableStateOf(LocalDateTime.now()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = LocalDateTime.now()
            kotlinx.coroutines.delay(60_000L)
        }
    }
    val tomorrow = exams.filter { it.date == now.toLocalDate().plusDays(1) && now.hour >= 18 }
    if (tomorrow.isNotEmpty()) {
        QuizCard("Holnapi dolgozat") {
            for (exam in tomorrow) {
                Text(exam.subject, fontWeight = FontWeight.Bold)
                val pending = items.filter { it.subject.equals(exam.subject, ignoreCase = true) }
                if (pending.isEmpty()) Text("Nincs nyitott gyakorlókérdés.")
                for (item in pending) Text("${item.number}. ${item.text}")
            }
        }
    }
    QuizCard("Ezt még gyakorold") {
        if (items.isEmpty()) Text("A tételes javítás hibás válaszai itt jelennek meg.")
        items.groupBy { it.subject }.forEach { (subject, group) ->
            Text(subject, fontWeight = FontWeight.Bold)
            for (item in group) {
                Text("${item.number}. ${item.text}")
                TextButton(onClick = { if (QuizStore.setReviewed(context, item.id, true)) changed() }) {
                    Text("Már helyesen megválaszoltam")
                }
            }
        }
    }
    QuizCard("Dolgozat dátuma") {
        OutlinedTextField(examSubject, { examSubject = it.take(60) }, label = { Text("Tantárgy") },
            singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedButton(enabled = examSubject.isNotBlank(), onClick = {
            val today = LocalDate.now()
            DatePickerDialog(context, { _, year, month, day ->
                if (QuizStore.saveExam(context, examSubject.trim(), LocalDate.of(year, month + 1, day))) {
                    changed()
                    notice = "A dolgozat dátuma mentve."
                } else notice = "A dátum mentése nem sikerült."
            }, today.year, today.monthValue - 1, today.dayOfMonth).apply {
                datePicker.minDate = System.currentTimeMillis() - 86_400_000L
            }.show()
        }) { Text("Dátum kiválasztása") }
        if (notice.isNotBlank()) Text(notice)
        for (exam in exams) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("${exam.subject} · ${exam.date.format(quizDate)}", Modifier.weight(1f))
                TextButton(onClick = { if (QuizStore.removeExam(context, exam.id)) changed() }) { Text("Törlés") }
            }
        }
    }
}

private fun imageFrame(area: Size, imageWidth: Int, imageHeight: Int): Rect {
    val scale = minOf(area.width / imageWidth, area.height / imageHeight)
    val width = imageWidth * scale
    val height = imageHeight * scale
    return Rect((area.width - width) / 2, (area.height - height) / 2,
        (area.width + width) / 2, (area.height + height) / 2)
}

@Composable
private fun QuizCropDialog(photo: QuizPhoto, onDismiss: () -> Unit, onSave: (QuizCrop) -> Unit) {
    val ctx = LocalContext.current
    val bitmap by produceState<android.graphics.Bitmap?>(null, photo.id) {
        value = withContext(Dispatchers.IO) { QuizDraft.bitmap(ctx, photo, 1500) }
    }
    val image = remember(bitmap) { bitmap?.asImageBitmap() }
    var crop by remember(photo.id) { mutableStateOf(photo.crop) }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.padding(14.dp).widthIn(max = 540.dp), shape = MaterialTheme.shapes.large) {
            Column(Modifier.padding(14.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Az értékelés kivágása", style = MaterialTheme.typography.titleLarge)
                Text("Húzz téglalapot a megtartandó rész köré.")
                image?.let { preview ->
                    Canvas(Modifier.fillMaxWidth().height(360.dp).semantics { contentDescription = "Kivágható kvízkép" }
                        .pointerInput(preview) {
                            var start = Offset.Zero
                            fun normalized(point: Offset): Offset {
                                val frame = imageFrame(Size(size.width.toFloat(), size.height.toFloat()), preview.width, preview.height)
                                return Offset(((point.x - frame.left) / frame.width).coerceIn(0f, 1f),
                                    ((point.y - frame.top) / frame.height).coerceIn(0f, 1f))
                            }
                            detectDragGestures(onDragStart = { start = normalized(it) }) { change, _ ->
                                change.consume()
                                val end = normalized(change.position)
                                crop = QuizCrop(minOf(start.x, end.x), minOf(start.y, end.y),
                                    maxOf(start.x, end.x), maxOf(start.y, end.y))
                            }
                        }) {
                        val frame = imageFrame(size, preview.width, preview.height)
                        drawImage(preview, dstOffset = IntOffset(frame.left.roundToInt(), frame.top.roundToInt()),
                            dstSize = IntSize(frame.width.roundToInt(), frame.height.roundToInt()))
                        val kept = Rect(frame.left + frame.width * crop.left, frame.top + frame.height * crop.top,
                            frame.left + frame.width * crop.right, frame.top + frame.height * crop.bottom)
                        val shade = Color.Black.copy(alpha = 0.65f)
                        drawRect(shade, frame.topLeft, Size(frame.width, kept.top - frame.top))
                        drawRect(shade, Offset(frame.left, kept.bottom), Size(frame.width, frame.bottom - kept.bottom))
                        drawRect(shade, Offset(frame.left, kept.top), Size(kept.left - frame.left, kept.height))
                        drawRect(shade, Offset(kept.right, kept.top), Size(frame.right - kept.right, kept.height))
                        drawRect(Color(0xFF7BE7C1), kept.topLeft, kept.size, style = Stroke(3.dp.toPx()))
                    }
                } ?: Text("Kép betöltése…")
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = onDismiss) { Text("Mégse") }
                    TextButton(onClick = { crop = QuizCrop.Full }) { Text("Teljes kép") }
                    Button(enabled = image != null && crop.right - crop.left >= 0.04f && crop.bottom - crop.top >= 0.04f,
                        onClick = { onSave(crop) }) { Text("Mentés") }
                }
            }
        }
    }
}
