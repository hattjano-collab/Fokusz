package hu.fokusz

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

internal data class QuickTask(val minutes: Int, val text: String)

private val quickTasks = listOf(
    QuickTask(5, "Írj le emlékezetből három dolgot a legutóbbi tanóráról, majd ellenőrizd őket."),
    QuickTask(5, "Gyakorolj öt szót kártyákkal. Azokat ismételd meg, amelyek nem jutottak eszedbe."),
    QuickTask(5, "Készítsd ki a következő feladathoz szükséges anyagokat, és írd le az első lépést."),
    QuickTask(10, "Válaszolj három rövid kérdésre egy korábbi anyagból jegyzet nélkül, majd javítsd a válaszokat."),
    QuickTask(10, "Olvass el két oldalt a tankönyvből, és foglald össze a lényeget három pontban."),
    QuickTask(10, "Válassz egy korábban elrontott példát, és próbáld újra megoldani."),
    QuickTask(20, "Oldj meg két gyakorlófeladatot. Az utolsó öt percben ellenőrizd, hogyan jutottál az eredményre."),
    QuickTask(20, "Gyakorolj egy dolgozattémát. Próbáld meg a végén egy percben elmagyarázni az anyagot."),
    QuickTask(15, "Olvasd át egy óra jegyzetét, majd írj öt önellenőrző kérdést a következő ismétléshez.")
)

internal fun quickTasksFor(minutes: Int): List<QuickTask> =
    quickTasks.filter { it.minutes <= minutes }.sortedByDescending { it.minutes }.take(3)

@Composable internal fun QuickTaskSuggestions() {
    var available by rememberSaveable { mutableStateOf(5) }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(5, 10, 20).forEach { minutes ->
            FilterChip(selected = available == minutes, onClick = { available = minutes },
                label = { Text("$minutes perc") })
        }
    }
    quickTasksFor(available).forEach { task ->
        Text("${task.minutes} perc · ${task.text}")
    }
}

@Composable internal fun FocusSubjectChooser(
    subjects: List<FocusSubject>,
    selected: FocusSubject,
    onSelected: (String) -> Unit,
    onSaved: (String) -> Unit,
) {
    val ctx = LocalContext.current
    var open by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf(false) }
    var editorSubject by remember { mutableStateOf<FocusSubject?>(null) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Tantárgy")
        Box {
            OutlinedButton(modifier = Modifier.fillMaxWidth(), onClick = { open = true }) {
                Text("${selected.name} ▾")
            }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                (listOf(FocusSubjects.general) + subjects).forEach { subject ->
                    DropdownMenuItem(text = { Text(subject.name) }, onClick = {
                        onSelected(subject.id)
                        open = false
                    })
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = {
                editorSubject = null
                editing = true
            }) { Text("Új tantárgy") }
            if (selected.id.isNotEmpty()) TextButton(onClick = {
                editorSubject = selected
                editing = true
            }) { Text("Appok és név") }
        }
        if (selected.packages.isNotEmpty()) {
            val primary = MaterialTheme.colorScheme.primary
            val appNames = remember(ctx, selected.packages) {
                selected.packages.map { appLabel(ctx, it) }.sorted().joinToString()
            }
            Text("Külön engedélyezve: $appNames",
                color = primary, style = MaterialTheme.typography.bodySmall)
        }
    }
    if (editing) FocusSubjectEditor(editorSubject, onDismiss = { editing = false }) { id ->
        onSaved(id)
        editing = false
    }
}

private data class FocusApp(val pkg: String, val label: String)

private fun appLabel(c: Context, pkg: String): String = runCatching {
    val manager = c.packageManager
    manager.getApplicationLabel(manager.getApplicationInfo(pkg, 0)).toString()
}.getOrDefault(pkg)

private fun installedApps(c: Context): List<FocusApp> {
    val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    val manager = c.packageManager
    return manager.queryIntentActivities(launcherIntent, 0).mapNotNull { resolved ->
        val activity = resolved.activityInfo ?: return@mapNotNull null
        val pkg = activity.packageName
        if (FocusPrefs.alwaysAllowedInFocus(c, pkg)) return@mapNotNull null
        FocusApp(pkg, resolved.loadLabel(manager).toString())
    }.distinctBy { it.pkg }.sortedBy { it.label.lowercase(Locale.getDefault()) }
}

@Composable private fun FocusSubjectEditor(
    subject: FocusSubject?,
    onDismiss: () -> Unit,
    onSaved: (String) -> Unit,
) {
    val ctx = LocalContext.current
    var name by remember { mutableStateOf(subject?.name.orEmpty()) }
    var packages by remember { mutableStateOf(subject?.packages.orEmpty()) }
    var query by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }
    val apps = remember(ctx, subject?.id) {
        val visible = installedApps(ctx)
        val saved = subject?.packages.orEmpty().filter { pkg -> visible.none { it.pkg == pkg } }
        visible + saved.map { FocusApp(it, appLabel(ctx, it)) }
    }
    val matches = remember(apps, query) {
        apps.filter { it.label.contains(query.trim(), ignoreCase = true) || it.pkg.contains(query.trim(), ignoreCase = true) }
    }
    AlertDialog(onDismissRequest = onDismiss,
        title = { Text(if (subject == null) "Új tantárgy" else "Tantárgy szerkesztése") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it.take(60); error = "" }, Modifier.fillMaxWidth(),
                    label = { Text("Tantárgy neve") }, singleLine = true)
                Text("A Google Classroom mindig elérhető. Jelöld meg a tantárgyhoz szükséges további appokat.")
                OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(),
                    label = { Text("Alkalmazás keresése") }, singleLine = true)
                LazyColumn(modifier = Modifier.height(160.dp)) {
                    items(matches, key = { it.pkg }) { app ->
                        val checked = app.pkg in packages
                        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(value = checked, role = Role.Checkbox) {
                            packages = if (it) packages + app.pkg else packages - app.pkg
                        }, verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = checked, onCheckedChange = null)
                            Text(app.label)
                        }
                    }
                    if (matches.isEmpty()) item { Text("Nincs ilyen alkalmazás.") }
                }
                if (error.isNotEmpty()) Text(error, color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = {
            TextButton(enabled = name.isNotBlank(), onClick = {
                val id = FocusSubjects.save(ctx, subject?.id, name, packages)
                if (id == null) error = "Nem sikerült elmenteni. Használj egyedi tantárgynevet."
                else onSaved(id)
            }) { Text("Mentés") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Mégse") } },
    )
}

@Composable internal fun FocusNoteHistory(notes: List<FocusNote>) {
    var visibleCount by rememberSaveable { mutableStateOf(0) }
    OutlinedButton(onClick = { visibleCount = if (visibleCount > 0) 0 else 5 }) {
        Text(if (visibleCount > 0) "Jegyzetek elrejtése" else "Korábbi jegyzetek")
    }
    if (visibleCount > 0) {
        val dates = remember {
            DateTimeFormatter.ofPattern("yyyy. MMM d., HH:mm", Locale.forLanguageTag("hu-HU"))
                .withZone(ZoneId.systemDefault())
        }
        notes.take(visibleCount).forEach { note ->
            Text(note.subjectName, fontWeight = FontWeight.Bold)
            Text("${dates.format(Instant.ofEpochMilli(note.finishedAt))} · ${duration(note.seconds)}",
                style = MaterialTheme.typography.bodySmall)
            Text(note.text)
        }
        if (notes.size > visibleCount) TextButton(onClick = { visibleCount += 5 }) { Text("További jegyzetek") }
    }
}
