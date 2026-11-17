package hu.fokusz

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import java.time.format.DateTimeFormatter

private val A = Color(0xFF7BE7C1)
private val B = Color(0xFFB9AAFF)
private val N = Color(0xFF090F1F)
private val W = Color(0xFFF5F7FF)
private val M = Color(0xFFA7B4CC)
private val C = Color(0xFF273750)

@Composable private fun FeatureCard(title: String, content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(21.dp))
            .background(Brush.linearGradient(listOf(Color(0xFF1B2940), Color(0xFF131E31))))
            .border(1.dp, Color(0xFF2D3D54), RoundedCornerShape(21.dp))
            .padding(17.dp),
        verticalArrangement = Arrangement.spacedBy(11.dp)
    ) {
        Text(title, color = A, fontSize = 14.sp, fontWeight = FontWeight.ExtraBold)
        content()
    }
}

@Composable private fun FeatureButton(text: String, enabled: Boolean = true, onClick: () -> Unit) {
    Button(onClick = onClick, enabled = enabled,
        modifier = Modifier.fillMaxWidth().height(47.dp),
        shape = RoundedCornerShape(14.dp),
        colors = ButtonDefaults.buttonColors(containerColor = A, contentColor = N)) {
        Text(text, fontWeight = FontWeight.Bold, fontSize = 13.sp)
    }
}

@Composable private fun SmallSecondary(text: String, enabled: Boolean = true, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, enabled = enabled, shape = RoundedCornerShape(13.dp),
        modifier = Modifier.fillMaxWidth().height(46.dp),
        border = BorderStroke(1.dp,C),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = W)) {
        Text(text, fontSize = 12.sp)
    }
}

@Composable fun StatsPanel() {
    val ctx = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) { while(true) {delay(2000);tick++} }
    val recent = remember(tick) { ProgressStore.recent(ctx) }
    val xp = ProgressStore.xp(ctx)
    val level = ProgressStore.level(ctx)
    val goals = ProgressStore.milestones(ctx)
    val usageMax = (recent.maxOfOrNull { it.usage } ?: 0).coerceAtLeast(1)
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("HALADÁS ÉS STATISZTIKÁK", color = W, fontSize = 22.sp, fontWeight = FontWeight.Black)
        FeatureCard("⭐ SZINTEK ÉS SOROZAT") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("LVL " + level, color = W, fontSize = 36.sp, fontWeight = FontWeight.Black)
                    Text(xp.toString() + " XP összesen", color = M)
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text("🔥 " + ProgressStore.streak(ctx) + " nap", color = A, fontSize = 20.sp,
                        fontWeight = FontWeight.Bold)
                    Text("Napi sorozat", color = M, fontSize = 12.sp)
                }
            }
            LinearProgressIndicator(
                progress = { ((xp % 250).toFloat() / 250f).coerceIn(0f,1f) },
                modifier = Modifier.fillMaxWidth().height(9.dp).clip(RoundedCornerShape(9.dp)),
                color = A, trackColor = C)
            Text("Minden 250 XP után új szintet érsz el.", color = M, fontSize = 12.sp)
        }
        FeatureCard("📊 KÉPERNYŐIDŐ – 7 NAP") {
            Text("A Fókusz által figyelt social appok ténylegesen mért ideje.",
                color = M, fontSize = 12.sp)
            for (d in recent) {
                Row(verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                    Text(d.date.format(DateTimeFormatter.ofPattern("EE", java.util.Locale("hu"))),
                        color = M, fontSize = 12.sp, modifier = Modifier.width(35.dp))
                    LinearProgressIndicator(
                        progress = { (d.usage.toFloat()/usageMax).coerceIn(0f,1f) },
                        modifier = Modifier.weight(1f).height(11.dp).clip(RoundedCornerShape(8.dp)),
                        color = B, trackColor = C
                    )
                    Text(duration(d.usage), color = W, fontSize = 11.sp,
                        textAlign = TextAlign.End, modifier = Modifier.width(70.dp))
                }
            }
            Text("A mérés csak bekapcsolt háttérvédelem mellett történik. Nem a teljes Android-használat.",
                color = M, fontSize = 11.sp)
        }
        FeatureCard("🎯 HETI TEVÉKENYSÉG") {
            Text("Teljesített questek: " + ProgressStore.weekTasks(ctx), color = W)
            Text("Kamerás ismétlések: " + ProgressStore.weekReps(ctx), color = W)
            Text("Időzített tevékenységek: " + duration(ProgressStore.weekStudy(ctx)), color = W)
            Text("Heti kihívás: legalább 8 teljesített quest → +10 perc",
                color = M)
            LinearProgressIndicator(
                progress = { (ProgressStore.weekTasks(ctx) / 8f).coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(8.dp)),
                color = A, trackColor = C)
            FeatureButton("HETI BÓNUSZ FELVÉTELE (+10 PERC)",
                enabled = ProgressStore.weeklyBonusAvailable(ctx)) {
                ProgressStore.claimWeekly(ctx);tick++
            }
        }
        FeatureCard("🏅 ACHIEVEMENTEK") {
            for (item in goals) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(if (item.second) "✓" else "○",
                        color = if (item.second) A else M)
                    Spacer(Modifier.width(12.dp))
                    Text(item.first, color = if (item.second) W else M)
                }
            }
            Text("Szintlépéssel új színtémák nyílnak meg a Beállításokban.",
                color = M, fontSize = 12.sp)
        }
        ExtraStatsPanel()
    }
}

