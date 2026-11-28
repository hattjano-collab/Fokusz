package hu.fokusz

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.activity.compose.setContent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.selection.selectable
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.toArgb
import androidx.core.view.WindowCompat
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.animation.animateContentSize
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.foundation.layout.heightIn
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

private val Midnight get() = FokuszAppearance.look.background
private val Panel get() = FokuszAppearance.look.surface
private val Panel2 get() = FokuszAppearance.look.hero
private val Mint get() = FokuszAppearance.look.link
private var legacyAccent by mutableStateOf(Color(0xFF7BE7C1))
fun updateFokuszTheme(theme: String) {
    legacyAccent = when (theme) {
        "lila" -> Color(0xFFB9AAFF)
        "arany" -> Color(0xFFFFC78D)
        else -> Color(0xFF7BE7C1)
    }
}
private val Gold get() = if (FokuszAppearance.look.dark) Color(0xFFFFC78D) else Color(0xff8a4a18)
private val Lilac get() = if (FokuszAppearance.look.dark) Color(0xFFB9AAFF) else Color(0xff66478b)
private val Muted get() = FokuszAppearance.look.muted
private val White get() = FokuszAppearance.look.foreground
private val Border get() = FokuszAppearance.look.outline

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
) : java.io.Serializable

private val handsSaver = listSaver<List<Hand>, Hand>(save = { it }, restore = { it.toList() })
private val dealerSaver = listSaver<List<Int>, Int>(save = { it }, restore = { it.toList() })

class MainActivity : ComponentActivity() {
    private var requestedTab by mutableIntStateOf(-1)
    private var tabRequestRevision by mutableIntStateOf(0)
    private var quizShare by mutableStateOf<QuizShare?>(null)

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        QuizShare.from(intent)?.let { quizShare = it }
        if (intent.hasExtra("focus_tab")) {
            requestedTab = intent.getIntExtra("focus_tab", 8)
            tabRequestRevision++
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        requestedTab=intent?.getIntExtra("focus_tab",-1) ?: -1
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) quizShare = QuizShare.from(intent)
        FokuszAppearance.load(this)
        setContent {
            FokuszTheme {
                val look = FokuszAppearance.look
                SideEffect {
                    window.statusBarColor = look.background.toArgb()
                    window.navigationBarColor = look.background.toArgb()
                    WindowCompat.getInsetsController(window, window.decorView).apply {
                        isAppearanceLightStatusBars = !look.dark
                        isAppearanceLightNavigationBars = !look.dark
                    }
                }
                Surface(color = look.background, modifier = Modifier.fillMaxSize()) {
                    FokuszApp(requestedTab, tabRequestRevision, quizShare) { received ->
                        if (quizShare?.id == received) quizShare = null
                    }
                }
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

@Composable private fun GlassCard(content: @Composable ColumnScope.() -> Unit) = FokuszCard(content = content)

@Composable private fun PrimaryAction(text: String, enabled: Boolean = true, modifier: Modifier = Modifier, onClick: () -> Unit) =
    FokuszAction(text, modifier, enabled, onClick = onClick)

@Composable private fun SoftAction(text: String, enabled: Boolean = true, modifier: Modifier = Modifier,
                                  highlight: Boolean = false, onClick: () -> Unit) =
    FokuszAction(text, modifier, enabled, primary = highlight, onClick = onClick)

@Composable private fun LegacyPage(content: @Composable ColumnScope.() -> Unit) {
    MaterialTheme(colorScheme = androidx.compose.material3.darkColorScheme(
        primary = legacyAccent, onPrimary = Color(0xff090f1f), surface = Color(0xff141e32),
        onSurface = Color(0xfff5f7ff), onSurfaceVariant = Color(0xffa7b4cc)
    ), typography = MaterialTheme.typography, shapes = MaterialTheme.shapes) {
        Surface(color = Color(0xff141e32), shape = FokuszAppearance.look.shape) {
            Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp),
                content = content)
        }
    }
}

@Composable private fun NavTab(title: String, symbol: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Box(
        modifier
            .clip(RoundedCornerShape(16.dp))
            .background(if (selected) Panel2 else Color.Transparent)
            .selectable(selected, role = Role.Tab, onClick = onClick)
            .heightIn(min = 50.dp).padding(vertical = 6.dp, horizontal = 3.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(symbol, color = if (selected) Mint else Muted, fontSize = 14.sp)
            Text(title, style = MaterialTheme.typography.labelMedium, textAlign = TextAlign.Center,
                color = if (selected) Mint else Muted,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium)
        }
    }
}

@Composable internal fun FokuszApp(openTab: Int = -1, openTabRevision: Int = 0, quizShare: QuizShare? = null,
                                 onShareConsumed: (String) -> Unit = {}) {
    val ctx = LocalContext.current
    val version = remember(ctx) { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }
    var tab by rememberSaveable { mutableStateOf(0) }
    LaunchedEffect(openTab, openTabRevision) { if (openTab in 0..12) tab = openTab }
    LaunchedEffect(quizShare?.id) { if (quizShare != null) tab = 11 }
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

    var selectedSide by rememberSaveable { mutableStateOf("") }
    var coinResult by rememberSaveable { mutableStateOf("") }
    var coinNote by rememberSaveable { mutableStateOf("Válassz egy oldalt a dobáshoz!") }
    var coinWon by rememberSaveable { mutableStateOf<Boolean?>(null) }
    var coinFlipping by remember { mutableStateOf(false) }
    var flipCounter by remember { mutableStateOf(0) }
    val coinRotation = remember { Animatable(0f) }
    val coinLift = remember { Animatable(0f) }

    var bjMessage by rememberSaveable { mutableStateOf("Válassz tétet, majd kezdj egy új kört.") }
    var hands by rememberSaveable(stateSaver = handsSaver) { mutableStateOf(listOf<Hand>()) }
    var dealer by rememberSaveable(stateSaver = dealerSaver) { mutableStateOf(listOf<Int>()) }
    var active by rememberSaveable { mutableStateOf(0) }
    var live by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        while (true) {
            bal = Store.bal(ctx)
            WeeklyWallet.pulse(ctx)
            isRunning = FocusService.isRunning
            usageOk = UsageGuard.allowed(ctx)
            overlayOk = Settings.canDrawOverlays(ctx)
            delay(1000)
        }
    }

