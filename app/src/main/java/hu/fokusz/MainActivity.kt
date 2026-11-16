package hu.fokusz

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import java.security.SecureRandom
import java.util.Locale
import kotlin.math.abs

private val Midnight = Color(0xFF090F1F)
private val Panel = Color(0xFF141E32)
private val Panel2 = Color(0xFF1B2740)
private val Mint = Color(0xFF7BE7C1)
private val Gold = Color(0xFFFFC78D)
private val Lilac = Color(0xFFB9AAFF)
private val Muted = Color(0xFFA7B4CC)
private val White = Color(0xFFF5F7FF)
private val Border = Color(0xFF2B3A55)

/** Valódi 52 lapos pakli; kriptográfiailag biztonságos keverés. */
object Shoe {
    val rnd = SecureRandom()
    private val cards = ArrayList<Int>()
    @Synchronized fun draw(): Int {
        if (cards.size < 15) {
            cards.clear()
            cards.addAll(0..51)
            cards.shuffle(rnd)
        }
        return cards.removeAt(cards.lastIndex)
    }
}
fun value(h: List<Int>): Int {
    var total = 0
    var aces = 0
    for (c in h) {
        val rank = c % 13 + 1
        if (rank == 1) { total += 11; aces++ } else total += minOf(rank, 10)
    }
    while (total > 21 && aces > 0) { total -= 10; aces-- }
    return total
}
fun rv(c: Int) = minOf(c % 13 + 1, 10)
fun name(c: Int) = listOf("A","2","3","4","5","6","7","8","9","10","J","Q","K")[c % 13] + "♠♥♦♣"[c / 13]
fun clock(sec: Int): String = String.format(Locale.ROOT, "%02d:%02d", sec / 60, sec % 60)
fun duration(sec: Int): String {
    val min = sec / 60; val rem = sec % 60
    return if (rem == 0) min.toString() + " perc"
    else if (min == 0) rem.toString() + " mp"
    else min.toString() + " p " + rem.toString() + " mp"
}
data class Hand(
    val cards: List<Int>,
    val bet: Int,
    val done: Boolean = false,
    val ace: Boolean = false,
    val res: String = ""
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = android.graphics.Color.rgb(9,15,31)
        window.navigationBarColor = android.graphics.Color.rgb(9,15,31)
        setContent {
            MaterialTheme(colorScheme = androidx.compose.material3.darkColorScheme(
                primary = Mint,
                onPrimary = Midnight,
                surface = Panel,
                background = Midnight,
                onBackground = White,
                onSurface = White
            )) {
                Surface(color = Midnight, modifier = Modifier.fillMaxSize()) { FokuszApp() }
            }
        }
    }
}

@Composable private fun SectionLabel(title: String, subtitle: String = "") {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, color = White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        if (subtitle.isNotEmpty()) Text(subtitle, color = Muted, fontSize = 13.sp)
    }
}

@Composable private fun GlassCard(
    modifier: Modifier = Modifier,
    colors: List<Color> = listOf(Panel, Panel),
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(Brush.linearGradient(colors))
            .border(1.dp, Border, RoundedCornerShape(24.dp))
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        content = content
    )
}

@Composable private fun PrimaryAction(text: String, enabled: Boolean = true, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.height(50.dp),
        shape = RoundedCornerShape(15.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = Mint,
            contentColor = Midnight,
            disabledContainerColor = Border,
            disabledContentColor = Muted
        )
    ) { Text(text, fontWeight = FontWeight.Bold, fontSize = 14.sp, maxLines = 1) }
}

@Composable private fun SoftAction(
    text: String,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
    highlight: Boolean = false,
    onClick: () -> Unit
) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.height(48.dp),
        shape = RoundedCornerShape(15.dp),
        border = BorderStroke(1.dp, if (highlight) Gold else Border),
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor = if (highlight) Color(0xFF3C3040) else Panel2,
            contentColor = if (highlight) Gold else White,
            disabledContentColor = Muted
        )
    ) { Text(text, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, maxLines = 1) }
}

