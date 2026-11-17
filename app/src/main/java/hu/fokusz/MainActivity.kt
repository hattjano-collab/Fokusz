package hu.fokusz

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.activity.compose.setContent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.width
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
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.animation.core.animateIntAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.graphicsLayer
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
import kotlinx.coroutines.coroutineScope
import java.security.SecureRandom
import java.util.Locale
import kotlin.math.abs

private val Midnight = Color(0xFF090F1F)
private val Panel = Color(0xFF141E32)
private val Panel2 = Color(0xFF1B2740)
private var Mint by mutableStateOf(Color(0xFF7BE7C1))
fun updateFokuszTheme(theme: String) {
    Mint = when (theme) {
        "lila" -> Color(0xFFB9AAFF)
        "arany" -> Color(0xFFFFC78D)
        else -> Color(0xFF7BE7C1)
    }
}
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
    private var requestedTab by mutableIntStateOf(-1)

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if(intent.hasExtra("focus_tab"))
            requestedTab=intent.getIntExtra("focus_tab",8)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        requestedTab=intent?.getIntExtra("focus_tab",-1) ?: -1
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
                Surface(color = Midnight, modifier = Modifier.fillMaxSize()) { FokuszApp(requestedTab) }
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
    val animatedBalance by animateIntAsState(targetValue=balance,
        animationSpec=tween(360),label="Élő időegyenleg")
    val animatedProgress by animateFloatAsState(
        targetValue=(balance.toFloat()/Store.DAILY).coerceIn(0f,1f),
        animationSpec=tween(520),label="Időkeret")
    GlassCard(colors = listOf(Color(0xFF183D42), Color(0xFF152A3E))) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("◉  IDŐEGYENLEG", color = Mint, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
            Spacer(Modifier.weight(1f))
            Text("● ÉLŐ", color = Mint.copy(alpha = .8f), fontSize = 11.sp)
        }
        Text(clock(animatedBalance), color = White, fontWeight = FontWeight.ExtraBold, fontSize = if (compact) 38.sp else 56.sp)
        Text("Ennyi időd van még a kiválasztott appokra", color = Muted, fontSize = 13.sp)
        val progress = animatedProgress
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
            fontSize = 11.sp,
            color = if (selected) Mint else Muted,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            maxLines = 1)
    }
}

