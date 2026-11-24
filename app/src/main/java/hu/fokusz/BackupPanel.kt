package hu.fokusz

import android.content.ActivityNotFoundException
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate

@Composable
internal fun BackupPanel(onRestored: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var pending by remember { mutableStateOf<BackupStore.Snapshot?>(null) }
    var busy by remember { mutableStateOf(false) }
    var message by rememberSaveable { mutableStateOf("") }
    val create = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        busy = true
        scope.launch {
            try {
                withContext(Dispatchers.IO) { BackupStore.write(context, uri) }
                message = "A mentés elkészült."
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: IllegalArgumentException) {
                message = error.message.orEmpty().ifBlank { "A fájlt nem sikerült menteni." }
            } catch (_: Exception) {
                message = "A fájlt nem sikerült menteni."
            } finally {
                busy = false
            }
        }
    }
    val open = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        busy = true
        scope.launch {
            try {
                pending = withContext(Dispatchers.IO) { BackupStore.read(context, uri) }
                message = ""
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: IllegalArgumentException) {
                message = error.message.orEmpty().ifBlank { "A fájlt nem sikerült megnyitni." }
            } catch (_: Exception) {
                message = "A fájlt nem sikerült megnyitni."
            } finally {
                busy = false
            }
        }
    }

    Text("Mentsd fájlba a beállításokat, a kvízelőzményeket, a feladatokat és a személyes egyenleget.",
        color = Color(0xFFA7B4CC), fontSize = 13.sp)
    Button(enabled = !busy, modifier = Modifier.fillMaxWidth(), onClick = {
        try {
            create.launch("Fokusz-mentes-${LocalDate.now()}.json")
        } catch (_: ActivityNotFoundException) {
            message = "A telefonon nem érhető el a rendszer fájlválasztója."
        }
    }) { Text("Mentés fájlba") }
    OutlinedButton(enabled = !busy, modifier = Modifier.fillMaxWidth(), onClick = {
        try {
            open.launch(arrayOf("application/json", "text/plain", "application/octet-stream"))
        } catch (_: ActivityNotFoundException) {
            message = "A telefonon nem érhető el a rendszer fájlválasztója."
        }
    }) { Text("Visszaállítás fájlból") }
    if (busy) Text("Mentés feldolgozása…", color = Color(0xFFA7B4CC), fontSize = 12.sp)
    if (message.isNotBlank()) Text(message, color = Color(0xFFF5F7FF), fontSize = 13.sp)

    pending?.let { snapshot ->
        val fromToday = snapshot.wallet.wallet.day == LocalDate.now()
        AlertDialog(
            onDismissRequest = { pending = null },
            title = { Text("Visszaállítod a mentést?") },
            text = {
                val balanceText = if (fromToday) {
                    "A mai egyenleg helyére a mentett egyenleg kerül, az azóta elköltött idő figyelembevételével."
                } else {
                    "Ez egy korábbi nap mentése. A mai egyenleged megmarad."
                }
                Text("A helyi beállításokat és feladatokat lecseréljük. A kvízelőzmények egyesülnek. $balanceText")
            },
            confirmButton = {
                TextButton(onClick = {
                    pending = null
                    busy = true
                    scope.launch {
                        try {
                            val walletRestored = withContext(Dispatchers.IO) { BackupStore.restore(context, snapshot) }
                            message = if (walletRestored) "A mentést és a mai egyenleget visszaállítottuk."
                            else "A mentést visszaállítottuk. A mai egyenleged megmaradt."
                            onRestored()
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (error: IllegalArgumentException) {
                            message = error.message.orEmpty().ifBlank { "A mentést nem sikerült visszaállítani." }
                        } catch (_: Exception) {
                            message = "A mentést nem sikerült visszaállítani."
                        } finally {
                            busy = false
                        }
                    }
                }) { Text("Visszaállítás") }
            },
            dismissButton = { TextButton(onClick = { pending = null }) { Text("Mégse") } }
        )
    }
}
