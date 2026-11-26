package hu.fokusz

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable internal fun TeamSocialPanel(team: CloudTeamView) {
    val c = LocalContext.current
    var enabled by remember(team.id) { mutableStateOf(SocialTeamEvents.sharing(c)) }
    var shown by remember(team.id) { mutableStateOf(false) }
    var items by remember(team.id) { mutableStateOf<List<TeamSocialEvent>?>(null) }
    var error by remember(team.id) { mutableStateOf(false) }
    val formatter = remember { DateTimeFormatter.ofPattern("MM.dd. HH:mm") }
    LaunchedEffect(shown, team.id) {
        while (shown && team.id.isNotBlank()) {
            try {
                items = SocialTeamEvents.latest(c)
                error = false
            } catch (exception: CancellationException) {
                throw exception
            } catch (_: Exception) {
                error = true
            }
            delay(15_000L)
        }
    }

    FeatureBox("Közösségi feloldások a csapatban") {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Megosztom a feloldásaimat és a pihenőnapomat", Modifier.weight(1f))
            Switch(enabled, { share ->
                enabled = share
                SocialTeamEvents.setSharing(c, share)
            })
        }
        if (enabled) Text("A csapat az appot, az indokot és a kért időt látja.")
        OutlinedButton(onClick = { shown = !shown }) { Text(if (shown) "Események elrejtése" else "Csapatesemények") }
        if (shown) {
            if (error) Text("A csapatesemények most nem tölthetők be.")
            if (items == null && !error) Text("Események betöltése…")
            if (items?.isEmpty() == true) Text("Még nincs megosztott esemény.")
            for (entry in items.orEmpty().filterNot { TeamCommunity.hidden(c, it.uid) }) {
                val name = team.members.firstOrNull { it.uid == entry.uid }?.name ?: "Korábbi tag"
                val timestamp = entry.submittedAt?.atZone(ZoneId.systemDefault())?.format(formatter).orEmpty()
                Text("$timestamp · $name", fontWeight = FontWeight.SemiBold)
                if (entry.kind == "rest") Text("Elindított egy 24 órás pihenőnapot.")
                if (entry.kind == "unlock") {
                    Text("${entry.app} · ${entry.minutes} perc · ${entry.reason?.label.orEmpty()}")
                }
            }
        }
    }
}
