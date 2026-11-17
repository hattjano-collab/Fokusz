package hu.fokusz

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

private val DUO=Color(0xFF83E26C)
private val DWHITE=Color(0xFFF6F9FF)
private val DMUTED=Color(0xFFACB9CF)
private val DGOLD=Color(0xFFFFD58D)

@Composable fun DuolingoPanel() {
    val ctx=LocalContext.current
    val scope=rememberCoroutineScope()
    var user by remember { mutableStateOf(DuolingoStore.username(ctx)) }
    var connected by remember { mutableStateOf(DuolingoStore.linked(ctx)) }
    var editing by remember { mutableStateOf(!DuolingoStore.linked(ctx)) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf(DuolingoStore.lastMessage(ctx)) }
    var refreshed by remember {mutableIntStateOf(0)}
    val uiRefresh=refreshed
    val today=DuolingoStore.rewardedToday(ctx)
    val currentStreak=DuolingoStore.streak(ctx)
    val xp=DuolingoStore.todayXp(ctx)
    val transition=rememberInfiniteTransition(label="Duolingo streak animáció")
    val flameScale by transition.animateFloat(0.96f,1.08f,
        animationSpec=infiniteRepeatable(tween(1050),RepeatMode.Reverse),
        label="Szüntelen motiváció")

    val permissionRequest=rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()) { refreshed++ }
    val notificationRefresh=uiRefresh
    val hasNotify=Build.VERSION.SDK_INT<33 || ContextCompat.checkSelfPermission(
        ctx,Manifest.permission.POST_NOTIFICATIONS)==PackageManager.PERMISSION_GRANTED

    LaunchedEffect(connected) {
        if(connected) {
            DuolingoStore.scheduleReminders(ctx)
            busy=true
            val response=DuolingoStore.check(ctx)
            status=response.message
            busy=false
            refreshed++
        }
    }
    Column(verticalArrangement=Arrangement.spacedBy(16.dp)) {
        Row(verticalAlignment=Alignment.CenterVertically,
            horizontalArrangement=Arrangement.spacedBy(12.dp)) {
            Box(Modifier.size(65.dp).graphicsLayer {
                scaleX=flameScale; scaleY=flameScale
            }.clip(CircleShape).background(
                Brush.radialGradient(listOf(Color(0xFF395C31),Color(0xFF213326)))),
                contentAlignment=Alignment.Center) {
                Text("🔥",fontSize=37.sp)
            }
            Column {
                Text("DUOLINGO STREAK",color=DWHITE,fontSize=23.sp,
                    fontWeight=FontWeight.Black)
                Text("Napi tanulás = extra képernyőidő",color=DMUTED,fontSize=12.sp)
            }
        }
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp))
            .background(Brush.linearGradient(listOf(Color(0xFF214B35),
                Color(0xFF172D3C))))
            .border(1.dp,Color(0xFF456C59),RoundedCornerShape(24.dp))
            .padding(18.dp),verticalArrangement=Arrangement.spacedBy(14.dp)) {
            Text("A NAPI JUTALMAD",color=DUO,fontSize=12.sp,fontWeight=FontWeight.Bold)
            Text("+3 perc",color=DWHITE,fontSize=39.sp,fontWeight=FontWeight.ExtraBold)
            Text(if(today)"✓ A mai jutalom már jóváírva." else
                "Gyakorolj egyet a Duolingóban, és ellenőrizd a napi teljesítésed.",
                color=if(today)DUO else DMUTED,fontSize=13.sp)
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                Column(Modifier.weight(1f).clip(RoundedCornerShape(15.dp))
                    .background(Color(0xFF213D37)).padding(14.dp)) {
                    Text("NAPOS STREAK",color=DMUTED,fontSize=11.sp)
                    Text("🔥 "+currentStreak,color=DWHITE,fontSize=23.sp,
                        fontWeight=FontWeight.Black)
                }
                Column(Modifier.weight(1f).clip(RoundedCornerShape(15.dp))
                    .background(Color(0xFF213D37)).padding(14.dp)) {
                    Text("MAI XP",color=DMUTED,fontSize=11.sp)
                    Text(xp.toString(),color=DWHITE,fontSize=23.sp,
                        fontWeight=FontWeight.Black)
                }
            }
            LinearProgressIndicator(progress={if(today)1f else 0f},
                modifier=Modifier.fillMaxWidth().height(11.dp)
                    .clip(RoundedCornerShape(10.dp)),
                color=DUO,trackColor=Color(0xFF365345))
        }
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp))
            .background(Color(0xFF1A2840)).padding(16.dp).animateContentSize(),
            verticalArrangement=Arrangement.spacedBy(12.dp)) {
            Text("🔗 PROFIL ÖSSZEKÖTÉSE",color=DUO,
                fontWeight=FontWeight.Bold)
            Text("Egyszer írd be a Duolingo nyilvános felhasználónevedet. Jelszót nem kérünk.",
                color=DMUTED,fontSize=12.sp)
            if(connected && !editing) {
                Text("✓ "+DuolingoStore.username(ctx),color=DWHITE,
                    fontSize=18.sp,fontWeight=FontWeight.Bold)
                OutlinedButton(onClick={editing=true},
                    modifier=Modifier.fillMaxWidth()) {
                    Text("Másik profil beállítása")
                }
            } else {
                OutlinedTextField(value=user,onValueChange={
                    if(it.length<=40)user=it
                },singleLine=true,modifier=Modifier.fillMaxWidth(),
                    label={Text("Duolingo felhasználónév")},
                    placeholder={Text("Például: pelda123")})
                Button(onClick={
                    busy=true
                    scope.launch {
                        val result=DuolingoStore.connect(ctx,user)
                        status=result.message
                        connected=result.success
                        editing=!result.success
                        busy=false
                        refreshed++
                    }
                },enabled=!busy && user.trim().length>=2,
                    colors=ButtonDefaults.buttonColors(
                        containerColor=DUO,contentColor=Color(0xFF0E1B1A)),
                    modifier=Modifier.fillMaxWidth()) {
                    Text(if(busy)"ELLENŐRZÉS…"else "PROFIL MENTÉSE",
                        fontWeight=FontWeight.Bold)
                }
                if(connected) OutlinedButton(onClick={editing=false},
                    modifier=Modifier.fillMaxWidth()) {Text("Mégsem")}
            }
            if(connected) {
                Button(onClick={
                    busy=true
                    scope.launch {
                        val result=DuolingoStore.check(ctx)
                        status=result.message
                        busy=false
                        refreshed++
                    }
                },enabled=!busy,modifier=Modifier.fillMaxWidth(),
                    colors=ButtonDefaults.buttonColors(containerColor=DUO,
                        contentColor=Color(0xFF0E1B1A))) {
                    Text(if(busy)"FRISSÍTÉS…"else "✓ MAI LECKE ELLENŐRZÉSE",
                        fontWeight=FontWeight.Bold)
                }
            }
            AnimatedVisibility(status.isNotBlank()) {
                Text(status,color=if(today)DUO else DGOLD,fontSize=13.sp,
                    fontWeight=FontWeight.SemiBold)
            }
            OutlinedButton(onClick={
                val launch=ctx.packageManager.getLaunchIntentForPackage("com.duolingo")
                try {
                    if(launch!=null)ctx.startActivity(launch)
                    else ctx.startActivity(Intent(Intent.ACTION_VIEW,
                        Uri.parse("https://www.duolingo.com/learn")))
                }catch(_:Exception){}
            },modifier=Modifier.fillMaxWidth()) {
                Text("🦉 DUOLINGO MEGNYITÁSA")
            }
        }
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp))
            .background(Color(0xFF1A2840)).padding(16.dp),
            verticalArrangement=Arrangement.spacedBy(12.dp)) {
            Text("🔔 NAPI EMLÉKEZTETŐK",color=DUO,
                fontWeight=FontWeight.Bold)
            Row(verticalAlignment=Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Emlékeztessen, ha még nincs kész",color=DWHITE,fontSize=13.sp)
                    Text("17:00 • 20:30 • 22:30",color=DMUTED,fontSize=11.sp)
                }
                Switch(checked=DuolingoStore.notifications(ctx),
                    onCheckedChange={
                        DuolingoStore.setNotifications(ctx,it)
                        refreshed++
                    })
            }
            if(!hasNotify) {
                Text("A telefon engedélyét is meg kell adnod, különben az emlékeztetők nem jelennek meg.",
                    color=DGOLD,fontSize=12.sp)
                Button(onClick={
                    if(Build.VERSION.SDK_INT>=33)
                        permissionRequest.launch(Manifest.permission.POST_NOTIFICATIONS)
                },modifier=Modifier.fillMaxWidth()) {
                    Text("ÉRTESÍTÉSEK ENGEDÉLYEZÉSE")
                }
            } else Text("✓ Az alkalmazásnak van értesítési jogosultsága.",
                color=DUO,fontSize=12.sp)
            Text("A rendszer energiatakarékos módja miatt az időpontok néhány percet késhetnek.",
                color=DMUTED,fontSize=11.sp)
        }
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp))
            .background(Color(0xFF172538)).padding(16.dp),
            verticalArrangement=Arrangement.spacedBy(8.dp)) {
            Text("HOGYAN MŰKÖDIK?",color=DUO,fontWeight=FontWeight.Bold)
            Text("A Fókusz a nyilvános Duolingo-profil napi XP-jét vagy a napi XP-cél teljesítését keresi. Ugyanarra a napra csak egyszer ad +3 percet.",
                color=DMUTED,fontSize=12.sp)
            Text("A Duolingo nem biztosít ehhez hivatalos nyilvános integrációt. Ha az API hibás vagy nem elérhető, a jóváírás nem történik meg automatikusan. Egy nyilvános felhasználónév nem igazolja, kié a fiók.",
                color=DMUTED,fontSize=11.sp)
        }
    }
}
