package hu.fokusz

import android.content.Context
import android.graphics.drawable.Drawable
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun ScreenTimeOverview(balance: Int, protected: Boolean, onBank: () -> Unit, onManage: () -> Unit) {
    val ctx = LocalContext.current
    val look = FokuszAppearance.look
    val apps by produceState(emptyList<SocialUnlockApp>(), ctx) {
        value = withContext(Dispatchers.IO) { SocialUnlockStore.apps(ctx) }
    }
    val installed by produceState(emptyList<InstalledApp>(), ctx) {
        value = withContext(Dispatchers.IO) { installedScreenApps(ctx) }
    }
    val managed = remember(installed, apps) {
        installed.filter { item ->
            FocusPrefs.blocked(ctx, item.pkg) || SocialUsage.dailyLimitMinutes(ctx, item.pkg) > 0
        }.map { found -> apps.firstOrNull { it.packageName == found.pkg } ?: SocialUnlockStore.app(ctx, found.pkg) }
    }
    var appIds by remember { mutableStateOf(emptySet<String>()) }
    var stats by remember { mutableStateOf(0 to 0) }
    var showApps by rememberSaveable { mutableStateOf(false) }
    var feedback by rememberSaveable { mutableStateOf("") }
    LaunchedEffect(ctx, apps) {
        while (true) {
            appIds = apps.filter { SocialUnlockStore.selected(ctx, it.packageName) }.map { it.packageName }.toSet()
            stats = (ProgressStore.recent(ctx, 1).lastOrNull()?.usage ?: 0) to ProgressStore.streak(ctx)
            delay(1000L)
        }
    }
    val selected = apps.filter { it.packageName in appIds }
    FokuszHeading("Képernyőidő")
    TimeGraphic(balance)
    FokuszAction("Alkalmazás feloldása", Modifier.fillMaxWidth()) { showApps = true }
    if (feedback.isNotBlank()) Text(feedback, color = look.muted)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FokuszCard(Modifier.weight(1f)) {
            Text("Mai használat", style = MaterialTheme.typography.labelLarge, color = look.muted)
            Text(formattedTime(stats.first.toLong()), style = MaterialTheme.typography.titleLarge)
        }
        FokuszCard(Modifier.weight(1f)) {
            Text("Sorozat", style = MaterialTheme.typography.labelLarge, color = look.muted)
            Text("${stats.second} nap", style = MaterialTheme.typography.titleLarge)
        }
    }
    val watched = (selected + managed).distinctBy { it.packageName }
    if (watched.isNotEmpty()) {
        Text("Kezelt appok", style = MaterialTheme.typography.titleLarge)
        FokuszCard {
            for (app in watched) {
                ManagedAppRow(app, ctx, protected)
                if (app != watched.last()) HorizontalDivider(color = look.outline.copy(alpha = .35f))
            }
        }
    }
    FokuszAction("Alkalmazáskeretek szerkesztése", Modifier.fillMaxWidth(), primary = false, onClick = onManage)
    if (showApps) ModalBottomSheet(onDismissRequest = { showApps = false }) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(.76f).verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Melyik appot nyitod meg?", style = MaterialTheme.typography.headlineSmall)
            Text("Saját keret · ${formattedTime(balance.toLong())}", color = look.muted)
            when {
                !protected -> Text("A Képernyőidő fülön add meg a használati és megjelenítési engedélyt, majd indítsd el a védelmet.")
                apps.isEmpty() -> Text("Nincs telepített, választható közösségi app.")
                selected.isEmpty() -> {
                    Text("Válassz egy appot, amelyikhez időt és indokot szeretnél megadni.")
                    for (app in apps) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            AppIcon(app.icon)
                            Text(app.name, Modifier.weight(1f))
                            Switch(checked = app.packageName in appIds, onCheckedChange = { enabled ->
                                if (enabled && SocialUnlockStore.setSelected(ctx, app.packageName, true)) appIds += app.packageName
                            })
                        }
                    }
                }
                else -> for (app in selected) {
                    FokuszAction("Megnyitom · ${app.name}", Modifier.fillMaxWidth(), primary = false) {
                        val intent = ctx.packageManager.getLaunchIntentForPackage(app.packageName)
                        feedback = if (intent != null && runCatching { ctx.startActivity(intent) }.isSuccess) ""
                            else "Az alkalmazás most nem indítható."
                        showApps = false
                    }
                }
            }
            if (balance == 0) {
                FokuszAction("Időbank megnyitása", Modifier.fillMaxWidth(), primary = false) { showApps = false; onBank() }
            }
        }
    }
}

@Composable private fun ManagedAppRow(app: SocialUnlockApp, c: Context, protected: Boolean) {
    val used = SocialUsage.dailySeconds(c, app.packageName)
    val state = when {
        !protected -> "A védelem inaktív"
        SocialUnlockStore.limitReached(c, app.packageName) -> "Napi korlát elérve"
        SocialUnlockStore.current(c, app.packageName) != null -> "Feloldás aktív"
        SocialUnlockStore.selected(c, app.packageName) -> "Feloldás szükséges"
        SocialUsage.dailyLimitMinutes(c, app.packageName) > 0 ->
            "Napi keret · ${SocialUsage.dailyLimitMinutes(c, app.packageName)} perc"
        else -> "Saját időkeretet használ"
    }
    Row(Modifier.fillMaxWidth().heightIn(min = 50.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        AppIcon(app.icon)
        Column(Modifier.weight(1f)) {
            Text(app.name)
            Text(state, color = FokuszAppearance.look.muted, style = MaterialTheme.typography.bodySmall)
        }
        Text(formattedTime(used.toLong()), style = MaterialTheme.typography.labelLarge)
    }
}

@Composable private fun AppIcon(icon: Drawable?) {
    val bitmap = remember(icon) { runCatching { icon?.toBitmap(72, 72)?.asImageBitmap() }.getOrNull() }
    if (bitmap != null) Image(bitmap, contentDescription = null, modifier = Modifier.size(32.dp))
}