@Composable fun FocusPanel(serviceOn:Boolean,usageOk:Boolean,overlayOk:Boolean,openTimeTab:()->Unit) {
    val ctx=LocalContext.current
    var selected by remember {mutableIntStateOf(25)}
    var refresh by remember {mutableIntStateOf(0)}
    LaunchedEffect(Unit){while(true){delay(1000);refresh++}}
    val ready=serviceOn&&usageOk&&overlayOk
    val phase=FocusPrefs.focusPhase(ctx)
    val left=FocusPrefs.focusLeft(ctx)
    Column(verticalArrangement=Arrangement.spacedBy(16.dp)) {
        Text("MÉLYFÓKUSZ",color=W,fontSize=24.sp,fontWeight=FontWeight.Black)
        FeatureCard("📚 TANULÁS – RUGALMAS IDŐZÍTŐ") {
            Text("15–120 perc közül választasz. A valóban teljesített idő felét képernyőidőként megkapod.",
                color=M)
            if(phase==0) {
                Text(selected.toString()+" perc tanulás",color=A,fontSize=23.sp,
                    fontWeight=FontWeight.Bold)
                Slider(value=selected.toFloat(),onValueChange={
                    selected=(it.toInt()/5*5).coerceIn(15,120)
                },valueRange=15f..120f,steps=20)
                Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    listOf(25,50,90).forEach { minute ->
                        OutlinedButton(onClick={selected=minute},
                            modifier=Modifier.weight(1f),
                            colors=ButtonDefaults.outlinedButtonColors(
                                contentColor=if(selected==minute) A else M)) {
                            Text(minute.toString()+" p")
                        }
                    }
                }
                Text("Jutalom: +"+duration(selected*30)+" képernyőidő + XP",
                    color=A,fontSize=17.sp,fontWeight=FontWeight.Bold)
                val remaining=CalendarStore.nextEventMinutes(ctx)
                if(remaining!=null && remaining<selected.toLong()) {
                    Text("📅 A következő naptári eseményed "+remaining+" perc múlva kezdődik. Érdemes rövidebb blokkot választani.",
                        color=Color(0xFFFFC78D),fontSize=12.sp)
                }
                if(!ready) {
                    Text("A tanuló módhoz engedélyezett háttérvédelem kell.",color=M)
                    SmallSecondary("Védelem és engedélyek") {openTimeTab()}
                } else {
                    FeatureButton("TANULÁS INDÍTÁSA") {
                        FocusPrefs.startFocus(ctx,selected*60);refresh++
                    }
                }
            } else if(phase==1) {
                Text(if(FocusPrefs.focusPaused(ctx)) "☕ OKOS SZÜNET" else "TANULÁS FOLYAMATBAN",
                    color=if(FocusPrefs.focusPaused(ctx))Color(0xFFFFC78D) else A,
                    fontWeight=FontWeight.Bold)
                Text(clock(left),color=W,fontSize=58.sp,fontWeight=FontWeight.ExtraBold,
                    modifier=Modifier.fillMaxWidth(),textAlign=TextAlign.Center)
                LinearProgressIndicator(progress={
                    (1f-left.toFloat()/FocusPrefs.focusTarget(ctx)).coerceIn(0f,1f)
                },color=A,trackColor=C,modifier=Modifier.fillMaxWidth().height(10.dp)
                    .clip(RoundedCornerShape(9.dp)))
                Text("Szünetből felhasznált: "+FocusPrefs.pauseUsed(ctx)+"/300 mp",
                    color=M,fontSize=12.sp)
                if(left>0){
                    SmallSecondary(if(FocusPrefs.focusPaused(ctx))
                        "TANULÁS FOLYTATÁSA" else "5 PERCES SZÜNETKERET",
                        enabled=FocusPrefs.focusPaused(ctx)||FocusPrefs.pauseUsed(ctx)<300) {
                        FocusPrefs.togglePause(ctx);refresh++
                    }
                }
                Text("A közösségi appok szünet közben is tiltva maradnak. Google kereső, ChatGPT és rendszerfunkciók elérhetők.",
                    color=M,fontSize=12.sp)
                if(!ready)Text("A háttérvédelem leállt: az időzítő szünetel. A feladatot megőrizzük.",
                    color=Color(0xFFFFC78D),fontSize=12.sp)
                if(left==0) FeatureButton("TANULÁS KÉSZ – JUTALOM ÁTVÉTELE") {
                    FocusPrefs.claimFocus(ctx);refresh++
                }
            } else {
                Text("✓ A tanulás teljesítve, a jutalmat jóváírtuk.",color=A)
                FeatureButton("ÚJ TANULÁSI BLOKK") {FocusPrefs.clearFocus(ctx);refresh++}
            }
        }
        FeatureCard("🛡️ SZIGORÚ FÓKUSZ – FONTOS") {
            Text("A figyelt social appok és az ismert böngészők zárolva vannak az időzítés alatt. A Google alkalmazás, ChatGPT és alapvető rendszerfunkciók nem kerülnek a tiltólistára.",
                color=M,fontSize=12.sp)
            Text("A telefon nem teszi lehetővé, hogy pusztán alkalmazáshasználati engedéllyel minden weboldalt ellenőrizzünk, ezért a korlátozás nem megkerülhetetlen.",
                color=M,fontSize=11.sp)
            Text("Az edző a ☰ menüből a tanulás alatt is megnyitható, a social appok közben tiltva maradnak.",
                color=A,fontSize=12.sp)
        }
    }
}

