package hu.fokusz

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

@Composable internal fun TimeBankPanel() {
    val context = LocalContext.current
    var wallet by remember { mutableIntStateOf(Store.bal(context)) }
    var stock by remember { mutableStateOf(Store.bank(context)) }
    var minutes by rememberSaveable { mutableStateOf("") }
    var seconds by rememberSaveable { mutableStateOf("") }
    var note by rememberSaveable { mutableStateOf("") }

    fun refresh() {
        wallet = Store.bal(context)
        stock = Store.bank(context)
    }
    fun transfer(amount: Int, deposit: Boolean) {
        val done = if (deposit) TimeBank.deposit(context, amount) else TimeBank.withdraw(context, amount)
        note = if (!done) "A megadott összeg nem mozgatható át."
            else if (deposit) "Betéve: ${duration(amount)}." else "Kivéve: ${duration(amount)}."
        refresh()
    }
    LaunchedEffect(Unit) {
        while (true) { refresh(); delay(1000L) }
    }
    val next = stock.addRemainder(wallet).advance(stock.day.plusDays(1))
    val amount = ((minutes.toLongOrNull() ?: 0) * 60 + (seconds.toLongOrNull() ?: 0))
        .takeIf { (seconds.toIntOrNull() ?: 0) in 0..59 && it in 1..Store.MAX_SAFE_SECONDS.toLong() }
        ?.toInt() ?: 0

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Időbank", style = MaterialTheme.typography.headlineSmall)
            Text(duration(stock.ready), style = MaterialTheme.typography.headlineLarge, color = MaterialTheme.colorScheme.primary)
            Text("Saját képernyőidő: ${duration(wallet)}")
            if (stock.pending > 0) Text("Korábbi befizetés, holnaptól kivehető: ${duration(stock.pending)}")
            if (stock.interest > 0) Text("Ma jóváírt kamat: +${duration(stock.interest)}")
            Text("Éjfélkor a saját keret maradéka a bankba kerül, és a bank 10%-kal nő.")
            Text("Holnapi becslés: ${duration(next.ready)} · kamat: +${duration(next.interest)}")
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(minutes, { minutes = it.filter(Char::isDigit).take(8) },
            label = { Text("Perc") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.weight(1f), singleLine = true)
        OutlinedTextField(seconds, { seconds = it.filter(Char::isDigit).take(2) },
            label = { Text("Másodperc") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.weight(1f), singleLine = true)
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(enabled = amount in 1..wallet, onClick = { transfer(amount, true) }, modifier = Modifier.weight(1f)) {
            Text("Beteszem")
        }
        OutlinedButton(enabled = amount in 1..stock.ready, onClick = { transfer(amount, false) }, modifier = Modifier.weight(1f)) {
            Text("Kiveszem")
        }
    }
    OutlinedButton(enabled = wallet > 0, onClick = { transfer(wallet, true) }, modifier = Modifier.fillMaxWidth()) {
        Text("Teljes saját keret betétele")
    }
    OutlinedButton(enabled = stock.ready > 0, onClick = { transfer(stock.ready, false) }, modifier = Modifier.fillMaxWidth()) {
        Text("Teljes banki összeg kivétele")
    }
    if (note.isNotBlank()) Text(note)
}
