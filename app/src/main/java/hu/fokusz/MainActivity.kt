package hu.fokusz

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import java.security.SecureRandom
import kotlin.math.abs

/** Valódi 52 lapos pakli, kihúzott lapok nem kerülnek vissza; 15 lap alatt újrakeveri. */
object Shoe {
    val rnd = SecureRandom()
    private val cards = ArrayList<Int>()
    fun draw(): Int {
        if (cards.size < 15) { cards.clear(); cards.addAll(0..51); cards.shuffle(rnd) }
        return cards.removeAt(cards.lastIndex)
    }
}
fun value(h: List<Int>): Int {
    var t = 0; var a = 0
    for (c in h) { val k = c % 13 + 1; if (k == 1) { t += 11; a++ } else t += minOf(k, 10) }
    while (t > 21 && a > 0) { t -= 10; a-- }
    return t
}
fun rv(c: Int) = minOf(c % 13 + 1, 10)
fun name(c: Int) = listOf("A","2","3","4","5","6","7","8","9","10","J","Q","K")[c % 13] + "♠♥♦♣"[c / 13]
fun fmt(sec: Int) = if (sec % 60 == 0) "${sec / 60}" else "%.1f".format(sec / 60f)
fun enabled(c: Context) = (Settings.Secure.getString(c.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: "").contains(c.packageName)

data class Hand(val cards: List<Int>, val bet: Int, val done: Boolean = false, val ace: Boolean = false, val res: String = "")

class MainActivity : ComponentActivity() {
    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        setContent { MaterialTheme { Surface(Modifier.fillMaxSize()) { App() } } }
    }
}

