package hu.fokusz

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.abs

@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun TimeBankPanel(onTeam: () -> Unit) {
    val context = LocalContext.current
    val look = FokuszAppearance.look
    var wallet by remember { mutableIntStateOf(Store.bal(context)) }
    var stock by remember { mutableStateOf(Store.bank(context)) }
    var minutes by rememberSaveable { mutableStateOf("") }
    var seconds by rememberSaveable { mutableStateOf("") }
    var operation by rememberSaveable { mutableStateOf<String?>(null) }
    var note by rememberSaveable { mutableStateOf("") }
    var showHistory by rememberSaveable { mutableStateOf(false) }
    var ledger by remember { mutableStateOf(emptyList<ScreenTimeLedger.Entry>()) }

    fun refresh() {
        wallet = Store.bal(context)
        stock = Store.bank(context)
        if (showHistory) ledger = ScreenTimeLedger.recent(context, 100).filter { it.reason in BANK_REASONS }
    }
    fun transfer(direction: String, amount: Int) {
        if (operation != direction) return
        val deposit = direction == "deposit"
        val done = if (deposit) TimeBank.deposit(context, amount) else TimeBank.withdraw(context, amount)
        note = if (!done) "A megadott összeg nem mozgatható át."
            else if (deposit) "Betéve ${formattedTime(amount.toLong())}." else "Kivéve ${formattedTime(amount.toLong())}."
        if (done) {
            operation = null
            minutes = ""
            seconds = ""
        }
        refresh()
    }
    LaunchedEffect(showHistory) {
        while (true) { refresh(); delay(1000L) }
    }

    val next = stock.addRemainder(wallet).advance(stock.day.plusDays(1))
    FokuszHeading("Időbank", "Saját bank")
    FokuszCard(color = look.hero) {
        Text("Kivehető banki egyenleg", color = look.muted, style = MaterialTheme.typography.labelLarge)
        LargeTime(stock.ready)
        if (stock.pending > 0) Text("Holnaptól kivehető még ${formattedTime(stock.pending.toLong())}.")
        if (stock.interest > 0) Text("Ma jóváírt kamat +${formattedTime(stock.interest.toLong())}")
        Text("A napi maradék éjfélkor kerül a bankba. A napi kamat 10%.", color = look.muted)
        val weeklyTonight = stock.day.dayOfWeek == java.time.DayOfWeek.SUNDAY &&
            WeeklyWallet.status?.week == WeeklyWalletClock.monday(stock.day).toString()
        if (weeklyTonight) Text("A hétfői banki egyenleget a heti zárás is befolyásolja.", color = look.muted)
        else Text("Holnapi becslés +${formattedTime(next.interest.toLong())} kamat", color = look.muted)
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FokuszAction("Beteszek", Modifier.weight(1f), enabled = wallet > 0) { operation = "deposit"; note = "" }
        FokuszAction("Kiveszek", Modifier.weight(1f), enabled = stock.ready > 0, primary = false) {
            operation = "withdraw"; note = ""
        }
    }
    Text("Szabadon felhasználható idő ${formattedTime(wallet.toLong())}", style = MaterialTheme.typography.bodyLarge)
    if (note.isNotBlank() && operation == null) Text(note)
    FokuszAction("Közös bank a Csapat menüben", Modifier.fillMaxWidth(), primary = false, onClick = onTeam)
    WeeklySettlementPanel()
    FokuszAction(if (showHistory) "Mozgások elrejtése" else "Bankmozgások", Modifier.fillMaxWidth(), primary = false) {
        showHistory = !showHistory
    }
    if (showHistory) FokuszCard {
        val format = remember { DateTimeFormatter.ofPattern("MM.dd. HH:mm").withZone(ZoneId.systemDefault()) }
        if (ledger.isEmpty()) Text("Még nincs mentett bankmozgás.")
        for (item in ledger) {
            val movement = if (item.reason.startsWith("Heti megtartás")) item.seconds else -item.seconds
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Column(Modifier.weight(1f)) {
                    Text(item.reason)
                    Text(format.format(Instant.ofEpochMilli(item.at)), color = look.muted,
                        style = MaterialTheme.typography.bodySmall)
                }
                Text((if (movement >= 0) "+" else "−") + formattedTime(abs(movement)))
            }
            HorizontalDivider(color = look.outline.copy(alpha = .35f))
        }
    }

    val direction = operation
    if (direction != null) {
        val deposit = direction == "deposit"
        val available = if (deposit) wallet else stock.ready
        val amount = (minutes.toLongOrNull() ?: 0L) * 60L + (seconds.toLongOrNull() ?: 0L)
        val valid = amount in 1L..available.toLong() && (seconds.toIntOrNull() ?: 0) in 0..59
        ModalBottomSheet(onDismissRequest = { operation = null; note = "" }) {
            Column(Modifier.fillMaxWidth().imePadding().verticalScroll(rememberScrollState()).padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(if (deposit) "Betét a saját bankba" else "Kivét a saját bankból", style = MaterialTheme.typography.headlineSmall)
                Text("Átvezethető ${formattedTime(available.toLong())}", color = look.muted)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(minutes, { minutes = it.filter(Char::isDigit).take(8) }, Modifier.weight(1f),
                        label = { Text("Perc") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                    OutlinedTextField(seconds, { seconds = it.filter(Char::isDigit).take(2) }, Modifier.weight(1f),
                        label = { Text("Másodperc") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                }
                FokuszAction(if (valid) "Átvezetem · ${formattedTime(amount)}" else "Átvezetés jóváhagyása",
                    Modifier.fillMaxWidth(), enabled = valid) { transfer(direction, amount.toInt()) }
                if (note.isNotBlank()) Text(note)
            }
        }
    }
}

private val BANK_REASONS = setOf("Befizetés az időbankba", "Kivét az időbankból",
    "Napi maradék átvezetése a bankba", "Heti megtartás", "Heti megtartás bankkamata")
