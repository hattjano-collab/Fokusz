package hu.fokusz

import android.content.Intent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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

private val BG = Color(0xFF141E32)
private val EDGE = Color(0xFF2B3A55)
private val GREEN = Color(0xFF7BE7C1)
private val LIGHT = Color(0xFFF5F7FF)
private val SOFT = Color(0xFFA7B4CC)
private val YELLOW = Color(0xFFFFC78D)

@Composable private fun QuestCard(content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp))
            .background(Brush.linearGradient(listOf(Color(0xFF24243E), BG)))
            .border(1.dp, EDGE, RoundedCornerShape(24.dp))
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        content = content
    )
}

@Composable fun QuestPanel(
    serviceOn: Boolean,
    usageOk: Boolean,
    overlayOk: Boolean,
    openTimeTab: () -> Unit
) {
    val ctx = LocalContext.current
    var frame by remember { mutableIntStateOf(0) }
    var cameraOn by remember { mutableStateOf(false) }
    var serviceError by remember { mutableStateOf("") }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1000)
            frame++
        }
    }

    val q = QuestStore.current(ctx)
    val phase = QuestStore.phase(ctx)
    val counted = QuestStore.reps(ctx)
    val remain = QuestStore.secondsLeft(ctx)
    val completed = QuestStore.completed(ctx)
    val timed = q.kind == QuestKind.TIMED
    val goalText = if (timed) duration(q.goal) else q.goal.toString() + " ismétlés"
    val enabledTime = serviceOn && usageOk && overlayOk

    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column {
                Text("QUESTEK", color = LIGHT, fontSize = 25.sp,
                    fontWeight = FontWeight.ExtraBold, letterSpacing = 1.5.sp)
                Text("Egy feladat. Egy jutalom. Újabb küldetés.",
                    color = SOFT, fontSize = 12.sp)
            }
            Spacer(Modifier.weight(1f))
            Box(Modifier.clip(RoundedCornerShape(12.dp))
                .background(Color(0xFF1D3D3A)).padding(9.dp)) {
                Text("✦  " + completed, color = GREEN,
                    fontWeight = FontWeight.Bold, fontSize = 14.sp)
            }
        }

        QuestCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("AKTUÁLIS KÜLDETÉS", color = GREEN,
                    fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                Spacer(Modifier.weight(1f))
                Text(if (phase == 2) "TELJESÍTVE" else if (phase == 1) "FOLYAMATBAN" else "ÚJ",
                    color = if (phase == 2) GREEN else YELLOW,
                    fontSize = 11.sp, fontWeight = FontWeight.Bold)
            }
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                Box(
                    Modifier.size(90.dp).clip(CircleShape)
                        .background(Brush.radialGradient(listOf(
                            Color(0xFF49486A), Color(0xFF252841)))),
                    contentAlignment = Alignment.Center
                ) {
                    Text(q.icon, fontSize = 45.sp)
                }
            }
            Text(q.title, color = LIGHT, fontSize = 23.sp,
                fontWeight = FontWeight.Bold, textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth())
            Text(q.description, color = SOFT, fontSize = 14.sp,
                textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Column(
                    Modifier.weight(1f).clip(RoundedCornerShape(14.dp))
                        .background(Color(0xFF1A3441)).padding(12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text("FELADAT", color = SOFT, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    Text(goalText, color = LIGHT, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                }
                Column(
                    Modifier.weight(1f).clip(RoundedCornerShape(14.dp))
                        .background(Color(0xFF173C38)).padding(12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text("JUTALOM", color = SOFT, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    Text("+" + duration(q.reward), color = GREEN,
                        fontWeight = FontWeight.Bold, fontSize = 15.sp)
                }
            }

            if (phase == 2) {
                Text("✓ SIKERES KÜLDETÉS", color = GREEN,
                    fontSize = 19.sp, fontWeight = FontWeight.ExtraBold,
                    textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                Text("A jutalmat hozzáadtam a képernyőidődhöz.", color = SOFT,
                    fontSize = 13.sp, textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth())
                Button(onClick = {
                    QuestStore.next(ctx)
                    cameraOn = false
                    frame++
                }, modifier = Modifier.fillMaxWidth().height(52.dp),
                    shape = RoundedCornerShape(15.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = GREEN,
                        contentColor = Color(0xFF0C1A24))) {
                    Text("KÖVETKEZŐ VÉLETLEN QUEST  ✦", fontWeight = FontWeight.Bold)
                }
            } else if (timed) {
                if (phase == 0) {
                    Text("Indítsd el az időzítőt, és végezd el a feladatot. Amíg tart, a figyelt közösségi appokat a Fókusz letiltja.",
                        color = SOFT, fontSize = 13.sp)
                    if (!usageOk || !overlayOk) {
                        Text("Először engedélyezd a használati adatokat és a megjelenítést más appok felett.",
                            color = YELLOW, fontSize = 12.sp)
                        OutlinedButton(onClick = openTimeTab, modifier = Modifier.fillMaxWidth()) {
                            Text("Engedélyek megnyitása")
                        }
                    } else if (!serviceOn) {
                        Text("Az időzítőhöz el kell indítani a háttérvédelmet.",
                            color = YELLOW, fontSize = 12.sp)
                        Button(onClick = {
                            try {
                                ctx.startForegroundService(Intent(ctx, FocusService::class.java))
                                serviceError = ""
                            } catch (ex: Exception) {
                                serviceError = "Nem indul a védelem: " + (ex.message ?: "hiba")
                            }
                        }, modifier = Modifier.fillMaxWidth()) { Text("Háttérvédelem bekapcsolása") }
                        if (serviceError.isNotEmpty()) Text(serviceError, color = YELLOW)
                    } else {
                        Button(onClick = {
                            if (QuestStore.startTimed(ctx)) frame++
                        }, modifier = Modifier.fillMaxWidth().height(52.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = GREEN,
                                contentColor = Color(0xFF0C1A24)),
                            shape = RoundedCornerShape(15.dp)) {
                            Text("START  •  " + clock(q.goal), fontWeight = FontWeight.Bold)
                        }
                    }
                } else {
                    Text(if (remain > 0) "HÁTRALÉVŐ IDŐ" else "AZ IDŐZÍTŐ LEJÁRT",
                        color = if (remain > 0) YELLOW else GREEN,
                        fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    Text(clock(remain), color = LIGHT, fontSize = 52.sp,
                        fontWeight = FontWeight.ExtraBold,
                        modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
                    LinearProgressIndicator(
                        progress = { ((q.goal - remain).toFloat() / q.goal).coerceIn(0f, 1f) },
                        color = GREEN,
                        trackColor = Color(0xFF33445F),
                        modifier = Modifier.fillMaxWidth().height(9.dp).clip(CircleShape)
                    )
                    if (remain > 0) {
                        Text("🔒 A közösségi appok le vannak tiltva a quest alatt. A visszaszámlálás csak aktív háttérvédelem mellett halad.",
                            color = SOFT, fontSize = 12.sp)
                        if (!serviceOn) {
                            Text("A védelem leállt, az időzítő szünetel!", color = YELLOW)
                            Button(onClick = {
                                try { ctx.startForegroundService(Intent(ctx, FocusService::class.java)) }
                                catch (_: Exception) { openTimeTab() }
                            }) { Text("Védelem újraindítása") }
                        }
                    } else {
                        Text("Ha tényleg elvégezted a feladatot, igazold a teljesítést. Az időzítő önmagában nem bizonyítja a takarítást vagy a tanulást.",
                            color = SOFT, fontSize = 12.sp)
                        Button(onClick = {
                            if (QuestStore.completeTimed(ctx)) frame++
                        }, modifier = Modifier.fillMaxWidth().height(52.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = GREEN,
                                contentColor = Color(0xFF0C1A24)),
                            shape = RoundedCornerShape(15.dp)) {
                            Text("TELJESÍTETTEM  •  +" + duration(q.reward),
                                fontWeight = FontWeight.Bold)
                        }
                    }
                }
            } else {
                Text("Kamerával számolt ismétlések", color = SOFT, fontSize = 12.sp)
                Text(counted.toString() + " / " + q.goal, color = LIGHT, fontSize = 49.sp,
                    fontWeight = FontWeight.ExtraBold, modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center)
                LinearProgressIndicator(
                    progress = { counted.toFloat() / q.goal },
                    modifier = Modifier.fillMaxWidth().height(8.dp).clip(CircleShape),
                    color = GREEN, trackColor = Color(0xFF33445F)
                )
                if (!cameraOn) {
                    Button(onClick = { cameraOn = true }, modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = GREEN,
                            contentColor = Color(0xFF0C1A24))) {
                        Text("KAMERÁS SZÁMLÁLÓ INDÍTÁSA", fontWeight = FontWeight.Bold)
                    }
                } else {
                    OutlinedButton(onClick = { cameraOn = false },
                        modifier = Modifier.fillMaxWidth()) { Text("Kamera kikapcsolása") }
                    PushupCamera(q.id) {
                        QuestStore.rep(ctx, q.id)
                        frame++
                    }
                }
            }
        }
        Text("40 különböző feladat, véletlen sorrendben. Egyszerre csak egy questet kapsz. A listát nem lehet kézzel kiválasztani.",
            modifier = Modifier.fillMaxWidth(), color = SOFT,
            fontSize = 12.sp, textAlign = TextAlign.Center)
    }
}
