package hu.fokusz

import android.content.Intent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import java.util.Locale

private val QBG = Color(0xFF141E32)
private val QEDGE = Color(0xFF2B3A55)
private val QGREEN = Color(0xFF7BE7C1)
private val QWHITE = Color(0xFFF5F7FF)
private val QSOFT = Color(0xFFA7B4CC)
private val QYELLOW = Color(0xFFFFC78D)
private val QLILAC = Color(0xFFB9AAFF)

@Composable private fun QCard(
    tones: List<Color> = listOf(Color(0xFF22253E), QBG),
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp))
            .background(Brush.linearGradient(tones))
            .border(1.dp, QEDGE, RoundedCornerShape(24.dp))
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(13.dp),
        content = content
    )
}

@Composable private fun QPrimary(label: String, enabled: Boolean = true, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth().height(52.dp),
        shape = RoundedCornerShape(15.dp),
        colors = ButtonDefaults.buttonColors(containerColor = QGREEN,
            contentColor = Color(0xFF0C1A24), disabledContainerColor = QEDGE,
            disabledContentColor = QSOFT)
    ) {
        Text(label, fontWeight = FontWeight.Bold)
    }
}

@Composable private fun QSecondary(label: String, enabled: Boolean = true, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth().height(46.dp),
        shape = RoundedCornerShape(15.dp),
        border = BorderStroke(1.dp, QEDGE),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = QWHITE,
            disabledContentColor = QSOFT),
    ) { Text(label, fontWeight = FontWeight.SemiBold) }
}

@Composable private fun StarterStatus(title: String, done: Boolean, reward: String) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
            .background(if (done) Color(0xFF1B383B) else Color(0xFF222C46))
            .padding(horizontal = 13.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(if (done) "✓" else "○",
            color = if (done) QGREEN else QYELLOW, fontWeight = FontWeight.Bold)
        Spacer(Modifier.size(10.dp))
        Text(title, color = QWHITE, fontWeight = FontWeight.Medium,
            modifier = Modifier.weight(1f))
        Text(reward, color = if (done) QGREEN else QSOFT)
    }
}

