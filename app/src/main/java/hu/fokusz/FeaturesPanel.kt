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
    }
}

@Composable fun FocusPanel(serviceOn: Boolean, usageOk: Boolean, overlayOk: Boolean, openTimeTab: () -> Unit) {
    val ctx = LocalContext.current
    var selected by remember { mutableIntStateOf(1500) }
    var refresh by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) { while (true) { delay(1000);refresh++ } }
    val ready = serviceOn && usageOk && overlayOk
    val phase = FocusPrefs.focusPhase(ctx)
    val left = FocusPrefs.focusLeft(ctx)
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("MÉLYFÓKUSZ", color = W, fontSize = 24.sp, fontWeight = FontWeight.Black)
        FeatureCard("📖 KONCENTRÁLT TANULÁS") {
            Text("Tanulj 25 vagy 50 percet. Közben a közösségi appok és az ismert böngészők is zárolva vannak.",
                color = M)
            if (phase == 0) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(1500,3000).forEach { value ->
                        OutlinedButton(onClick = { selected = value },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = if (selected == value) A else M)) {
                            Text((value/60).toString() + " perc")
                        }
                    }
                }
                Text("Jutalom: +" + duration(selected/3) + " képernyőidő + XP.",
                    color = A)
                if (!ready) {
                    Text("A kezdéshez indítsd el az engedélyezett háttérvédelmet!",
                        color = M)
                    SmallSecondary("Képernyőidő-beállítások") { openTimeTab() }
                } else {
                    FeatureButton("TANULÁS INDÍTÁSA") { FocusPrefs.startFocus(ctx, selected);refresh++ }
                }
            } else if (phase == 1) {
                Text(clock(left), color = W, fontSize = 50.sp,
                    fontWeight = FontWeight.ExtraBold, textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth())
                LinearProgressIndicator(progress = {
                    (1f - left.toFloat() / FocusPrefs.focusTarget(ctx)).coerceIn(0f,1f)
                }, modifier = Modifier.fillMaxWidth().height(9.dp)
                    .clip(RoundedCornerShape(8.dp)),
                    color = A, trackColor = C)
                if (!ready) Text("A védelem leállt. Az időzítő csak bekapcsolt háttérvédelemmel halad.",
                    color = Color(0xFFFFC78D))
                if (left == 0) FeatureButton("KÉSZ VAGYOK  •  JUTALOM") {
                    FocusPrefs.claimFocus(ctx); refresh++
                }
            } else {
                Text("✓ Fókuszblokk teljesítve! Jutalom jóváírva.", color = A)
                FeatureButton("ÚJ FÓKUSZBLOKK") { FocusPrefs.clearFocus(ctx);refresh++ }
            }
        }
        FeatureCard("TIPPEK") {
            Text("• Készíts elő minden tanulnivalót a kezdés előtt.", color = M)
            Text("• A blokk idején ne használd a telefont.", color = M)
            Text("• Ha a Xiaomi bezárja a Fókusz háttérszolgáltatását, az időzítő szünetel.",
                color = M)
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
        FeatureCard("⌛ MAXIMÁLIS IDŐEGYENLEG") {
            Text("Korlátozhatod, hogy összesen mennyi idő gyűlhessen fel. A jutalmak nem emelik a beállított plafon fölé az egyenleget.",
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
        FeatureCard("🛡️ ADATVÉDELEM") {
            Text("A questek, XP, statisztikák, blokkolási beállítások és a barátlista a telefonon maradnak.",
                color = M)
            Text("Az online sakkellenőrzés a megadott nyilvános felhasználónevet használja. A kameraelemzés helyben fut.",
                color = M)
            Text("A Fókusz saját fejlesztésű app, nem rendszerkomponens. A tiltás nem megkerülhetetlen.",
                color = M)
        }
    }
}

@Composable fun FriendsPanel() {
    val ctx = LocalContext.current
    var displayName by remember { mutableStateOf("Én") }
    var friendName by remember { mutableStateOf("") }
    var friendXP by remember { mutableStateOf("") }
    var refresh by remember { mutableIntStateOf(0) }
    val xp = ProgressStore.xp(ctx)
    val entries = (FocusPrefs.friends(ctx) + FocusPrefs.Friend(displayName.ifBlank { "Én" },xp))
        .sortedByDescending { it.xp }
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("BARÁTOK ÉS KIHÍVÁSOK", color = W, fontSize = 23.sp, fontWeight = FontWeight.Black)
        FeatureCard("🤝 EREDMÉNY MEGOSZTÁSA") {
            Text("Küldd el XP-det és heti eredményedet egy barátodnak. Ehhez nem kell fiók.",
                color = M)
            OutlinedTextField(value = displayName,
                onValueChange = { if (it.length <= 25) displayName = it },
                label = { Text("Megjelenő neved") }, modifier = Modifier.fillMaxWidth())
            FeatureButton("EREDMÉNY MEGOSZTÁSA") {
                val share = "FÓKUSZ kihívás 🏆\n" + displayName + "\n" + xp +
                    " XP | " + ProgressStore.weekTasks(ctx) + " quest ezen a héten.\n" +
                    "Mennyit tudsz teljesíteni?"
                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, share)
                }
                ctx.startActivity(Intent.createChooser(intent,"Fókusz kihívás megosztása"))
            }
        }
        FeatureCard("🏅 HELYI BARÁTI RANGLISTA") {
            Text("Az ismerőseid elküldhetik az XP-jüket, te pedig felveheted őket. Ez helyi, kézi ranglista; nincs automatikus szerveres szinkron.",
                color = M, fontSize = 12.sp)
            for ((index,item) in entries.withIndex()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text((index+1).toString() + ". " + item.name, color = W,
                        modifier = Modifier.weight(1f))
                    Text(item.xp.toString() + " XP", color = A)
                }
            }
        }
        FeatureCard("➕ BARÁT FELVÉTELE") {
            OutlinedTextField(value = friendName,
                onValueChange = { if(it.length<=25)friendName=it },
                label = { Text("Barát neve") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(value = friendXP,
                onValueChange = { if(it.length<=6 && it.all(Char::isDigit))friendXP=it },
                label = { Text("XP-je (általa megosztott)") }, modifier = Modifier.fillMaxWidth())
            FeatureButton("HOZZÁADÁS",enabled = friendName.trim().length>=2 &&
                (friendXP.toIntOrNull() ?: -1)>=0) {
                if (FocusPrefs.addFriend(ctx,friendName,friendXP.toIntOrNull() ?: 0)) {
                    friendName="";friendXP="";refresh++
                }
            }
        }
    }
}
