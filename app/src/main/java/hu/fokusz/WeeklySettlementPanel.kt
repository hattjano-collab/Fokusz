package hu.fokusz

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.format.DateTimeFormatter

internal fun weekAmount(tenths: Long): String {
    val whole = tenths / 10L
    val fraction = tenths % 10L
    if (fraction == 0L) return formattedTime(whole)
    val heads = listOf(
        (whole / 3600L).takeIf { it > 0 }?.let { "$it ó" },
        (whole / 60L % 60L).takeIf { it > 0 }?.let { "$it p" },
        "${whole % 60L},$fraction mp"
    )
    return heads.filterNotNull().joinToString(" ")
}

@Composable internal fun WeeklySettlementPanel() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val remote = WeeklyWallet.status
    val problem = WeeklyWallet.message
    val look = FokuszAppearance.look
    var show by rememberSaveable { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var historyOpen by rememberSaveable { mutableStateOf(false) }
    var shownResult by rememberSaveable { mutableStateOf<String?>(null) }
    val deadlineFormat = remember {
        DateTimeFormatter.ofPattern("yyyy.MM.dd. HH:mm:ss").withZone(WeeklyWalletClock.zone)
    }
    val latest = remote?.receipts?.firstOrNull()
    LaunchedEffect(latest?.week) {
        if (latest != null && WeeklyWalletClock.today().dayOfWeek == java.time.DayOfWeek.MONDAY &&
            WeeklyWalletClock.monday(WeeklyWalletClock.today()).minusWeeks(1).toString() == latest.week &&
            shownResult != latest.week) show = true
    }

    fun submit(mode: WeeklyMode? = null) {
        if (busy) return
        busy = true
        scope.launch {
            try { WeeklyWallet.synchronize(ctx, mode, remote?.week) }
            finally { busy = false }
        }
    }

    FokuszCard {
        Text("Heti elszámolás", style = MaterialTheme.typography.headlineSmall)
        Text("Vasárnap 23:59:59-kor, magyar idő szerint zár a hét. A számítás alapja a saját szabad időd és a személyes bankod összege.")
        FokuszAction(if (show) "Heti részletek elrejtése" else "Heti részletek és választás",
            Modifier.fillMaxWidth(), primary = false) {
            show = !show
            if (show && remote == null) submit()
        }
        if (show) {
            if (remote != null) {
                Text("Határidő · ${deadlineFormat.format(Instant.ofEpochMilli(remote.cutoff - 1000))}", color = look.muted)
                Text("Az aktuális egyenleg alapján. A százalékot a záráskori helyezés dönti el.", color = look.muted)
            } else {
                Text("A szerver visszaigazolása után választhatsz. Alapértelmezésként a biztos megtartás él.", color = look.muted)
            }
            val selected = remote?.mode ?: WeeklyMode.SAFE
            val percent = remote?.percent
            val payout = remote?.sureTenths?.let(::weekAmount) ?: "A heti helyezéstől függ"
            val chance = percent?.let { "$it%" } ?: "20% vagy 30%"
            val loss = percent?.let { "${100 - it}%" } ?: "80% vagy 70%"
            val maximum = remote?.closing?.let(::formattedTime) ?: "A teljes záróegyenleg"
            val canChoose = remote != null && !busy && problem.isEmpty()
            ChoiceCard(WeeklyMode.SAFE, selected, canChoose,
                "🛡", "Megtartható idő · $payout", "Megtartás · $chance",
                "A záróegyenlegből ennyi jár biztosan.") { submit(WeeklyMode.SAFE) }
            ChoiceCard(WeeklyMode.RISK, selected, canChoose,
                "⚄", "Nyerhető · $maximum", "Nyerés · $chance   Elvesztés · $loss",
                "Várható érték · $payout") { submit(WeeklyMode.RISK) }
            if (problem.isNotBlank()) Text(problem, color = look.muted)
            if (busy) Text("Egyeztetés a szerverrel…", color = look.muted)
            FokuszAction("Heti állapot frissítése", Modifier.fillMaxWidth(), enabled = !busy, primary = false) { submit() }
            val receipts = remote?.receipts.orEmpty()
            if (receipts.isNotEmpty()) {
                val first = receipts.first()
                LaunchedEffect(first.week) {
                    if (shownResult == first.week) return@LaunchedEffect
                    delay(275)
                    shownResult = first.week
                }
                WeeklyResult(first, shownResult == first.week)
                if (receipts.size > 1) {
                    FokuszAction(if (historyOpen) "Korábbi hetek elrejtése" else "Korábbi elszámolások",
                        Modifier.fillMaxWidth(), primary = false) { historyOpen = !historyOpen }
                }
                if (historyOpen) receipts.drop(1).forEach { receipt -> WeeklyResult(receipt, true) }
            }
        }
    }
}

@Composable private fun ChoiceCard(mode: WeeklyMode, selected: WeeklyMode, enabled: Boolean,
                                    symbol: String, first: String, second: String, third: String, onClick: () -> Unit) {
    val look = FokuszAppearance.look
    val isSelected = selected == mode
    Column(Modifier.fillMaxWidth().selectable(selected = isSelected, enabled = enabled,
        role = Role.RadioButton, onClick = onClick)
        .background(look.background, look.shape).border(look.border.dp, look.outline, look.shape).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Text("$symbol  ${mode.label}" + if (isSelected) "  ✓" else "", style = MaterialTheme.typography.titleLarge)
        Text(first)
        Text(second)
        Text(third, color = look.muted)
    }
}

@Composable private fun WeeklyResult(receipt: WeeklyReceipt, revealed: Boolean) {
    val scale by animateFloatAsState(targetValue = if (receipt.won == true && !revealed) .94f else 1f,
        animationSpec = tween(300), label = "Heti eredmény")
    val look = FokuszAppearance.look
    val c = LocalContext.current
    val applied = remember(receipt) { Store.weeklyAppliedTenths(c, receipt.week) }
    Column(Modifier.fillMaxWidth().graphicsLayer { scaleX = scale; scaleY = scale }
        .background(look.hero, look.shape).padding(14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Text("${receipt.week} · Heti eredmény", style = MaterialTheme.typography.titleLarge)
        Text(receipt.mode.label)
        Text(receipt.rank?.let { "Csapathelyezés · $it." } ?: "Csapat nélkül")
        Text("Biztosan megtartható · ${weekAmount(receipt.sureTenths)}")
        if (receipt.mode == WeeklyMode.RISK) {
            Text("Nyerési esély · ${receipt.percent}%")
            Text(if (receipt.won == true) "✦ Megtartottad a teljes záróegyenleget." else "A sorsoláson elfogyott a záróegyenleg.")
        }
        Text("A heti elszámolásban megtartott idő · ${weekAmount(receipt.retainedTenths)}")
        Text("Következő heti kezdőegyenleg · ${weekAmount(receipt.openingTenths)}")
        if (applied != null && applied != receipt.retainedTenths) {
            Text("Ezen a telefonon felszabadított idő · ${weekAmount(applied)}", color = look.muted)
        }
    }
}
