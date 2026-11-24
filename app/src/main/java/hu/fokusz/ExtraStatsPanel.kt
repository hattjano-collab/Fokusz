package hu.fokusz

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.delay
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val GREEN=Color(0xFF7BE7C1)
private val FG=Color(0xFFF5F7FF)
private val SUB=Color(0xFFA7B4CC)

@Composable fun ExtraStatsPanel() {
    val c=LocalContext.current
    var refresh by remember { mutableIntStateOf(0) }
    var showChessHistory by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) { while (true) { delay(4000); refresh++ } }
    val days=ProgressStore.recent(c)
    val used=days.sumOf { it.usage }
    val estimate=(7*Store.DAILY-used).coerceAtLeast(0)
    Column(verticalArrangement=Arrangement.spacedBy(15.dp)) {
        FeatureBox("HETI SZEMÉLYES BOSS FIGHT") {
            PersonalBossPanel()
        }
        FeatureBox("♟️ SAKKSTATISZTIKA") {
            val w=ChessVerifier.wins(c)
            val l=ChessVerifier.losses(c)
            val d=ChessVerifier.draws(c)
            val total=w+l+d
            Text("Győzelem: $w  •  Vereség: $l  •  Döntetlen: $d",color=FG)
            Text("Győzelmi arány: "+(if(total>0)w*100/total else 0)+"%",color=GREEN,
                fontWeight=FontWeight.Bold)
            Text("Jutalom minden újonnan ellenőrzött sakkjátszmáért: győzelem +1 perc, vereség +30 másodperc, döntetlen +45 másodperc.",
                color=SUB,fontSize=12.sp)
            val accounts = ChessVerifier.accounts(c).joinToString(" · ") { it.label }
            Text(accounts.ifEmpty { "Nincs összekötött sakkfiók." }, color = SUB)
            OutlinedButton(
                onClick = { showChessHistory = true },
                modifier = Modifier.fillMaxWidth()
            ) { Text("Sakkjutalmak naplója") }
            if (showChessHistory) {
                ChessRewardHistoryDialog(refresh, total) { showChessHistory = false }
            }
        }
        FeatureBox("📉 BECSÜLT MEGTAKARÍTOTT IDŐ") {
            Text(duration(estimate),color=GREEN,fontSize=28.sp,fontWeight=FontWeight.ExtraBold)
            Text("A héten ennyivel maradt a figyelt social appok használata a napi 30 perces viszonyítási szint alatt.",
                color=SUB,fontSize=12.sp)
            Text("Ez becslés, nem a telefon teljes képernyőideje. A mérés csak bekapcsolt védelem alatt teljes.",
                color=SUB,fontSize=11.sp)
            Text("Mért social média ezen a héten: "+duration(used),
                color=FG,fontSize=13.sp)
        }
        FeatureBox("🧠 ADAPTÍV QUESTEK ÉS NAPI RITMUS") {
            val busy=CalendarStore.busySoon(c)
            val weekend=LocalDate.now().dayOfWeek.value>=6
            Text(if(busy) "📅 Hamarosan naptári programod van: rövidebb küldetéseket részesítünk előnyben."
                else if(weekend) "🌿 Hétvégi mód: mozgás és szabadtéri questek előnyben."
                else "📚 Tanítási mód: tanulós questek és rövid feladatok előnyben.",
                color=FG)
            Text("A mozgásos extra questek célszáma legfeljebb 10%-kal igazodik az előző 7 nap teljesítményéhez. A napi kötelező fekvőcél változatlan.",
                color=SUB,fontSize=12.sp)
        }
    }
}

@Composable
private fun ChessRewardHistoryDialog(refresh: Int, totalGames: Int, onClose: () -> Unit) {
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    var rewards by remember { mutableStateOf<List<ChessReward>?>(null) }
    var linkError by remember { mutableStateOf(false) }
    val dateFormat = remember {
        DateTimeFormatter.ofPattern("yyyy. MM. dd. HH:mm", Locale.forLanguageTag("hu-HU"))
            .withZone(ZoneId.systemDefault())
    }
    LaunchedEffect(context, refresh) { rewards = ChessVerifier.rewardHistory(context) }

    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Sakkjutalmak naplója") },
        text = {
            val history = rewards
            if (history == null) {
                Text("Betöltés…")
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (totalGames > history.size) {
                        Text("A régebbi partikból az alkalmazás összesített statisztikát mentett.",
                            fontSize = 12.sp)
                    }
                    if (linkError) Text("A játszma linkjét nem sikerült megnyitni.")
                    if (history.isEmpty()) {
                        Text("A következő ellenőrzött partid részletei itt jelennek meg.")
                    } else {
                        LazyColumn(
                            modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            items(history, key = { it.id }) { reward ->
                                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text("${reward.platform.label} · ${reward.opponent ?: "Ismeretlen ellenfél"}",
                                        fontWeight = FontWeight.Bold)
                                    val credit = reward.creditedSeconds
                                    Text("${reward.outcome.label} · Jóváírva: " +
                                        if (credit > 0) "+${duration(credit)}" else "0 mp")
                                    Text("Parti: ${dateFormat.format(reward.playedAt)}", fontSize = 12.sp)
                                    Text("Ellenőrizve: ${dateFormat.format(reward.verifiedAt)}", fontSize = 12.sp)
                                    reward.gameUrl?.let { url ->
                                        TextButton(onClick = {
                                            linkError = runCatching { uriHandler.openUri(url) }.isFailure
                                        }) { Text("Eredeti játszma megnyitása") }
                                    }
                                    HorizontalDivider()
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text("Bezárás") } }
    )
}
