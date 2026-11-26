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
import kotlinx.coroutines.launch

@Composable internal fun TeamRankPanel(team: CloudTeamView) {
    val c = LocalContext.current
    val scope = rememberCoroutineScope()
    var open by remember(team.id) { mutableStateOf(false) }
    var share by remember(team.id) { mutableStateOf(TeamWeeklyUsage.enabled(c)) }
    var ranking by remember(team.id) { mutableStateOf<List<WeeklyMemberUsage>?>(null) }
    var friends by remember(team.id) { mutableStateOf(emptySet<String>()) }
    var status by remember(team.id) { mutableStateOf("") }
    var revision by remember(team.id) { mutableIntStateOf(0) }

    LaunchedEffect(open, team.id, revision) {
        while (open && team.id.isNotBlank()) {
            try {
                ranking = TeamWeeklyUsage.ranking(team.id).filter { row ->
                    !TeamCommunity.hidden(c, row.uid) && team.members.any { it.uid == row.uid }
                }
                friends = runCatching { TeamCommunity.friends(c).filter { it.state == "accepted" }.map { it.otherUid }.toSet() }
                    .getOrDefault(emptySet())
                status = ""
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { status = "A heti ranglista most nem érhető el." }
            delay(30_000L)
        }
    }

    FeatureBox("Heti csapatranglista") {
        OutlinedButton(onClick = { open = !open }) { Text(if (open) "Ranglista elrejtése" else "Heti ranglista") }
        if (!open) return@FeatureBox
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Megosztom a heti használatomat", Modifier.weight(1f))
            Switch(share, { enabled ->
                share = enabled
                TeamWeeklyUsage.setEnabled(c, enabled)
                if (enabled) {
                    CloudTeams.pulse(c, true)
                    revision++
                } else scope.launch {
                    try { TeamWeeklyUsage.withdraw(c, team.id); revision++ }
                    catch (e: CancellationException) { throw e }
                    catch (_: Exception) { status = "A korábbi heti sor törlését nem sikerült elküldeni." }
                }
            })
        }
        if (status.isNotBlank()) Text(status)
        val cutoff = java.time.Instant.now().minus(14L, java.time.temporal.ChronoUnit.DAYS)
        for (member in team.members.filter { it.updatedAt?.toInstant()?.isBefore(cutoff) == true }) {
            Text("${member.name} · Legalább 14 napja nem szinkronizált.")
        }
        if (ranking?.isEmpty() == true) Text("Ezen a héten még senki nem osztott meg adatokat.")
        fun name(uid: String) = team.members.firstOrNull { it.uid == uid }?.name ?: "Korábbi tag"
        if (!ranking.isNullOrEmpty()) {
            Text("Legkevesebb mért használat", fontWeight = FontWeight.Bold)
            for (row in ranking.orEmpty().sortedBy { it.usage }) {
                Text("${name(row.uid)} · ${duration(row.usage)}")
            }
            val mine = ranking.orEmpty().firstOrNull { it.uid == CloudTeams.currentUid() }
            val group = ranking.orEmpty()
            if (mine != null && group.size > 1) {
                Text("Saját eredmény: ${duration(mine.usage)} · Megosztó csapattagok átlaga: ${duration(group.map { it.usage }.average().toInt())}")
                for (friend in group.filter { it.uid in friends }) {
                    Text("Te: ${duration(mine.usage)} · ${name(friend.uid)}: ${duration(friend.usage)}")
                }
            }
            Text("Legtöbb felhasznált csapatidő", fontWeight = FontWeight.Bold)
            for (row in ranking.orEmpty().sortedByDescending { it.shared }) {
                Text("${name(row.uid)} · ${duration(row.shared)}")
            }
        }
    }
}