@Composable fun QuestPanel(
    serviceOn: Boolean,
    usageOk: Boolean,
    overlayOk: Boolean,
    openTimeTab: () -> Unit
) {
    val ctx = LocalContext.current
    var refresh by remember { mutableIntStateOf(0) }
    var cameraOn by remember { mutableStateOf(false) }
    var customEditing by remember { mutableStateOf(false) }
    var customTitle by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1000)
            refresh++
        }
    }

    val quest = remember(refresh) { QuestStore.ensureQuest(ctx) }
    val p = QuestStore.phase(ctx)
    val doneChess = QuestStore.chessDone(ctx)
    val donePush = QuestStore.pushDone(ctx)
    val pushTarget = QuestStore.pushTarget(ctx)
    val leftSkips = 3 - QuestStore.skips(ctx)
    val customUsed = QuestStore.customUsed(ctx)
    val completeCount = QuestStore.completed(ctx)
    val remaining = QuestStore.secondsLeft(ctx)
    val serviceReady = serviceOn && usageOk && overlayOk
    val active = QuestStore.active(ctx)

    fun update() { refresh++ }

    Column(verticalArrangement = Arrangement.spacedBy(17.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column {
                Text("NAPI KÜLDETÉSEK", color = QWHITE,
                    fontWeight = FontWeight.ExtraBold, fontSize = 24.sp)
                Text("Te választod a sorrendet. Az extra questek utána jönnek.",
                    color = QSOFT, fontSize = 12.sp)
            }
        }
        QCard(listOf(Color(0xFF1D3F43), Color(0xFF17263C))) {
            Row {
                Text("✦  MAI HALADÁS", color = QGREEN, fontWeight = FontWeight.Bold)
                Spacer(Modifier.weight(1f))
                Text(completeCount.toString() + " kész", color = QGREEN, fontWeight = FontWeight.Bold)
            }
            StarterStatus("♟️  Sakkmeccs", doneChess, "+5 perc")
            StarterStatus("💪  " + pushTarget + " fekvőtámasz", donePush, "+10 perc")
            LinearProgressIndicator(
                progress = { (listOf(doneChess, donePush).count { it }.toFloat() / 2f) },
                modifier = Modifier.fillMaxWidth().height(9.dp).clip(CircleShape),
                color = QGREEN, trackColor = Color(0xFF33525B)
            )
            Text(
                if (doneChess && donePush) "Mindkét kezdő quest kész! Az extra questek feloldva."
                else "Előbb a két kezdő questet teljesítsd (vagy helyettesítsd / skippeld).",
                color = QSOFT, fontSize = 12.sp
            )
        }

        if (quest == null) {
            QCard {
                Text("MELYIKKEL KEZDESZ?", color = QLILAC,
                    fontWeight = FontWeight.ExtraBold, fontSize = 21.sp)
                Text("A két kezdő quest sorrendjét te választod. Mindkettőt meg kell oldani, mielőtt extra feladatot kapsz.",
                    color = QSOFT)
                if (!doneChess) QPrimary("♟  SAKKMECCS  ·  +5 perc") {
                    QuestStore.chooseStarter(ctx, "chess")
                    update()
                }
                if (!donePush) QPrimary("💪  " + pushTarget + " FEKVŐ  ·  +10 perc") {
                    QuestStore.chooseStarter(ctx, "push")
                    update()
                }
            }
        } else {
            QCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (active == "chess" || active == "push") "NAPI KEZDŐ QUEST"
                        else if (active == "custom") "EGYÉNI QUEST" else "EXTRA QUEST",
                        color = QLILAC,
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp)
                    Spacer(Modifier.weight(1f))
                    Text(if (p == 2) "✓ KÉSZ" else if (p == 1) "FOLYAMATBAN" else "AKTÍV",
                        color = if (p == 2) QGREEN else QYELLOW, fontWeight = FontWeight.Bold,
                        fontSize = 11.sp)
                }
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Box(Modifier.size(88.dp).clip(CircleShape)
                        .background(Brush.radialGradient(listOf(Color(0xFF4B496B), Color(0xFF27293E)))),
                        contentAlignment = Alignment.Center) {
                        Text(quest.icon, fontSize = 43.sp)
                    }
                }
                Text(quest.title, color = QWHITE,
                    fontSize = 23.sp,
                    fontWeight = FontWeight.Bold, textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth())
                Text(quest.description, color = QSOFT, textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Column(Modifier.weight(1f).clip(RoundedCornerShape(14.dp))
                        .background(Color(0xFF21334C)).padding(12.dp),
                        horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("FELADAT", color = QSOFT,
                            fontSize = 11.sp)
                        Text(when (quest.kind) {
                            QuestKind.TIMED -> duration(quest.goal)
                            QuestKind.CHESS -> "1 játszma"
                            QuestKind.PUSHUPS -> quest.goal.toString() + " fekvő"
                        }, color = QWHITE, fontWeight = FontWeight.Bold)
                    }
                    Column(Modifier.weight(1f).clip(RoundedCornerShape(14.dp))
                        .background(Color(0xFF1B3D3B)).padding(12.dp),
                        horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("JUTALOM", color = QSOFT,
                            fontSize = 11.sp)
                        Text("+" + duration(quest.reward), color = QGREEN,
                            fontWeight = FontWeight.Bold)
                    }
                }

                if (p == 2) {
                    Text("✓ " + QuestStore.result(ctx), color = QGREEN,
                        textAlign = TextAlign.Center, fontWeight = FontWeight.Bold,
                        modifier = Modifier.fillMaxWidth())
                    QPrimary("KÖVETKEZŐ QUEST  ✦") {
                        QuestStore.next(ctx)
                        cameraOn = false
                        customEditing = false
                        customTitle = ""
                        error = ""
                        update()
                    }
                } else if (quest.kind == QuestKind.CHESS) {
                    if (p == 0) {
                        Text("Játssz egy teljes sakkmeccset élőben vagy egy alkalmazásban. Az eredménytől függetlenül jár az 5 perc.",
                            color = QSOFT)
                        QPrimary("♟  SAKKMECCS KEZDÉSE") {
                            QuestStore.startChess(ctx); update()
                        }
                    } else {
                        Text("Játék folyamatban. Ha a parti valóban véget ért, igazold a teljesítést. Az alkalmazás a sakkpartit automatikusan nem tudja ellenőrizni.",
                            color = QSOFT)
                        QPrimary("LEJÁTSZOTTAM A MECCSET  ·  +5 perc") {
                            QuestStore.completeChess(ctx); update()
                        }
                    }
                } else if (quest.kind == QuestKind.PUSHUPS) {
                    Text("KAMERÁVAL SZÁMOLT ISMÉTLÉSEK", color = QSOFT,
                        fontWeight = FontWeight.Bold)
                    Text(QuestStore.reps(ctx).toString() + " / " + quest.goal,
                        color = QWHITE, textAlign = TextAlign.Center,
                        fontSize = 48.sp,
                        fontWeight = FontWeight.ExtraBold, modifier = Modifier.fillMaxWidth())
                    LinearProgressIndicator(
                        progress = { QuestStore.reps(ctx).toFloat() / quest.goal },
                        color = QGREEN, trackColor = QEDGE,
                        modifier = Modifier.fillMaxWidth().height(9.dp).clip(CircleShape)
                    )
                    if (!cameraOn) {
                        QPrimary("KAMERÁS SZÁMLÁLÓ INDÍTÁSA") { cameraOn = true }
                    } else {
                        QSecondary("Kamera kikapcsolása") { cameraOn = false }
                        PushupCamera(quest.id) {
                            QuestStore.rep(ctx, quest.id)
                            update()
                        }
                    }
                    Text("A kamerás testtartás-felismerés becslés; nem garantálható, hogy minden szabálytalan ismétlést felismer.",
                        color = QSOFT, fontSize = 12.sp)
                } else {
                    if (p == 0) {
                        Text("A feladat ideje alatt a figyelt közösségi appok nem használhatók. A visszaszámlálást az aktív háttérvédelem futtatja.",
                            color = QSOFT)
                        if (!usageOk || !overlayOk) {
                            Text("Először engedélyezd a használati adatok és az átfedő ablakok hozzáférését!",
                                color = QYELLOW)
                            QSecondary("Engedélyek a Képernyőidő fülön") { openTimeTab() }
                        } else if (!serviceOn) {
                            Text("A háttérvédelem nincs bekapcsolva.", color = QYELLOW)
                            QPrimary("VÉDELEM INDÍTÁSA") {
                                try { ctx.startForegroundService(Intent(ctx, FocusService::class.java)) }
                                catch (e: Exception) { error = e.message ?: "Nem indult a védelem" }
                            }
                        } else {
                            QPrimary("START  ·  " + clock(quest.goal)) {
                                QuestStore.startTimed(ctx); update()
                            }
                        }
                    } else {
                        Text("HÁTRALÉVŐ IDŐ", color = if (remaining > 0) QYELLOW else QGREEN,
                            fontWeight = FontWeight.Bold)
                        Text(clock(remaining), color = QWHITE,
                            textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth(),
                            fontSize = 50.sp,
                            fontWeight = FontWeight.ExtraBold)
                        LinearProgressIndicator(
                            progress = { ((quest.goal - remaining).toFloat() / quest.goal).coerceIn(0f, 1f) },
                            modifier = Modifier.fillMaxWidth().height(9.dp).clip(CircleShape),
                            color = QGREEN, trackColor = QEDGE
                        )
                        if (remaining > 0) {
                            Text("🔒 A közösségi appok zárolva vannak a quest alatt.",
                                color = QSOFT)
                            if (!serviceOn) {
                                Text("A háttérvédelem nem fut. Az időzítő szünetel.", color = QYELLOW)
                                QSecondary("Védelem újraindítása") {
                                    try { ctx.startForegroundService(Intent(ctx, FocusService::class.java)) }
                                    catch (_: Exception) { openTimeTab() }
                                }
                            }
                        } else {
                            Text("Az idő letelt. Erősítsd meg, hogy valóban elvégezted a feladatot!",
                                color = QSOFT)
                            QPrimary("TELJESÍTETTEM  ·  +" + duration(quest.reward)) {
                                QuestStore.completeTimed(ctx); update()
                            }
                        }
                    }
                }
            }

            if (p == 0) {
                QCard(listOf(Color(0xFF272A3A), QBG)) {
                    Text("ALTERNATÍVÁK", color = QWHITE,
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp)
                    Text("A nap folyamán háromszor kihagyhatsz egy questet, darabonként 5 percért.",
                        color = QSOFT)
                    QSecondary(
                        "QUEST KIHAGYÁSA  ·  –5 perc  (" + leftSkips + "/3 maradt)",
                        enabled = leftSkips > 0 && Store.bal(ctx) >= 300
                    ) {
                        if (QuestStore.skip(ctx)) {
                            cameraOn = false
                            error = ""
                            update()
                        } else error = "A kihagyáshoz legalább 5 perc egyenleg kell."
                    }
                    if (leftSkips > 0 && Store.bal(ctx) < 300) {
                        Text("Nincs 5 perc az egyenlegeden a kihagyáshoz.", color = QYELLOW)
                    }
                    if (!customUsed) {
                        Text("Napi 1× lecserélheted az aktuális questet egy saját, 15 perces feladatra. Jutalom: +10 perc. Közben social media tiltás.",
                            color = QSOFT)
                        if (!customEditing) {
                            QSecondary("✦  EGYÉNI QUEST HELYETTE") { customEditing = true }
                        } else {
                            OutlinedTextField(
                                value = customTitle,
                                onValueChange = { if (it.length <= 80) customTitle = it },
                                label = { Text("Mit fogsz csinálni 15 percig?") },
                                placeholder = { Text("Pl. rendet rakok a szobámban") },
                                maxLines = 2,
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(15.dp)
                            )
                            QPrimary("CSERE  ·  15 PERC  →  +10 PERC",
                                enabled = customTitle.trim().length >= 3) {
                                if (QuestStore.useCustom(ctx, customTitle)) {
                                    cameraOn = false
                                    customEditing = false
                                    update()
                                }
                            }
                            QSecondary("Mégsem") { customEditing = false }
                        }
                    } else {
                        Text("✓ A napi egyszeri egyéni quest lehetőségét már felhasználtad.",
                            color = QSOFT)
                    }
                    if (error.isNotEmpty()) Text(error, color = QYELLOW)
                }
            }
        }

        QCard(listOf(Color(0xFF1A303A), QBG)) {
            Row {
                Text("MAI SZABÁLYOK", color = QGREEN, fontWeight = FontWeight.Bold)
                Spacer(Modifier.weight(1f))
                Text(java.time.LocalDate.now().format(
                    java.time.format.DateTimeFormatter.ofPattern("MM. dd.", Locale("hu"))),
                    color = QSOFT)
            }
            Text("♟  1 sakkmeccs: +5 perc", color = QWHITE)
            Text("💪  10 vagy 20 fekvőtámasz: +10 perc", color = QWHITE)
            Text("↪  3 kihagyás naponta: –5 perc / alkalom", color = QWHITE)
            Text("✦  1 egyéni csere naponta: 15 perc → +10 perc", color = QWHITE)
            Text("A napi lehetőségek éjfélkor megújulnak. Az extra questek a két kezdő feladat után véletlenszerűen következnek.",
                color = QSOFT, fontSize = 12.sp)
        }
    }
}
