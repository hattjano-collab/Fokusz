package hu.fokusz

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
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
    var showMore by rememberSaveable { mutableStateOf(false) }
    var revision by remember { mutableIntStateOf(0) }
    var note by remember { mutableStateOf("") }
    val apps by produceState<List<SocialUnlockApp>?>(null, c, showApps) {
        if (showApps) value = withContext(Dispatchers.IO) { SocialUnlockStore.apps(c) }
    }
    var history by remember { mutableStateOf(emptyList<SocialUnlockEntry>()) }
    LaunchedEffect(c, showHistory) {
        while (showHistory) {
            history = withContext(Dispatchers.IO) { SocialUnlockStore.history(c) }
            delay(2000L)
        }
    }
    val dateFormat = remember { DateTimeFormatter.ofPattern("MM.dd. HH:mm") }

    FeatureBox("Közösségi appok feloldása") {
        Text("A kijelölt app megnyitásához add meg a perceket és az indokot. A saját keret csak használat közben fogy.")
        OutlinedButton(onClick = { showApps = !showApps }) {
            Text(if (showApps) "Alkalmazások elrejtése" else "Közösségi appok kiválasztása")
        }
        if (showApps) {
            val installed = apps
            if (installed == null) Text("Alkalmazások betöltése…")
            else if (installed.isEmpty()) Text("A választható közösségi alkalmazások közül egyik sincs telepítve.")
            for (app in installed.orEmpty()) key(app.packageName) {
                val active = remember(revision, app.packageName) { SocialUnlockStore.selected(c, app.packageName) }
                val image = remember(app.icon) {
                    runCatching { app.icon?.toBitmap(96, 96)?.asImageBitmap() }.getOrNull()
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    image?.let { Image(it, contentDescription = null, modifier = Modifier.size(36.dp)) }
                    Text(app.name, Modifier.weight(1f))
                    Switch(checked = active, onCheckedChange = { selected ->
                        note = if (SocialUnlockStore.setSelected(c, app.packageName, selected)) ""
                            else "A beállítást nem sikerült menteni."
                        revision++
                    })
                }
            }
            if (note.isNotBlank()) Text(note)
        }
        OutlinedButton(onClick = { showHistory = !showHistory }) {
            Text(if (showHistory) "Előzmények elrejtése" else "Feloldási előzmények")
        }
        if (showHistory) {
            if (history.isEmpty()) Text("Még nincs mentett feloldás.")
            val limit = if (showMore) 100 else 10
            for (entry in history.take(limit)) {
                val grant = entry.grant
                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text("${grant.appName} · ${grant.minutes} percre feloldva", fontWeight = FontWeight.SemiBold)
                    Text("${duration(entry.usedSeconds)} használat · ${grant.reason.label}")
                    Text(Instant.ofEpochMilli(grant.startedAt).atZone(ZoneId.systemDefault()).format(dateFormat))
                }
            }
            if (history.size > 10) OutlinedButton(onClick = { showMore = !showMore }) {
                Text(if (showMore) "Kevesebb tétel" else "Korábbi feloldások")
            }
        }
    }
}