@Composable fun FokuszApp(openTab:Int = -1) {
    val ctx = LocalContext.current
    var tab by rememberSaveable { mutableStateOf(0) }
    LaunchedEffect(openTab) { if(openTab in 0..10) tab=openTab }
    LaunchedEffect(Unit) { updateFokuszTheme(FocusPrefs.theme(ctx)) }
    var bal by remember { mutableStateOf(Store.bal(ctx)) }
    var isRunning by remember { mutableStateOf(FocusService.isRunning) }
    var usageOk by remember { mutableStateOf(UsageGuard.allowed(ctx)) }
    var overlayOk by remember { mutableStateOf(Settings.canDrawOverlays(ctx)) }

    var minutes by rememberSaveable { mutableStateOf("1") }
    var seconds by rememberSaveable { mutableStateOf("00") }
    var confirmAllIn by remember { mutableStateOf(false) }
    val m = minutes.toLongOrNull()
    val s = seconds.toLongOrNull()
    val proposedStake=if(m!=null&&s!=null&&m>=0L&&s in 0L..59L)
        m*60L+s else 0L
    val stake=if(proposedStake in 1L..Store.MAX_SAFE_SECONDS.toLong())
        proposedStake.toInt()else 0
    val wagerValid = stake > 0 && stake <= bal && GambleStats.canBet(ctx, stake)

    var selectedSide by remember { mutableStateOf("") }
    var coinResult by remember { mutableStateOf("") }
    var coinNote by remember { mutableStateOf("Válassz egy oldalt a dobáshoz!") }
    var coinWon by remember { mutableStateOf<Boolean?>(null) }
    var coinFlipping by remember { mutableStateOf(false) }
    var flipCounter by remember { mutableStateOf(0) }
    val coinRotation = remember { Animatable(0f) }
    val coinLift = remember { Animatable(0f) }

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
            coinRotation.snapTo(0f)
            coinLift.snapTo(0f)
            coroutineScope {
                launch {
                    coinRotation.animateTo(1800f, tween(durationMillis=1580, easing=LinearEasing))
                }
                launch {
                    coinLift.animateTo(-120f, tween(durationMillis=730, easing=FastOutSlowInEasing))
                    coinLift.animateTo(0f, tween(durationMillis=850, easing=FastOutSlowInEasing))
                }
            }
            coinFlipping = false
        }
    }

    fun coinFlip(choice: String) {
        if (!wagerValid || coinFlipping || live) return
        val bet = stake
        if (!GambleStats.reserve(ctx,bet)) return
        Store.add(ctx, -bet)
        // Minden dobásnál független, 50-50% esélyű véletlenszám.
        val result = if (Shoe.rnd.nextBoolean()) "Fej" else "Írás"
        val won = result == choice
        if (won) Store.addUncapped(ctx,bet.toLong()*2L)
        bal = Store.bal(ctx)
        selectedSide = choice
        coinResult = result
        coinWon = won
        coinNote = if (won) "Nyertél! +" + duration(bet) else "Most nem sikerült. −" + duration(bet)
        GambleStats.record(ctx,"Érmedobás",if(won)bet else -bet)
        coinFlipping = true
        flipCounter++
    }

    fun finish(hs: List<Hand>) {
        val dc = dealer.toMutableList()
        if (hs.any { value(it.cards) <= 21 }) while (value(dc) < 17) dc.add(Shoe.draw())
        dealer = dc
        live = false
        var paid=0L
        var spent=0L
        val dv=value(dc)
        hands=hs.map{h->
            val pv=value(h.cards)
            val payout=when {
                pv>21 -> 0L
                dv>21 || pv>dv -> h.bet.toLong()*2L
                pv==dv -> h.bet.toLong()
                else -> 0L
            }
            paid+=payout;spent+=h.bet.toLong()
            h.copy(res=when {
                payout>h.bet.toLong() -> "GYŐZELEM · +"+duration(h.bet)
                payout==h.bet.toLong() -> "DÖNTETLEN"
                else -> "VESZTETT · −"+duration(h.bet)
            })
        }
        Store.addUncapped(ctx,paid)
        bal=Store.bal(ctx)
        val difference=paid-spent
        val shown=abs(difference).coerceAtMost(Store.MAX_SAFE_SECONDS.toLong()).toInt()
        bjMessage=if(difference>=0)"Kör eredménye: +"+duration(shown)
                  else "Kör eredménye: −"+duration(shown)
        GambleStats.record(ctx,"Blackjack",difference.coerceIn(-Store.MAX_SAFE_SECONDS.toLong(),
            Store.MAX_SAFE_SECONDS.toLong()).toInt())
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
        if(!GambleStats.reserve(ctx,bet))return
        Store.add(ctx, -bet)
        val player = listOf(Shoe.draw(), Shoe.draw())
        val dc = listOf(Shoe.draw(), Shoe.draw())
        dealer = dc
        active = 0
        val playerBJ = value(player) == 21
        val dealerBJ = value(dc) == 21
        if (playerBJ || dealerBJ) {
            val paid=if(playerBJ&&dealerBJ)bet.toLong()
                     else if(playerBJ)bet.toLong()*5L/2L else 0L
            Store.addUncapped(ctx,paid)
            live = false
            bjMessage = when {
                playerBJ && dealerBJ -> "Mindkét fél blackjack · döntetlen"
                playerBJ -> "BLACKJACK! +" + duration((paid-bet).coerceAtMost(Store.MAX_SAFE_SECONDS.toLong()).toInt())
                else -> "Az osztónak blackjackje van · −" + duration(bet)
            }
            hands = listOf(Hand(player, bet, done = true, res = bjMessage))
            GambleStats.record(ctx,"Blackjack",(paid-bet).coerceIn(-Store.MAX_SAFE_SECONDS.toLong(),Store.MAX_SAFE_SECONDS.toLong()).toInt())
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
        if (bal < h.bet || h.bet>Store.MAX_SAFE_SECONDS/2 || !GambleStats.reserve(ctx,h.bet)) return
        Store.add(ctx, -h.bet)
        next(replaceHand(h.copy(cards = h.cards + Shoe.draw(), bet = h.bet * 2, done = true)))
    }
    fun split() {
        val h = hands[active]
        if (bal < h.bet || hands.size >= 4 || h.cards.size != 2 || rv(h.cards[0]) != rv(h.cards[1])) return
        if(!GambleStats.reserve(ctx,h.bet))return
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
    val canDouble = canMove && current!!.cards.size == 2 && GambleStats.canBet(ctx,current.bet)
    val canSplit = canMove && current!!.cards.size == 2 && rv(current.cards[0]) == rv(current.cards[1]) &&
        hands.size < 4 && !current.ace && GambleStats.canBet(ctx,current.bet)
    val betEnabled = wagerValid && !live && !coinFlipping

    val drawerState = androidx.compose.material3.rememberDrawerState(DrawerValue.Closed)
    val drawerScope = rememberCoroutineScope()
    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet(modifier=Modifier.width(295.dp),
                drawerContainerColor=Panel) {
                Column(Modifier.statusBarsPadding().padding(16.dp),
                    verticalArrangement=Arrangement.spacedBy(7.dp)) {
                    Text("FÓKUSZ",color=Mint,fontWeight=FontWeight.Black,fontSize=24.sp)
                    Text("Válassz funkciót",color=Muted,fontSize=13.sp)
                    Spacer(Modifier.height(12.dp))
                    listOf(
                        Triple(0,"♠","Gambling"),
                        Triple(1,"✦","Questek"),
                        Triple(2,"◷","Képernyőidő"),
                        Triple(3,"▥","Statisztika"),
                        Triple(4,"◎","AI-edző"),
                        Triple(5,"◉","Fókuszmód"),
                        Triple(6,"⚙","Beállítások"),
                        Triple(7,"▦","Naptár"),
                        Triple(8,"🔥","Duolingo"),
                        Triple(9,"👥","Csapatok"),
                        Triple(10,"📚","Xeropan")
                    ).forEach { (id,symbol,label) ->
                        NavigationDrawerItem(
                            label={Text(symbol + "   " + label,color=if(tab==id)Mint else White)},
                            selected=tab==id,
                            onClick={
                                tab=id
                                drawerScope.launch { drawerState.close() }
                            },
                            shape=RoundedCornerShape(15.dp)
                        )
                    }
                }
            }
        }
    ) {
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

        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(17.dp))
            .background(Panel2).padding(5.dp), verticalAlignment=Alignment.CenterVertically,
            horizontalArrangement=Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick={drawerScope.launch { drawerState.open() }},
                border=BorderStroke(1.dp,Border),shape=RoundedCornerShape(13.dp),
                colors=ButtonDefaults.outlinedButtonColors(contentColor=Mint)) {
                Text("☰  MENÜ",fontWeight=FontWeight.Bold)
            }
            Text(listOf("Gambling","Questek","Képernyőidő","Statisztika",
                "AI-edző","Fókuszmód","Beállítások","Naptár","Duolingo","Csapatok").getOrElse(tab) { "Fókusz" },
                color=White,fontWeight=FontWeight.Bold, fontSize=15.sp)
        }

        if(tab == 0 || tab == 1 || tab == 3) {
            Row(
                Modifier.fillMaxWidth().animateContentSize().clip(RoundedCornerShape(17.dp))
                    .background(Brush.horizontalGradient(
                        listOf(Color(0xFF203E47),Color(0xFF262C46))))
                    .border(1.dp,Color(0xFF395B65),RoundedCornerShape(17.dp))
                    .padding(horizontal=13.dp,vertical=11.dp),
                verticalAlignment=Alignment.CenterVertically,
                horizontalArrangement=Arrangement.spacedBy(8.dp)
            ) {
                Column(Modifier.weight(1f)) {
                    Text("🏦  BANK HOLNAP",color=Muted,fontSize=10.sp,
                        fontWeight=FontWeight.Bold)
                    Text(duration(TimeBank.projectedTomorrow(ctx)),color=Mint,
                        fontSize=18.sp,fontWeight=FontWeight.Black)
                }
                if(DuolingoStore.linked(ctx) && DuolingoStore.rewardedToday(ctx)) {
                    Text("🔥 DUO ✓",color=Mint,fontSize=13.sp,fontWeight=FontWeight.Bold)
                } else {
                    OutlinedButton(onClick={tab=8},
                        colors=ButtonDefaults.outlinedButtonColors(contentColor=Gold),
                        border=BorderStroke(1.dp,Color(0xFF6F6753)),
                        shape=RoundedCornerShape(12.dp)) {
                        Text("🔥 DUO +3",fontSize=12.sp)
                    }
                }
            }
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
                        onValueChange = { if (it.length <= 9 && it.all(Char::isDigit)) minutes = it },
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
                OutlinedButton(onClick={confirmAllIn=true},
                    enabled=bal>0&&!live&&!coinFlipping,
                    modifier=Modifier.fillMaxWidth()) {
                    Text("♠ ALL IN  •  teljes saját időegyenleg")
                }
                if(confirmAllIn) {
                    androidx.compose.material3.AlertDialog(
                        onDismissRequest={confirmAllIn=false},
                        title={Text("ALL IN – megerősítés")},
                        text={Text("Biztosan a teljes "+clock(bal)+
                            " saját képernyőidődet szeretnéd kockáztatni? A csapatidőre ez nem vonatkozik.")},
                        confirmButton={
                            androidx.compose.material3.TextButton(onClick={
                                minutes=(bal/60).toString()
                                seconds=String.format(Locale.ROOT,"%02d",bal%60)
                                confirmAllIn=false
                            }){Text("IGEN, ALL IN")}
                        },
                        dismissButton={
                            androidx.compose.material3.TextButton(onClick={confirmAllIn=false}) {
                                Text("MÉGSEM")
                            }
                        }
                    )
                }
                if (!wagerValid) {
                    Text(
                        if (stake <= 0) "Adj meg érvényes tétet (0–59 mp másodpercmező)!"
                        else "Ehhez a téthez nincs elegendő saját időegyenleged.",
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
                        Modifier.size(118.dp)
                            .graphicsLayer {
                                rotationY = coinRotation.value % 360f
                                translationY = coinLift.value.dp.toPx()
                                cameraDistance = 12.dp.toPx()
                            }
                            .shadow(18.dp,CircleShape)
                            .clip(CircleShape)
                            .background(Brush.radialGradient(listOf(Color(0xFFFFF1CB), Gold, Color(0xFFB97834))))
                            .border(5.dp, Color(0xFF8E643B), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            if (coinFlipping) {
                                if ((coinRotation.value / 180f).toInt() % 2 == 0) "F" else "Í"
                            } else if (coinResult == "Fej") "F"
                            else if (coinResult == "Írás") "Í" else "↻",
                            color = Color(0xFF513624), fontSize = 55.sp, fontWeight = FontWeight.Black
                        )
                    }
                }
                Text(
                    if (coinFlipping) "FELDOBTAD AZ ÉRMÉT…" else if (coinResult.isEmpty()) "FEJ VAGY ÍRÁS?" else "Eredmény: " + coinResult,
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

            DiceDuel(stake=stake,enabled=betEnabled) { bal=Store.bal(ctx) }

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
            GamblingSummaryPanel(GambleStats.staked(ctx))
        } else if (tab == 1) {
            QuestPanel(isRunning, usageOk, overlayOk, { tab = 2 }, { tab = 6 })
        } else if (tab == 2) {
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
        } else if (tab == 3) {
            StatsPanel()
        } else if (tab == 4) {
            CoachPanel()
        } else if (tab == 5) {
            FocusPanel(isRunning, usageOk, overlayOk) { tab = 2 }
        } else if (tab == 6) {
            OptionsPanel()
        } else if (tab == 7) {
            CalendarPanel()
        } else if(tab == 8) {
            DuolingoPanel()
        }
        else if(tab==9) {
            CloudTeamPanel()
        } else if(tab==10) {
            XeropanPanel()
        }
        Spacer(Modifier.height(14.dp))
        Text("FÓKUSZ  •  2.6", color = Muted.copy(alpha = .6f),
            modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center, fontSize = 11.sp)
        Spacer(Modifier.height(8.dp))
    }
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