@Composable private fun TimeHero(balance: Int, compact: Boolean = false) {
    GlassCard(colors = listOf(Color(0xFF183D42), Color(0xFF152A3E))) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("◉  IDŐEGYENLEG", color = Mint, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
            Spacer(Modifier.weight(1f))
            Text("● ÉLŐ", color = Mint.copy(alpha = .8f), fontSize = 11.sp)
        }
        Text(clock(balance), color = White, fontWeight = FontWeight.ExtraBold, fontSize = if (compact) 38.sp else 56.sp)
        Text("Ennyi időd van még a kiválasztott appokra", color = Muted, fontSize = 13.sp)
        val progress = (balance.toFloat() / Store.DAILY).coerceIn(0f, 1f)
        LinearProgressIndicator(
            progress = { progress },
            modifier = Modifier.fillMaxWidth().height(8.dp).clip(CircleShape),
            color = Mint,
            trackColor = Color(0xFF33545C)
        )
        Row {
            Text("Napi alap: 30:00", color = Muted, fontSize = 12.sp)
            Spacer(Modifier.weight(1f))
            Text(if (balance > Store.DAILY) "Bónusz időd van" else "Maradék " + clock(balance), color = Mint, fontSize = 12.sp)
        }
    }
}

@Composable private fun NavTab(title: String, symbol: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Box(
        modifier
            .clip(RoundedCornerShape(16.dp))
            .background(if (selected) Panel2 else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(vertical = 14.dp, horizontal = 6.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(symbol + "  " + title,
            fontSize = 14.sp,
            color = if (selected) Mint else Muted,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            maxLines = 1)
    }
}

@Composable fun FokuszApp() {
    val ctx = LocalContext.current
    var tab by rememberSaveable { mutableStateOf(0) }
    var bal by remember { mutableStateOf(Store.bal(ctx)) }
    var isRunning by remember { mutableStateOf(FocusService.isRunning) }
    var usageOk by remember { mutableStateOf(UsageGuard.allowed(ctx)) }
    var overlayOk by remember { mutableStateOf(Settings.canDrawOverlays(ctx)) }

    var minutes by rememberSaveable { mutableStateOf("1") }
    var seconds by rememberSaveable { mutableStateOf("00") }
    val m = minutes.toIntOrNull()
    val s = seconds.toIntOrNull()
    val stake = if (m != null && s != null && m in 0..999 && s in 0..59) m * 60 + s else 0
    val wagerValid = stake > 0 && stake <= bal

    var selectedSide by remember { mutableStateOf("") }
    var coinResult by remember { mutableStateOf("") }
    var coinNote by remember { mutableStateOf("Válassz egy oldalt a dobáshoz!") }
    var coinWon by remember { mutableStateOf<Boolean?>(null) }
    var coinFlipping by remember { mutableStateOf(false) }
    var flipCounter by remember { mutableStateOf(0) }

    var bjMessage by remember { mutableStateOf("Válassz tétet, majd kezdj egy új kört.") }
    var hands by remember { mutableStateOf(listOf<Hand>()) }
    var dealer by remember { mutableStateOf(listOf<Int>()) }
    var active by remember { mutableStateOf(0) }
    var live by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        while (true) {
            bal = Store.bal(ctx)
            isRunning = FocusService.isRunning
            usageOk = UsageGuard.allowed(ctx)
            overlayOk = Settings.canDrawOverlays(ctx)
            delay(1000)
        }
    }

    LaunchedEffect(flipCounter) {
        if (flipCounter > 0) {
            delay(550)
            coinFlipping = false
        }
    }

    fun coinFlip(choice: String) {
        if (!wagerValid || coinFlipping || live) return
        val bet = stake
        Store.add(ctx, -bet)
        // Minden dobásnál független, 50-50% esélyű véletlenszám.
        val result = if (Shoe.rnd.nextBoolean()) "Fej" else "Írás"
        val won = result == choice
        if (won) Store.add(ctx, bet * 2)
        bal = Store.bal(ctx)
        selectedSide = choice
        coinResult = result
        coinWon = won
        coinNote = if (won) "Nyertél! +" + duration(bet) else "Most nem sikerült. −" + duration(bet)
        coinFlipping = true
        flipCounter++
    }

    fun finish(hs: List<Hand>) {
        val dc = dealer.toMutableList()
        if (hs.any { value(it.cards) <= 21 }) while (value(dc) < 17) dc.add(Shoe.draw())
        dealer = dc
        live = false
        var paid = 0
        var spent = 0
        val dv = value(dc)
        hands = hs.map { h ->
            val pv = value(h.cards)
            val payout = when {
                pv > 21 -> 0
                dv > 21 || pv > dv -> h.bet * 2
                pv == dv -> h.bet
                else -> 0
            }
            paid += payout
            spent += h.bet
            h.copy(res = when {
                payout > h.bet -> "GYŐZELEM · +" + duration(h.bet)
                payout == h.bet -> "DÖNTETLEN"
                else -> "VESZTETT · −" + duration(h.bet)
            })
        }
        Store.add(ctx, paid)
        bal = Store.bal(ctx)
        val difference = paid - spent
        bjMessage = if (difference >= 0) "Kör eredménye: +" + duration(difference)
                    else "Kör eredménye: −" + duration(abs(difference))
    }

    fun next(hs: List<Hand>) {
        hands = hs
        val first = hs.indexOfFirst { !it.done }
        if (first >= 0) active = first else finish(hs)
        bal = Store.bal(ctx)
    }

    fun deal() {
        if (!wagerValid || live) return
        val bet = stake
        Store.add(ctx, -bet)
        val player = listOf(Shoe.draw(), Shoe.draw())
        val dc = listOf(Shoe.draw(), Shoe.draw())
        dealer = dc
        active = 0
        val playerBJ = value(player) == 21
        val dealerBJ = value(dc) == 21
        if (playerBJ || dealerBJ) {
            val paid = if (playerBJ && dealerBJ) bet else if (playerBJ) bet * 5 / 2 else 0
            Store.add(ctx, paid)
            live = false
            bjMessage = when {
                playerBJ && dealerBJ -> "Mindkét fél blackjack · döntetlen"
                playerBJ -> "BLACKJACK! +" + duration(paid - bet)
                else -> "Az osztónak blackjackje van · −" + duration(bet)
            }
            hands = listOf(Hand(player, bet, done = true, res = bjMessage))
        } else {
            hands = listOf(Hand(player, bet))
            live = true
            bjMessage = "Te következel! Kérj lapot, vagy állj meg."
        }
        bal = Store.bal(ctx)
    }

    fun replaceHand(h: Hand): List<Hand> =
        hands.toMutableList().also { it[active] = h }
    fun hit() {
        val h = hands[active]
        val cs = h.cards + Shoe.draw()
        next(replaceHand(h.copy(cards = cs, done = value(cs) >= 21)))
    }
    fun stand() { next(replaceHand(hands[active].copy(done = true))) }
    fun doubleDown() {
        val h = hands[active]
        if (bal < h.bet) return
        Store.add(ctx, -h.bet)
        next(replaceHand(h.copy(cards = h.cards + Shoe.draw(), bet = h.bet * 2, done = true)))
    }
    fun split() {
        val h = hands[active]
        if (bal < h.bet || hands.size >= 4 || h.cards.size != 2 || rv(h.cards[0]) != rv(h.cards[1])) return
        Store.add(ctx, -h.bet)
        val ace = h.cards[0] % 13 == 0
        val a = Hand(listOf(h.cards[0], Shoe.draw()), h.bet, done = ace, ace = ace)
        val b = Hand(listOf(h.cards[1], Shoe.draw()), h.bet, done = ace, ace = ace)
        val nextHands = hands.toMutableList()
        nextHands[active] = a
        nextHands.add(active + 1, b)
        next(nextHands.map { if (value(it.cards) >= 21) it.copy(done = true) else it })
    }

    val current = hands.getOrNull(active)
    val canMove = live && current != null && !current.done
    val canDouble = canMove && current!!.cards.size == 2 && bal >= current.bet
    val canSplit = canMove && current!!.cards.size == 2 && rv(current.cards[0]) == rv(current.cards[1]) &&
        hands.size < 4 && !current.ace && bal >= current.bet
    val betEnabled = wagerValid && !live && !coinFlipping

    Column(
        Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()
            .verticalScroll(rememberScrollState()).padding(horizontal = 18.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(45.dp).clip(RoundedCornerShape(14.dp))
                .background(Brush.linearGradient(listOf(Mint, Color(0xFF3AAEAD)))),
                contentAlignment = Alignment.Center) {
                Text("◈", fontSize = 30.sp, color = Midnight, fontWeight = FontWeight.Black)
            }
            Spacer(Modifier.size(12.dp))
            Column {
                Text("FÓKUSZ", color = White, fontSize = 23.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 2.sp)
                Text("A te időd. A te szabályaid.", color = Muted, fontSize = 12.sp)
            }
        }

        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp))
                .background(Color(0xFF101A2C)).padding(5.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            NavTab("Gambling", "♠", tab == 0, Modifier.weight(1f)) { tab = 0 }
            NavTab("Képernyőidő", "◷", tab == 1, Modifier.weight(1f)) { tab = 1 }
        }

        if (tab == 0) {
            TimeHero(bal, compact = true)
            SectionLabel("Játssz az időddel", "Nincs valódi pénz — minden tét időegység.")
            GlassCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("TÉT BEÁLLÍTÁSA", color = Muted, fontSize = 12.sp, letterSpacing = 1.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.weight(1f))
                    Text(clock(stake), color = Mint, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = minutes,
                        onValueChange = { if (it.length <= 3 && it.all(Char::isDigit)) minutes = it },
                        label = { Text("Perc") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        shape = RoundedCornerShape(16.dp)
                    )
                    OutlinedTextField(
                        value = seconds,
                        onValueChange = { if (it.length <= 2 && it.all(Char::isDigit)) seconds = it },
                        label = { Text("Másodperc") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        shape = RoundedCornerShape(16.dp)
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(30, 60, 300).forEach { v ->
                        SoftAction(
                            text = if (v < 60) "00:30" else clock(v),
                            modifier = Modifier.weight(1f),
                            onClick = { if (!live) { minutes = (v / 60).toString(); seconds = String.format(Locale.ROOT, "%02d", v % 60) } }
                        )
                    }
                }
                if (!wagerValid) {
                    Text(
                        if (stake <= 0) "Adj meg érvényes, legalább 1 másodperces tétet (0–59 mp)!"
                        else "Ehhez a téthez nincs elegendő időegyenleged.",
                        color = Gold, fontSize = 12.sp
                    )
                }
                if (live) Text("A tét egy blackjack-kör közben nem módosítható.", color = Muted, fontSize = 12.sp)
            }

            GlassCard(colors = listOf(Color(0xFF332941), Panel)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("🪙  ÉRMEDOBÁS", color = Gold, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                    Spacer(Modifier.weight(1f))
                    Text("50% / 50%", color = Muted, fontSize = 12.sp)
                }
                Box(Modifier.fillMaxWidth().padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
                    Box(
                        Modifier.size(118.dp).clip(CircleShape)
                            .background(Brush.radialGradient(listOf(Color(0xFFFFE6AF), Gold, Color(0xFFBD8649))))
                            .border(5.dp, Color(0xFF8E643B), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            if (coinFlipping) "◈" else if (coinResult == "Fej") "F" else if (coinResult == "Írás") "Í" else "↻",
                            color = Color(0xFF513624), fontSize = 55.sp, fontWeight = FontWeight.Black
                        )
                    }
                }
                Text(
                    if (coinFlipping) "PÖRÖG AZ ÉRME…" else if (coinResult.isEmpty()) "FEJ VAGY ÍRÁS?" else "Eredmény: " + coinResult,
                    modifier = Modifier.fillMaxWidth(), color = White, fontSize = 18.sp,
                    fontWeight = FontWeight.Bold, textAlign = TextAlign.Center
                )
                Text(
                    if (coinFlipping) "Az eredmény hamarosan megjelenik."
                    else if (selectedSide.isEmpty()) coinNote
                    else "Tipped: " + selectedSide + "  •  " + coinNote,
                    modifier = Modifier.fillMaxWidth(),
                    color = if (!coinFlipping && coinWon == true) Mint else if (!coinFlipping && coinWon == false) Gold else Muted,
                    fontSize = 13.sp, textAlign = TextAlign.Center
                )
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    PrimaryAction("FEJ", enabled = betEnabled, modifier = Modifier.weight(1f)) { coinFlip("Fej") }
                    SoftAction("ÍRÁS", enabled = betEnabled, modifier = Modifier.weight(1f), highlight = true) { coinFlip("Írás") }
                }
                Text("Minden dobás független véletlen esemény. A nyeremény nettó +1× tét.",
                    color = Muted, fontSize = 11.sp)
            }

            GlassCard(colors = listOf(Color(0xFF242741), Panel)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("♠  BLACKJACK", color = Lilac, fontWeight = FontWeight.Bold, fontSize = 19.sp)
                    Spacer(Modifier.weight(1f))
                    Text("3:2 BLACKJACK", color = Muted, fontSize = 11.sp)
                }
                if (hands.isEmpty()) {
                    Text("Gyűjts 21 pontot, vagy kerülj közelebb hozzá, mint az osztó.",
                        color = Muted, fontSize = 14.sp)
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("OSZTÓ", color = Muted, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.weight(1f))
                        Text(if (live) "? PONT" else value(dealer).toString() + " PONT", color = Gold, fontSize = 12.sp)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                        if (live) {
                            PlayingCard(dealer.first())
                            PlayingCard(null)
                        } else dealer.forEach { PlayingCard(it) }
                    }
                    Spacer(Modifier.height(4.dp))
                    hands.forEachIndexed { i, h ->
                        val selected = live && active == i && !h.done
                        Column(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(17.dp))
                                .background(if (selected) Color(0xFF27423F) else Color(0xFF202B40))
                                .border(1.dp, if (selected) Mint else Border, RoundedCornerShape(17.dp))
                                .padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(7.dp)
                        ) {
                            Row {
                                Text((if (selected) "▶ " else "") + "KÉZ " + (i + 1),
                                    color = if (selected) Mint else Muted, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                Spacer(Modifier.weight(1f))
                                Text(value(h.cards).toString() + " PONT · " + clock(h.bet),
                                    color = White, fontSize = 12.sp)
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                                h.cards.forEach { PlayingCard(it) }
                            }
                            if (h.res.isNotEmpty()) Text(h.res, color = if (h.res.startsWith("GYŐZ")) Mint else Gold, fontSize = 12.sp)
                        }
                    }
                }
                Text(bjMessage, color = Muted, fontSize = 13.sp)
                PrimaryAction("ÚJ OSZTÁS  •  " + clock(stake), enabled = betEnabled,
                    modifier = Modifier.fillMaxWidth()) { deal() }
                Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    SoftAction("LAP", enabled = canMove, modifier = Modifier.weight(1f)) { hit() }
                    SoftAction("MEGÁLL", enabled = canMove, modifier = Modifier.weight(1f)) { stand() }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    SoftAction("DUPLÁZÁS", enabled = canDouble, modifier = Modifier.weight(1f)) { doubleDown() }
                    SoftAction("SPLIT", enabled = canSplit, modifier = Modifier.weight(1f), highlight = true) { split() }
                }
                Text("Split: azonos értékű kártyáknál, legfeljebb 4 kézig. Az új kézhez külön fedezet kell.",
                    color = Muted, fontSize = 11.sp)
            }
        } else {
            SectionLabel("Képernyőidő", "Az időkereted, engedélyeid és védelmed.")
            TimeHero(bal)
            GlassCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("VÉDELEM ÁLLAPOTA", color = Muted, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.weight(1f))
                    Text(if (isRunning && usageOk && overlayOk) "● AKTÍV" else "● NEM AKTÍV",
                        color = if (isRunning && usageOk && overlayOk) Mint else Gold,
                        fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
                Text(
                    "A Fókusz az előtérben használt alkalmazások idejét figyeli. Ha elfogy, takaróképernyővel korlátozza őket.",
                    color = Muted, fontSize = 13.sp
                )
                if (!usageOk) {
                    Text("Hiányzik a használati adatokhoz való hozzáférés.", color = Gold, fontSize = 12.sp)
                    PrimaryAction("Használati adatok engedélyezése", modifier = Modifier.fillMaxWidth()) {
                        ctx.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
                    }
                }
                if (!overlayOk) {
                    Text("Hiányzik a megjelenítés más alkalmazások felett.", color = Gold, fontSize = 12.sp)
                    PrimaryAction("Megjelenítés engedélyezése", modifier = Modifier.fillMaxWidth()) {
                        ctx.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + ctx.packageName)))
                    }
                }
                if (usageOk && overlayOk) {
                    if (isRunning) {
                        SoftAction("Védelem leállítása", modifier = Modifier.fillMaxWidth()) {
                            ctx.stopService(Intent(ctx, FocusService::class.java))
                            isRunning = false
                        }
                    } else {
                        PrimaryAction("Védelem elindítása", modifier = Modifier.fillMaxWidth()) {
                            try {
                                ctx.startForegroundService(Intent(ctx, FocusService::class.java))
                                isRunning = true
                            } catch (e: Exception) {
                                bjMessage = "Nem sikerült elindítani a védelmet: " + (e.message ?: "ismeretlen hiba")
                            }
                        }
                    }
                }
            }
            GlassCard {
                Text("FIGYELT ALKALMAZÁSOK", color = Muted, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                Text("TikTok  •  Instagram  •  YouTube", color = White, fontWeight = FontWeight.SemiBold)
                Text("Snapchat  •  Facebook  •  Reddit  •  X", color = White, fontWeight = FontWeight.SemiBold)
                Text("A napi 30 perc minden figyelt alkalmazásra közös. A játékkal szerzett idő hozzáadódik.",
                    color = Muted, fontSize = 12.sp)
            }
            GlassCard {
                Text("TIPP XIAOMI TELEFONHOZ", color = Mint, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                Text("A stabil háttérfigyeléshez állíts be korlátozás nélküli akkumulátorhasználatot a Fókusz alkalmazásinformációs oldalán.",
                    color = Muted, fontSize = 13.sp)
                Text("Minden adat helyben marad a telefonon. A Fókusz nem használ valódi pénzt.",
                    color = Muted, fontSize = 12.sp)
            }
        }
        Spacer(Modifier.height(14.dp))
        Text("FÓKUSZ  •  1.2", color = Muted.copy(alpha = .6f),
            modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center, fontSize = 11.sp)
        Spacer(Modifier.height(8.dp))
    }
}

@Composable private fun PlayingCard(card: Int?) {
    val front = card != null
    val suitRed = front && card!! / 13 in listOf(1,2)
    Column(
        Modifier.size(width = 45.dp, height = 62.dp).clip(RoundedCornerShape(8.dp))
            .background(if (front) White else Color(0xFF5861A8))
            .border(1.dp, if (front) Color(0xFFDDE3F0) else Lilac, RoundedCornerShape(8.dp)),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(if (front) (card!! % 13 + 1).let {
            listOf("A","2","3","4","5","6","7","8","9","10","J","Q","K")[it - 1]
        } else "✦",
            fontSize = 17.sp, fontWeight = FontWeight.ExtraBold,
            color = if (!front) White else if (suitRed) Color(0xFFC43A5D) else Midnight)
        Text(if (front) "♠♥♦♣"[card!! / 13].toString() else "✦",
            fontSize = 17.sp, color = if (!front) White else if (suitRed) Color(0xFFC43A5D) else Midnight)
    }
}