@Composable fun OptionsPanel() {
    val ctx = LocalContext.current
    var refresh by remember { mutableIntStateOf(0) }
    val currentTheme = FocusPrefs.theme(ctx)
    val maxTime = FocusPrefs.maxBalance(ctx)
    val level = ProgressStore.level(ctx)
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("BEÁLLÍTÁSOK", color = W, fontSize = 23.sp, fontWeight = FontWeight.Black)
        FeatureCard("⭐ NAPI ALAPQUESTEK BEÁLLÍTÁSA") {
            BaseQuestSettings()
        }
        FeatureCard("♟️ SAKKFÓKUSZ (HA KIVÁLASZTOD)") {
            ChessAccountPanel()
        }
        FeatureCard("🔒 SAJÁT BLOKKLISTA") {
            Text("Válaszd ki, mely appok fogyasszák a napi időkeretet. A questek és a mélyfókusz idején további appok is blokkoltak lehetnek.",
                color = M, fontSize = 12.sp)
            FocusPrefs.socialChoices.forEach { (label,pkg) ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(label, modifier = Modifier.weight(1f), color = W)
                    Switch(checked = FocusPrefs.blocked(ctx, pkg),
                        onCheckedChange = { FocusPrefs.setBlocked(ctx, pkg, it);refresh++ })
                }
            }
            Text("Az appok tényleges blokkolását a gyártói háttérkorlátozások befolyásolhatják.",
                color = M, fontSize = 11.sp)
        }
        FeatureCard("⌛ NORMÁL JUTALMAK PLAFONJA") {
            Text("A szokásos questjutalmak összegét korlátozhatod. A játékok nyereményére és az időbankból kivett percekre ez a 4–6 órás plafon már nem vonatkozik.",
                color = M, fontSize = 12.sp)
            for (hours in listOf(1,2,3,4,6)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(hours.toString() + " óra", color = W, modifier = Modifier.weight(1f))
                    Checkbox(checked = maxTime == hours*3600,
                        onCheckedChange = { if (it) {FocusPrefs.setMaxBalance(ctx,hours*3600);refresh++} })
                }
            }
        }
        FeatureCard("🚨 VÉSZFELOLDÁS") {
            Text("Naponta egy alkalommal 3 perc ideiglenes feloldás. Nem használható a szigorú egyéni quest közben.",
                color = M)
            if (FocusPrefs.emergencyActive(ctx)) {
                Text("Aktív: még " + clock(FocusPrefs.emergencyLeft(ctx)),color = A)
            } else if (FocusPrefs.emergencyAvailable(ctx)) {
                FeatureButton("3 PERCES VÉSZFELOLDÁS") {
                    FocusPrefs.activateEmergency(ctx);refresh++
                }
            } else Text("A mai vészfeloldást már elhasználtad.", color = M)
        }
        FeatureCard("🎨 FELOLDHATÓ SZÍNTÉMÁK") {
            for ((key,label,minLevel) in listOf(
                Triple("menta","Menta  •  alap",1),
                Triple("lila","Levendula  •  3. szint",3),
                Triple("arany","Arany  •  5. szint",5)
            )) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(label, color = if (level >= minLevel) W else M,
                        modifier = Modifier.weight(1f))
                    Checkbox(checked = currentTheme == key,
                        enabled = level >= minLevel,
                        onCheckedChange = {
                            if (it) { FocusPrefs.setTheme(ctx,key); updateFokuszTheme(key);refresh++ }
                        })
                }
            }
        }
        FeatureCard("🔄 MEGSZAKÍTÁSVÉDELEM") {
            Text("A folyamatban lévő tanulás, quest és időbank az újraindítás után is megmarad. A rendszer engedélyével a Fókusz a háttérvédelmet is újraindíthatja.",
                color=M,fontSize=12.sp)
            Row(verticalAlignment=Alignment.CenterVertically) {
                Text("Automatikus indítás újraindítás után",
                    modifier=Modifier.weight(1f),color=W,fontSize=13.sp)
                Switch(checked=FocusPrefs.autoRestart(ctx),
                    onCheckedChange={FocusPrefs.setAutoRestart(ctx,it);refresh++})
            }
            Text("Egyes Android/HyperOS verziók blokkolhatják az automatikus indulást. Ilyenkor az alkalmazás értesítéssel kéri a kézi újraindítást.",
                color=M,fontSize=11.sp)
        }
        FeatureCard("🛡️ ADATVÉDELEM") {
            Text("A questek, XP, statisztikák, naptári események és beállítások a telefonon maradnak. A telefon naptárát csak külön engedéllyel olvassuk.",
                color = M)
            Text("Az online sakkellenőrzés a megadott nyilvános felhasználónevet használja. A kameraelemzés helyben fut.",
                color = M)
            Text("A Fókusz saját fejlesztésű app, nem rendszerkomponens. A tiltás nem megkerülhetetlen.",
                color = M)
        }
    }
}