    LaunchedEffect(flipCounter) {
        if (flipCounter > 0) {
            val start = (coinRotation.value % 360f + 360f) % 360f
            val landing = if (coinResult == "Írás") 180f else 0f
            coinRotation.snapTo(start)
            coinLift.snapTo(0f)
            coroutineScope {
                launch {
                    coinRotation.animateTo(1800f + landing, tween(durationMillis = 1370,
                        easing = androidx.compose.animation.core.CubicBezierEasing(.18f, .55f, .3f, 1f)))
                }
                launch { coinLift.animateTo(1f, tween(durationMillis = 1370, easing = LinearEasing)) }
            }
            coinFlipping = false
        }
    }

    fun coinFlip(choice: String) {
        if (!wagerValid || coinFlipping || live) return
        val bet = stake
        if (!GambleStats.reserve(ctx, bet, "Érmedobás tétje")) return
        // Minden dobásnál független, 50-50% esélyű véletlenszám.
        val result = if (Shoe.rnd.nextBoolean()) "Fej" else "Írás"
        val won = result == choice
        if (won) Store.addUncapped(ctx, bet.toLong() * 2L, "Érmedobás kifizetése")
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
        if (!live) return
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
        Store.addUncapped(ctx, paid, "Blackjack kifizetése")
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
        if (!wagerValid || live || coinFlipping) return
        val bet = stake
        if (!GambleStats.reserve(ctx, bet, "Blackjack tétje")) return
        val player = listOf(Shoe.draw(), Shoe.draw())
        val dc = listOf(Shoe.draw(), Shoe.draw())
        dealer = dc
        active = 0
        val playerBJ = value(player) == 21
        val dealerBJ = value(dc) == 21
        if (playerBJ || dealerBJ) {
            val paid=if(playerBJ&&dealerBJ)bet.toLong()
                     else if(playerBJ)bet.toLong()*5L/2L else 0L
            Store.addUncapped(ctx, paid, "Blackjack kifizetése")
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
        if (!live) return
        val h = hands[active]
        val cs = h.cards + Shoe.draw()
        next(replaceHand(h.copy(cards = cs, done = value(cs) >= 21)))
    }
    fun stand() { if (live) next(replaceHand(hands[active].copy(done = true))) }
    fun doubleDown() {
        if (!live) return
        val h = hands[active]
        if (h.cards.size != 2 || h.bet > Store.MAX_SAFE_SECONDS / 2 ||
            !GambleStats.reserve(ctx, h.bet, "Blackjack duplázás tétje")) return
        next(replaceHand(h.copy(cards = h.cards + Shoe.draw(), bet = h.bet * 2, done = true)))
    }
    fun split() {
        if (!live) return
        val h = hands[active]
        if (hands.size >= 4 || h.ace || h.cards.size != 2 || rv(h.cards[0]) != rv(h.cards[1])) return
        if (!GambleStats.reserve(ctx, h.bet, "Blackjack osztás tétje")) return
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
    val pageScroll = rememberScrollState()
    var gameChoice by rememberSaveable { mutableIntStateOf(0) }
    val gamePositions = remember { IntArray(3) }
    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet(modifier=Modifier.width(295.dp),
                drawerContainerColor=Panel) {
                Column(Modifier.statusBarsPadding().navigationBarsPadding()
                    .verticalScroll(rememberScrollState()).padding(16.dp),
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
                        Triple(10,"📚","Xeropan"),
                        Triple(11,"▣","Kvíz fotózása"),
                        Triple(12,"▤","Időbank")
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
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
    Column(
        Modifier.weight(1f).fillMaxWidth()
            .verticalScroll(pageScroll).padding(horizontal = 20.dp, vertical = 17.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(45.dp).clip(FokuszAppearance.look.shape)
                .background(FokuszAppearance.look.accent),
                contentAlignment = Alignment.Center) {
                Text("◈", fontSize = 28.sp, color = FokuszAppearance.look.onAccent, fontWeight = FontWeight.Black)
            }
            Spacer(Modifier.size(12.dp))
            Column {
                Text("Fókusz", color = White, style = MaterialTheme.typography.headlineMedium)
                androidx.compose.material3.TextButton(onClick = {
                    val next = (FokuszAppearance.look.ordinal + 1) % FokuszLook.entries.size
                    FokuszAppearance.choose(ctx, FokuszLook.entries[next])
                }, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text("Dizájn váltása", color = Mint, style = MaterialTheme.typography.labelMedium)
                }
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
                "AI-edző","Fókuszmód","Beállítások","Naptár","Duolingo","Csapatok","Xeropan",
                "Kvíz fotózása","Időbank").getOrElse(tab) { "Fókusz" },
                color=White,fontWeight=FontWeight.Bold, fontSize=15.sp)
        }

        if(tab == 1 || tab == 3) {
            Row(
                Modifier.fillMaxWidth().animateContentSize().clip(RoundedCornerShape(17.dp))
                    .background(Brush.horizontalGradient(
                        listOf(Panel, Panel2)))
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

        if (tab == 0 && (!isRunning || !usageOk || !overlayOk)) {
            GlassCard {
                Text("A közösségi appok zárolása jelenleg inaktív", color = Gold)
                SoftAction("Bekapcsolás", onClick = { tab = 2 })
            }
        }

        if (tab == 0) {
            FokuszHeading("Játék a saját időddel", "Gambling")
            FokuszCard(color = FokuszAppearance.look.hero) {
                Text("Játékra elérhető saját idő", color = Muted)
                LargeTime(bal, size = 50)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("Érmedobás", "Kockapárbaj", "Blackjack").forEachIndexed { index, title ->
                    NavTab(title, listOf("◉", "⚄", "♠")[index], gameChoice == index, Modifier.weight(1f)) {
                        gameChoice = index
                        drawerScope.launch { pageScroll.animateScrollTo(gamePositions[index].coerceAtLeast(0)) }
                    }
                }
            }
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

            FokuszCard(Modifier.onGloballyPositioned { gamePositions[0] = it.positionInParent().y.toInt() }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("🪙  ÉRMEDOBÁS", color = Gold, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                    Spacer(Modifier.weight(1f))
                    Text("50% / 50%", color = Muted, fontSize = 12.sp)
                }
                BimetalCoin(coinRotation.value, coinLift.value)
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

            Box(Modifier.onGloballyPositioned { gamePositions[1] = it.positionInParent().y.toInt() }) {
                DiceDuel(stake = stake, enabled = betEnabled) { bal = Store.bal(ctx) }
            }

            FokuszCard(Modifier.onGloballyPositioned { gamePositions[2] = it.positionInParent().y.toInt() }) {
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
                                .background(if (selected) Panel2 else Panel)
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
            LegacyPage { GamblingSummaryPanel(GambleStats.staked(ctx)) }
        } else if (tab == 1) {
            LegacyPage { QuestPanel(isRunning, usageOk, overlayOk, { tab = 2 }, { tab = 6 }) }
        } else if (tab == 2) {
            ScreenTimeOverview(bal, isRunning && usageOk && overlayOk, { tab = 12 }, { tab = 6 })
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
            LegacyPage {
                SocialUnlockSettings()
                FocusSchedulesPanel()
                ScreenTimePanel()
            }
            GlassCard {
                Text("TIPP XIAOMI TELEFONHOZ", color = Mint, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                Text("A stabil háttérfigyeléshez állíts be korlátozás nélküli akkumulátorhasználatot a Fókusz alkalmazásinformációs oldalán.",
                    color = Muted, fontSize = 13.sp)
                Text("A heti elszámolás a saját időegyenleget és a választást szinkronizálja a Firebase-szerverrel.",
                    color = Muted, fontSize = 12.sp)
            }
        } else if (tab == 3) {
            LegacyPage { StatsPanel() }
        } else if (tab == 4) {
            LegacyPage { CoachPanel() }
        } else if (tab == 5) {
            LegacyPage { FocusPanel(isRunning, usageOk, overlayOk) { tab = 2 } }
        } else if (tab == 6) {
            ThemePicker()
            LegacyPage { OptionsPanel() }
        } else if (tab == 7) {
            LegacyPage { CalendarPanel() }
        } else if(tab == 8) {
            LegacyPage { DuolingoPanel() }
        }
        else if(tab==9) {
            WeeklySettlementPanel()
            LegacyPage { CloudTeamPanel() }
        } else if(tab==10) {
            LegacyPage { XeropanPanel() }
        } else if(tab==11) {
            LegacyPage { QuizPhotoPanel(quizShare, onShareConsumed) }
        } else if(tab==12) {
            TimeBankPanel { tab = 9 }
        }
        Spacer(Modifier.height(14.dp))
        Text("FÓKUSZ  •  $version", color = Muted.copy(alpha = .6f),
            modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center, fontSize = 11.sp)
        Spacer(Modifier.height(8.dp))
    }
    Row(Modifier.fillMaxWidth().background(Panel).padding(horizontal = 8.dp, vertical = 4.dp)) {
        for ((id, symbol, title) in listOf(Triple(2,"◷","Képernyőidő"), Triple(0,"♠","Gambling"), Triple(12,"▤","Időbank"))) {
            NavTab(title, symbol, tab == id, Modifier.weight(1f)) { tab = id }
        }
    }
    }
    }
}

@Composable private fun PlayingCard(card: Int?) {
    val front = card != null
    val suitRed = front && card!! / 13 in listOf(1,2)
    Column(
        Modifier.size(width = 45.dp, height = 62.dp).clip(RoundedCornerShape(8.dp))
            .background(if (front) Color(0xfff5f7ff) else Color(0xFF5861A8))
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
