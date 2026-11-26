package hu.fokusz

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.Collator

internal data class InstalledApp(val pkg: String, val label: String)

internal fun installedScreenApps(context: Context): List<InstalledApp> {
    val manager = context.packageManager
    val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    val collator = Collator.getInstance()
    val launchable = manager.queryIntentActivities(launcher, 0).mapNotNull { resolved ->
        val packageName = resolved.activityInfo?.packageName ?: return@mapNotNull null
        InstalledApp(packageName, resolved.loadLabel(manager).toString())
    }
    val downloaded = manager.getInstalledApplications(0).filter { info ->
        info.flags and ApplicationInfo.FLAG_SYSTEM == 0 || info.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP != 0
    }.map { InstalledApp(it.packageName, it.loadLabel(manager).toString()) }
    return (launchable + downloaded).distinctBy { it.pkg }.filterNot { FocusPrefs.isEssential(context, it.pkg) }
        .sortedWith { a, b -> collator.compare(a.label, b.label) }
}

@Composable internal fun AppLimitsPanel(focus: Boolean) {
    val context = LocalContext.current
    var expanded by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var count by rememberSaveable { mutableIntStateOf(30) }
    var edited by remember { mutableStateOf<InstalledApp?>(null) }
    var refresh by remember { mutableIntStateOf(0) }
    var limitMessage by remember { mutableStateOf("") }
    val apps by produceState(emptyList<InstalledApp>(), context, expanded) {
        if (expanded) value = withContext(Dispatchers.IO) { installedScreenApps(context) }
    }
    Text(if (focus) "Ezek a szabályok a fókuszmódra és az egyedi questekre vonatkoznak. Az Alapértelmezett a tantárgy engedélyeit is figyelembe veszi."
        else "A napi percenkénti korlát és a saját egyenleg fogyasztása egymástól is külön beállítható.")
    Text("A Classroom mindig elérhető.")
    if (limitMessage.isNotBlank()) Text(limitMessage)
    OutlinedButton(onClick = { expanded = !expanded }) {
        Text(if (expanded) "Alkalmazások elrejtése" else "Telepített alkalmazások")
    }
    if (expanded) {
        OutlinedTextField(query, { query = it; count = 30 }, label = { Text("Alkalmazás keresése") },
            singleLine = true, modifier = Modifier.fillMaxWidth())
        val filtered = apps.filter { it.label.contains(query.trim(), true) || it.pkg.contains(query.trim(), true) }
        if (filtered.isEmpty()) Text(if (apps.isEmpty()) "Nincs megjeleníthető alkalmazás." else "Nincs találat.")
        for (app in filtered.take(count)) {
            val label = remember(app, refresh, focus) {
                if (focus) FocusPrefs.focusRule(context, app.pkg).label
                else {
                    val limit = SocialUsage.dailyLimitMinutes(context, app.pkg)
                    val charge = FocusPrefs.blocked(context, app.pkg)
                    listOf(if (limit > 0) "$limit perc/nap" else "Nincs napi plafon",
                        if (charge) "Saját keretből" else "").filter(String::isNotBlank).joinToString(" · ")
                }
            }
            OutlinedButton(onClick = { edited = app }, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.weight(1f)) {
                    Text(app.label, fontWeight = FontWeight.SemiBold)
                    Text(label)
                }
            }
        }
        if (filtered.size > count) TextButton(onClick = { count += 30 }) { Text("További alkalmazások") }
    }
    edited?.let { app ->
        var minutes by remember(app) { mutableStateOf(SocialUsage.dailyLimitMinutes(context, app.pkg).toString()) }
        var charge by remember(app) { mutableStateOf(FocusPrefs.blocked(context, app.pkg)) }
        var rule by remember(app) { mutableStateOf(FocusPrefs.focusRule(context, app.pkg)) }
        val value = minutes.toIntOrNull()
        AlertDialog(onDismissRequest = { edited = null }, title = { Text(app.label) }, text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (focus) {
                    for (option in FocusAppRule.entries) {
                        FilterChip(selected = option == rule, onClick = { rule = option }, label = { Text(option.label) })
                    }
                } else {
                    Text("Mai mért használat: ${clock(SocialUsage.dailySeconds(context, app.pkg))}.")
                    OutlinedTextField(minutes, { minutes = it.filter(Char::isDigit).take(4) },
                        label = { Text("Napi korlát percben (0 = nincs)") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Fogyassza a saját időt", Modifier.weight(1f))
                        Switch(checked = charge, onCheckedChange = { charge = it })
                    }
                }
            }
        }, confirmButton = {
            TextButton(enabled = focus || (value != null && value in 0..1440), onClick = {
                if (focus) FocusPrefs.setFocusRule(context, app.pkg, rule)
                else {
                    val requested = value ?: return@TextButton
                    val old = SocialUsage.dailyLimitMinutes(context, app.pkg)
                    if (old > 0 && (requested == 0 || requested > old) &&
                        SocialTeamRequests.guarded(context) && SocialUnlockStore.selected(context, app.pkg)) {
                        limitMessage = SocialTeamRequests.ask(context, SocialRequestKind.LIMIT,
                            SocialUnlockStore.app(context, app.pkg), requested, old = old)
                    } else {
                        SocialUsage.setDailyLimit(context, app.pkg, requested)
                        limitMessage = ""
                    }
                    FocusPrefs.setBlocked(context, app.pkg, charge)
                }
                refresh++
                edited = null
            }) { Text("Mentés") }
        }, dismissButton = { TextButton(onClick = { edited = null }) { Text("Mégse") } })
    }
}
