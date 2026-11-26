package hu.fokusz

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
internal fun SocialUnlockSettings() {
    val c = LocalContext.current
    var showApps by rememberSaveable { mutableStateOf(false) }
    var showHistory by rememberSaveable { mutableStateOf(false) }
    var showGuide by rememberSaveable { mutableStateOf(!SocialUnlockStore.guideSeen(c)) }
    var tryUnlock by rememberSaveable { mutableStateOf(false) }
    var tryResult by rememberSaveable { mutableStateOf("") }
    var revision by remember { mutableIntStateOf(0) }
    var note by remember { mutableStateOf("") }
    var jokes by remember { mutableStateOf(SocialUnlockStore.jokes(c)) }
    val apps by produceState<List<SocialUnlockApp>?>(null, c, showApps, showGuide) {
        if (showApps || showGuide) value = withContext(Dispatchers.IO) { SocialUnlockStore.apps(c) }
    }
    var history by remember { mutableStateOf(emptyList<SocialUnlockEntry>()) }
    var now by remember { mutableStateOf(FocusPrefs.sampleTime(c)) }
    var active by remember { mutableStateOf(SocialUnlockStore.active(c, now)) }
    DisposableEffect(c) {
        val prefs = c.getSharedPreferences(SocialUnlockStore.PREFS, android.content.Context.MODE_PRIVATE)
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == "selected") revision++
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    LaunchedEffect(c, revision) {
        while (true) {
            now = FocusPrefs.sampleTime(c)
            active = SocialUnlockStore.active(c, now)
            delay(1000L)
        }
    }

    LaunchedEffect(c, showHistory) {
        while (showHistory) {
            history = withContext(Dispatchers.IO) { SocialUnlockStore.history(c) }
            delay(5000L)
        }
    }

    FeatureBox("Közösségi appok feloldása") {
        Text("Minden kijelölt apphoz külön add meg az időt és az indokot.")
        OutlinedButton(onClick = { showApps = !showApps }) {
            Text(if (showApps) "Alkalmazások elrejtése" else "Közösségi appok kiválasztása")
        }
        if (showApps) {
            val installed = apps
            if (installed == null) Text("Alkalmazások betöltése…")
            else if (installed.isEmpty()) Text("A választható közösségi alkalmazások közül egyik sincs telepítve.")
            for (app in installed.orEmpty()) key(app.packageName) {
                val selected = remember(revision, app.packageName) { SocialUnlockStore.selected(c, app.packageName) }
                val image = remember(app.icon) {
                    runCatching { app.icon?.toBitmap(96, 96)?.asImageBitmap() }.getOrNull()
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    image?.let { Image(it, contentDescription = null, modifier = Modifier.size(36.dp)) }
                    Text(app.name, Modifier.weight(1f))
                    Switch(checked = selected, modifier = Modifier.semantics { contentDescription = app.name },
                        onCheckedChange = { enabled ->
                            note = if (!enabled && SocialTeamRequests.guarded(c)) {
                                SocialTeamRequests.ask(c, SocialRequestKind.REMOVE, app, 0)
                            } else if (SocialUnlockStore.setSelected(c, app.packageName, enabled)) {
                                ""
                            } else "A beállítást nem sikerült menteni."
                            revision++
                        })
                }
            }
            if (note.isNotBlank()) Text(note)
        }

        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Vicces üzenetek", Modifier.weight(1f))
            Switch(jokes, { jokes = it; SocialUnlockStore.setJokes(c, it) },
                Modifier.semantics { contentDescription = "Vicces üzenetek" })
        }

        if (active.isNotEmpty()) {
            Text("Aktív feloldások", fontWeight = FontWeight.Bold)
            for (grant in active) key(grant.id) {
                val left = (grant.millisLeft(now) + 999L).div(1000L).toInt()
                Text("${grant.appName} még ${clock(left)}-ig használható")
                OutlinedButton(onClick = {
                    if (SocialUnlockStore.close(c, grant)) {
                        FocusService.refreshUnlocks()
                        revision++
                    }
                }) { Text("Most visszazárom") }
            }
        }

        OutlinedButton(onClick = { showHistory = !showHistory }) {
            Text(if (showHistory) "Előzmények elrejtése" else "Feloldási előzmények és statisztika")
        }
        if (showHistory) SocialUnlockSummary(history)

        RestDayPanel()
        OutlinedButton(onClick = {
            showGuide = !showGuide
            if (!showGuide) SocialUnlockStore.dismissGuide(c)
        }) {
            Text(if (showGuide) "Bemutató bezárása" else "Első lépések és próbafeloldás")
        }
        if (showGuide) {
            Text("1. Engedélyezd az alkalmazások feletti megjelenítést és a használati adatokat, majd indítsd el a védelmet ezen a fülön.")
            Text("2. A gyors zároláshoz az Android hozzáférhetőségi beállításaiban kapcsold be a Fókuszt.")
            OutlinedButton(onClick = { c.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }) {
                Text("Android hozzáférhetőségi beállításai")
            }
            Text("3. Jelölj ki egy közösségi appot. Próbáld ki először a visszanyilat, majd az idő és az indok megadását.")
            OutlinedButton(onClick = { showApps = true }) { Text("Appok kijelölése") }
            OutlinedButton(onClick = { tryUnlock = true }) { Text("Próbafeloldás megnyitása") }
            if (tryResult.isNotBlank()) Text(tryResult)
        }
    }
    if (tryUnlock) {
        val demo = remember(apps) { apps?.firstOrNull() ?: SocialUnlockApp("", "Mintaalkalmazás", null) }
        val draft = remember { SocialUnlockDraft() }
        val cancel = {
            tryResult = "Kiléptél. A kérés megszakadt."
            tryUnlock = false
        }
        Dialog(onDismissRequest = cancel,
            properties = DialogProperties(usePlatformDefaultWidth = false)) {
            AndroidView(modifier = Modifier.fillMaxSize(), factory = { context ->
                SocialUnlockOverlay(context, demo, draft, null, cancel, {}, { _, _, _, _ -> true }, cancel, {
                    tryResult = "Sikeres próbafeloldás."
                    tryUnlock = false
                }).apply { updateAvailability(15, 0); enter() }.view
            })
        }
    }
}