@Composable fun App() {
    val ctx = LocalContext.current
    var bal by remember { mutableStateOf(Store.bal(ctx)) }
    var on by remember { mutableStateOf(enabled(ctx)) }
    var stake by remember { mutableStateOf(5) }
    var msg by remember { mutableStateOf("") }
    var hands by remember { mutableStateOf(listOf<Hand>()) }
    var d by remember { mutableStateOf(listOf<Int>()) }
    var act by remember { mutableStateOf(0) }
    var live by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { while (true) { bal = Store.bal(ctx); on = enabled(ctx); delay(1000) } }
    val canBet = !live && bal / 60 >= stake

    fun dealerPlay(hs: List<Hand>) {
        val dd = d.toMutableList()
        if (hs.any { value(it.cards) <= 21 }) while (value(dd) < 17) dd.add(Shoe.draw())
        d = dd; live = false
        val dv = value(dd)
        var paid = 0; var staked = 0
        hands = hs.map { h ->
            val pv = value(h.cards)
            val pay = when {
                pv > 21 -> 0
                dv > 21 || pv > dv -> h.bet * 2
                pv == dv -> h.bet
                else -> 0
            }
            paid += pay; staked += h.bet
            h.copy(res = when {
                pay > h.bet -> "Nyert +${fmt(h.bet)} perc"
                pay == h.bet -> "Döntetlen"
                else -> "Vesztett −${fmt(h.bet)} perc"
            })
        }
        Store.add(ctx, paid)
        val net = paid - staked
        msg = "Összesen: " + (if (net >= 0) "+" else "−") + fmt(abs(net)) + " perc"
    }
    fun next(hs: List<Hand>) {
        hands = hs
        val i = hs.indexOfFirst { !it.done }
        if (i >= 0) act = i else dealerPlay(hs)
        bal = Store.bal(ctx)
    }
    fun deal() {
        val bet = stake * 60
        Store.add(ctx, -bet)
        val np = listOf(Shoe.draw(), Shoe.draw()); val nd = listOf(Shoe.draw(), Shoe.draw())
        d = nd; msg = ""; act = 0
        val pn = value(np) == 21; val dn = value(nd) == 21
        if (pn || dn) {
            val pay = if (pn && dn) bet else if (pn) bet * 5 / 2 else 0
            Store.add(ctx, pay); live = false
            val r = if (pn && dn) "Döntetlen (mindkettő blackjack)"
                else if (pn) "BLACKJACK 3:2! +${fmt(pay - bet)} perc"
                else "Az osztónak blackjackje van. −${fmt(bet)} perc"
            hands = listOf(Hand(np, bet, done = true, res = r))
        } else { hands = listOf(Hand(np, bet)); live = true }
        bal = Store.bal(ctx)
    }
    fun upd(h: Hand): List<Hand> = hands.toMutableList().also { it[act] = h }
    fun hit() { val h = hands[act]; val c = h.cards + Shoe.draw(); next(upd(h.copy(cards = c, done = value(c) >= 21))) }
    fun stand() { next(upd(hands[act].copy(done = true))) }
    fun dbl() {
        val h = hands[act]; Store.add(ctx, -h.bet)
        next(upd(h.copy(cards = h.cards + Shoe.draw(), bet = h.bet * 2, done = true)))
    }
    fun split() {
        val h = hands[act]; Store.add(ctx, -h.bet)
        val ace = h.cards[0] % 13 == 0
        val a = Hand(listOf(h.cards[0], Shoe.draw()), h.bet, done = ace, ace = ace)
        val b = Hand(listOf(h.cards[1], Shoe.draw()), h.bet, done = ace, ace = ace)
        val hs = hands.toMutableList(); hs[act] = a; hs.add(act + 1, b)
        next(hs.map { if (value(it.cards) >= 21) it.copy(done = true) else it })
    }

    val cur = hands.getOrNull(act)
    val can = live && cur != null && !cur.done
    val two = can && cur!!.cards.size == 2
    val canDbl = two && bal >= cur!!.bet
    val canSplit = two && rv(cur!!.cards[0]) == rv(cur.cards[1]) && hands.size < 4 && !cur.ace && bal >= cur.bet

    Column(
        Modifier.verticalScroll(rememberScrollState()).statusBarsPadding().padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("🔒 Fókusz", fontSize = 22.sp)
        Text("%d:%02d".format(bal / 60, bal % 60), fontSize = 56.sp)
        Text("Mai időkeret a figyelemelterelő appokra. Minden nap 30 percet kapsz.")
        if (!on) {
            Text("⚠️ A védelem ki van kapcsolva!")
            Button(onClick = { ctx.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }) { Text("Bekapcsolás") }
        }
        Text("Tét (perc):")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(1, 5, 10, 15).forEach { v ->
                if (v == stake) Button(onClick = {}) { Text("$v") }
                else OutlinedButton(onClick = { if (!live) stake = v }) { Text("$v") }
            }
        }
        Text("🪙 Érmedobás")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("Fej", "Írás").forEach { s ->
                Button(enabled = canBet, onClick = {
                    Store.add(ctx, -stake * 60)
                    val r = if (Shoe.rnd.nextBoolean()) "Fej" else "Írás"
                    if (r == s) { Store.add(ctx, stake * 120); msg = "$r! Nyertél: +$stake perc" }
                    else msg = "$r. Vesztettél: −$stake perc"
                    bal = Store.bal(ctx)
                }) { Text(s) }
            }
        }
        Text("🃏 Blackjack")
        if (hands.isNotEmpty()) {
            Text("Osztó: " + if (live) name(d[0]) + " ?" else d.joinToString(" ") { name(it) } + " (${value(d)})")
            hands.forEachIndexed { i, h ->
                val here = live && i == act && !h.done
                Text(
                    (if (here) "▶ " else "• ") + "${i + 1}. kéz: " + h.cards.joinToString(" ") { name(it) } +
                        " (${value(h.cards)}) · tét ${fmt(h.bet)} perc" + (if (h.res.isNotEmpty()) "\n   ${h.res}" else ""),
                    fontWeight = if (here) FontWeight.Bold else FontWeight.Normal
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(enabled = canBet, onClick = { deal() }) { Text("Osztás") }
            Button(enabled = can, onClick = { hit() }) { Text("Lap") }
            Button(enabled = can, onClick = { stand() }) { Text("Megáll") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(enabled = canDbl, onClick = { dbl() }) { Text("Duplázás") }
            Button(enabled = canSplit, onClick = { split() }) { Text("Split") }
        }
        Text(msg, fontSize = 18.sp)
        Text("Nyerés 1:1, blackjack 3:2, döntetlennél visszajár a tét. Duplázás és split külön tétet kér.")
    }
}