@Composable private fun RestDayPanel() {
    val c = LocalContext.current
    var until by remember { mutableLongStateOf(SocialUnlockStore.restUntil(c)) }
    var available by remember { mutableStateOf(SocialUnlockStore.canStartRest(c)) }
    var confirm by remember { mutableStateOf(false) }
    val formatter = remember { DateTimeFormatter.ofPattern("MM.dd. HH:mm") }
    LaunchedEffect(until) {
        if (until == 0L) return@LaunchedEffect
        delay((until - System.currentTimeMillis()).coerceAtLeast(0L))
        until = SocialUnlockStore.restUntil(c)
        available = SocialUnlockStore.canStartRest(c)
    }
    if (until > 0L) {
        Text("Pihenőnap aktív · ${Instant.ofEpochMilli(until).atZone(ZoneId.systemDefault()).format(formatter)}-ig")
        OutlinedButton(onClick = {
            if (SocialUnlockStore.stopRest(c)) {
                until = 0L
                available = SocialUnlockStore.canStartRest(c)
                FocusService.refreshUnlocks()
            }
        }) { Text("Pihenőnap lezárása") }
    } else if (available) {
        OutlinedButton(onClick = { confirm = true }) { Text("Havi pihenőnap indítása") }
    } else Text("Ebben a hónapban már volt pihenőnapod.")
    if (confirm) AlertDialog(onDismissRequest = { confirm = false },
        title = { Text("Elindítod a 24 órás pihenőnapot?") },
        text = { Text("A kijelölt appok napi alkalmazáskorlátja 24 órára szünetel. A feloldáshoz továbbra is kell idő, egy indok és megerősítés.") },
        confirmButton = {
            OutlinedButton(onClick = {
                if (SocialUnlockStore.startRest(c)) {
                    until = SocialUnlockStore.restUntil(c)
                    SocialTeamEvents.onRest(c)
                    FocusService.refreshUnlocks()
                }
                confirm = false
            }) { Text("Pihenőnap indítása") }
        },
        dismissButton = { OutlinedButton(onClick = { confirm = false }) { Text("Mégse") } })
}
