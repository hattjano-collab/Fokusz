package hu.fokusz

import android.content.Intent
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.MultiFormatWriter
import kotlinx.coroutines.delay

private val TC=Color(0xFF7BE7C1)
private val TV=Color(0xFFEDF4FF)
private val TM=Color(0xFFADC0D1)
private val TBR=Color(0xFF334D60)
private val TY=Color(0xFFFFD58D)

private fun teamQR(value:String):android.graphics.Bitmap?=try {
    val hints=mapOf(EncodeHintType.MARGIN to 2,
        EncodeHintType.CHARACTER_SET to "UTF-8")
    val pixels=MultiFormatWriter().encode(value,BarcodeFormat.QR_CODE,512,512,hints)
    val image=android.graphics.Bitmap.createBitmap(512,512,
        android.graphics.Bitmap.Config.ARGB_8888)
    val colors=IntArray(512*512)
    for(y in 0 until 512)for(x in 0 until 512)
        colors[y*512+x]=if(pixels[x,y])android.graphics.Color.BLACK else
            android.graphics.Color.WHITE
    image.setPixels(colors,0,512,0,0,512,512)
    image
}catch(_:Exception){null}

@Composable private fun TeamCard(title:String,body:@Composable androidx.compose.foundation.layout.ColumnScope.()->Unit) {
    Column(Modifier.fillMaxWidth().animateContentSize()
        .background(Color(0xFF192A42),RoundedCornerShape(22.dp))
        .border(1.dp,TBR,RoundedCornerShape(22.dp))
        .padding(17.dp),verticalArrangement=Arrangement.spacedBy(11.dp)) {
        Text(title,color=TC,fontSize=15.sp,fontWeight=FontWeight.ExtraBold)
        body()
    }
}

@Composable fun TeamPanel() {
    val ctx=LocalContext.current
    var tick by remember {mutableIntStateOf(0)}
    var title by remember {mutableStateOf("Fókusz csapat")}
    var nickname by remember {mutableStateOf(TeamStore.nickname(ctx).let {
        if(it=="Tag")"" else it
    })}
    var share by remember {mutableStateOf(TeamStore.sharing(ctx))}
    var warning by remember {mutableStateOf("")}
    var showLeave by remember {mutableStateOf(false)}
    val active=TeamStore.active(ctx)
    val host=TeamStore.host(ctx)
    val perms=TeamNearby.allowed(ctx)
    val teamMembers=remember(tick,active) { if(active)TeamStore.members(ctx) else emptyList() }
    val qrText=if(active&&host)TeamStore.qr(ctx) else ""
    val code=remember(qrText){if(qrText.isNotEmpty())teamQR(qrText)else null}
    fun launch() {
        if(!TeamStore.active(ctx))return
        try{
            ContextCompat.startForegroundService(ctx,
                Intent(ctx,TeamSyncService::class.java))
            warning="A csapat szinkronizálása elindult."
        }catch(e:Exception){
            warning="Nem indul a szinkron: "+(e.localizedMessage ?: "rendszerhiba")
        }
    }
    val request=rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()) {result ->
            if(TeamNearby.allowed(ctx)) launch()
            else warning="A közeli eszközök engedélye szükséges a csatlakozáshoz."
            tick++
        }
    fun startSync() {
        if(TeamNearby.allowed(ctx))launch()
        else request.launch(TeamNearby.permissions())
    }
    LaunchedEffect(active) {
        if(active && !TeamSyncService.isRunning && TeamNearby.allowed(ctx))startSync()
    }
    LaunchedEffect(Unit) {
        while(true){delay(1600);tick++}
    }
    Column(verticalArrangement=Arrangement.spacedBy(17.dp)) {
        Text("👥 CSAPATOK",color=TV,fontSize=26.sp,fontWeight=FontWeight.Black)
        Text("Szerver nélkül, közeli telefonok között. QR-kóddal lehet összekapcsolni a készülékeket.",
            color=TM,fontSize=12.sp)
        if(!active) {
            TeamCard("✨ ÚJ CSAPAT LÉTREHOZÁSA") {
                OutlinedTextField(value=title,onValueChange={if(it.length<=40)title=it},
                    label={Text("Csapat neve")},modifier=Modifier.fillMaxWidth(),
                    singleLine=true)
                OutlinedTextField(value=nickname,onValueChange={if(it.length<=24)nickname=it},
                    label={Text("Beceneved")},modifier=Modifier.fillMaxWidth(),
                    singleLine=true)
                Row(verticalAlignment=Alignment.CenterVertically) {
                    Checkbox(checked=share,onCheckedChange={share=it})
                    Text("Megosztom a csapattal a maradék időmet és a napi használt időt.",
                        color=TM,fontSize=12.sp)
                }
                Button(onClick={
                    TeamStore.setNickname(ctx,nickname)
                    TeamStore.setSharing(ctx,share)
                    if(TeamStore.create(ctx,title)){startSync();tick++}
                    else warning="Érvénytelen csapatnév."
                },enabled=title.trim().length>=3&&nickname.trim().length>=2,
                    modifier=Modifier.fillMaxWidth(),
                    colors=ButtonDefaults.buttonColors(containerColor=TC,
                        contentColor=Color(0xFF0D1C26))) {
                    Text("CSAPAT LÉTREHOZÁSA",fontWeight=FontWeight.Bold)
                }
            }
            TeamCard("📷 CSATLAKOZÁS QR-KÓDDAL") {
                Text("A barátod a Csapatok fülön megmutatja a saját QR-kódját. Ezt olvasd be. Ha mindkét telefon a közelben van, automatikusan összekapcsolódnak.",
                    color=TM,fontSize=12.sp)
                OutlinedTextField(value=nickname,onValueChange={if(it.length<=24)nickname=it},
                    label={Text("Beceneved")},modifier=Modifier.fillMaxWidth(),
                    singleLine=true)
                Row(verticalAlignment=Alignment.CenterVertically) {
                    Checkbox(checked=share,onCheckedChange={share=it})
                    Text("Beleegyezem a képernyőidő-egyenlegem és a használatom megosztásába.",
                        color=TM,fontSize=12.sp)
                }
                Button(onClick={
                    TeamStore.setNickname(ctx,nickname)
                    TeamStore.setSharing(ctx,share)
                    GmsBarcodeScanning.getClient(ctx).startScan()
                        .addOnSuccessListener { result ->
                            val raw=result.rawValue ?: ""
                            if(TeamStore.join(ctx,raw)) {
                                warning="QR-kód beolvasva! Közeli telefon keresése…"
                                startSync();tick++
                            } else warning="Ez nem érvényes Fókusz csapat-QR."
                        }.addOnFailureListener {
                            warning="QR-olvasási hiba: "+
                                (it.localizedMessage ?: "nem elérhető")
                        }
                },enabled=nickname.trim().length>=2,
                    modifier=Modifier.fillMaxWidth(),
                    colors=ButtonDefaults.buttonColors(containerColor=Color(0xFFB9AAFF),
                        contentColor=Color(0xFF121525))) {
                    Text("📷  CSAPAT QR-KÓD BEOLVASÁSA")
                }
            }
        } else {
            TeamCard("🌐 "+TeamStore.name(ctx)) {
                Text(if(host)"TE VAGY A CSAPAT GAZDÁJA" else "CSATLAKOZOTT TAG",
                    color=TY,fontSize=11.sp,fontWeight=FontWeight.Bold)
                Text(TeamNearby.status,color=if(TeamNearby.connected)TC else TM,fontSize=12.sp)
                if(!TeamSyncService.isRunning||!perms) {
                    Button(onClick={startSync()},modifier=Modifier.fillMaxWidth(),
                        colors=ButtonDefaults.buttonColors(containerColor=TC,
                            contentColor=Color(0xFF10202B))) {
                        Text("SZINKRON INDÍTÁSA")
                    }
                } else {
                    OutlinedButton(onClick={TeamNearby.sync(ctx);tick++},
                        modifier=Modifier.fillMaxWidth()) {
                        Text("🔄 CSAPAT FRISSÍTÉSE")
                    }
                }
                Text("A gazda telefonjának a közelben kell lennie az új közös percek kiosztásához. Távollétben a már kiadott rövid időkeret használható.",
                    color=TM,fontSize=11.sp)
            }
            if(host)TeamCard("🔳 MEGHÍVÓ QR-KÓD") {
                Text("A barátaid ezt a kódot olvassák be a Fókusz alkalmazásban.",
                    color=TM,fontSize=12.sp)
                if(code!=null) {
                    Image(bitmap=code.asImageBitmap(),contentDescription="Csapat meghívó QR",
                        modifier=Modifier.fillMaxWidth().height(260.dp)
                            .background(Color.White,RoundedCornerShape(16.dp)).padding(12.dp),
                        contentScale=ContentScale.Fit)
                } else Text("Nem sikerült a QR-kód megjelenítése.",color=TY)
                Text("A QR-kód titkos csapatkulcsot tartalmaz. Csak annak mutasd meg, akit meghívsz.",
                    color=TY,fontSize=11.sp)
            }
            TeamCard("⏱️ KÖZÖS KÉPERNYŐIDŐ") {
                val shared=TeamStore.pool(ctx)
                val bank=TeamStore.bank(ctx)
                Row(horizontalArrangement=Arrangement.spacedBy(9.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text("MAI CSAPATKERET",color=TM,fontSize=10.sp)
                        Text(duration(shared),color=TC,fontSize=24.sp,fontWeight=FontWeight.Black)
                    }
                    Column(Modifier.weight(1f)) {
                        Text("CSAPATBANK",color=TM,fontSize=10.sp)
                        Text(duration(bank),color=TV,fontSize=24.sp,fontWeight=FontWeight.Black)
                    }
                }
                Text("Összesen: "+duration(shared+bank),color=TV,
                    fontWeight=FontWeight.Bold)
                Text("Napi alap: tagonként +10 perc. Minden saját befejezett quest +5 percet ad a közös időhöz.",
                    color=TM,fontSize=12.sp)
                Text("A fel nem használt közös napi idő éjfélkor a csapatbankba kerül. A bank naponta 10%-kal növekszik, legfeljebb 24 óráig.",
                    color=TM,fontSize=12.sp)
                if(!host)Text("Nálad most előre lefoglalt közös idő: "+
                    duration(TeamStore.leased(ctx))+
                    ". Újabb használathoz közeli kapcsolat kell a gazdával.",
                    color=TY,fontSize=12.sp)
                if(TeamStore.pool(ctx)+TeamStore.bank(ctx)>0) {
                    Text("Ha elfogy a személyes időd, a csapatkeret lép életbe. A csoport minden tagja használhatja.",
                        color=TC,fontSize=12.sp)
                }
            }
            TeamCard("👤 CSAPATTAGOK  •  "+teamMembers.size) {
                teamMembers.forEach { m ->
                    Column(Modifier.fillMaxWidth().background(Color(0xFF20344A),
                        RoundedCornerShape(13.dp)).padding(12.dp),
                        verticalArrangement=Arrangement.spacedBy(4.dp)) {
                        Text((if(m.id==TeamStore.memberId(ctx))"★ " else "◉ ")+m.name,
                            color=TV,fontWeight=FontWeight.Bold)
                        if(m.share) {
                            Text("Maradék: "+duration(m.balance.coerceAtLeast(0))+
                                "  •  Felhasználva ma: "+duration(m.dailyUsed.coerceAtLeast(0)),
                                color=TC,fontSize=12.sp)
                        } else Text("A képernyőidő-adatok nem nyilvánosak.",
                            color=TM,fontSize=12.sp)
                        Text("Heti fekvő: "+m.pushups+"  •  Sakk: "+m.chess+
                            "  •  Tanulás: "+duration(m.study),
                            color=TM,fontSize=11.sp)
                        Text("Utolsó adat: "+m.updated,color=TM,fontSize=10.sp)
                    }
                }
                Row(verticalAlignment=Alignment.CenterVertically) {
                    Checkbox(checked=TeamStore.sharing(ctx),onCheckedChange={
                        TeamStore.setSharing(ctx,it);TeamNearby.sync(ctx);tick++
                    })
                    Text("Saját képernyőidő-statisztikám megosztása",
                        color=TV,fontSize=12.sp)
                }
            }
            TeamCard("👑 CSAPAT BOSS FIGHT") {
                val (push,chess,study)=TeamStore.weekBossProgress(ctx)
                val (pGoal,cGoal,sGoal)=TeamStore.bossGoal(ctx)
                Text("Az összes tag heti teljesítménye összeadódik. A cél a csoport létszámával nő.",
                    color=TM,fontSize=12.sp)
                listOf(
                    Triple("Fekvőtámasz",push,pGoal),
                    Triple("Sakkmeccs",chess,cGoal),
                    Triple("Tanulás",study,sGoal)
                ).forEach { (label,value,target) ->
                    Row {
                        Text(label,modifier=Modifier.weight(1f),color=TV,fontSize=12.sp)
                        Text(if(target==sGoal&&label=="Tanulás")
                            duration(value)+" / "+duration(target)
                            else "$value / $target",color=TC,fontSize=12.sp)
                    }
                    LinearProgressIndicator(progress={
                        (value.toFloat()/target.coerceAtLeast(1)).coerceIn(0f,1f)
                    },color=TC,trackColor=TBR,modifier=Modifier.fillMaxWidth().height(8.dp))
                }
                if(TeamStore.bossClaimed(ctx)) {
                    Text("🏆 E heti csapat Boss legyőzve!",color=TC,fontWeight=FontWeight.Bold)
                } else {
                    val earned=push>=pGoal&&chess>=cGoal&&study>=sGoal
                    Button(onClick={TeamNearby.requestBoss(ctx);tick++},
                        enabled=earned,modifier=Modifier.fillMaxWidth(),
                        colors=ButtonDefaults.buttonColors(containerColor=TC,
                            contentColor=Color(0xFF101F2D))) {
                        Text("BOSS JUTALOM  •  +"+duration(teamMembers.size*900)+
                            " A CSAPATNAK")
                    }
                }
                Text("Nincsenek külön csapatquestek, és fókuszszoba sincs.",
                    color=TM,fontSize=11.sp)
            }
            TeamCard("⚙️ CSAPAT KEZELÉSE") {
                Text("A csoport a készülékeken tárolódik, nincs felhő. A gazda telefonja vezeti a közös bankot.",
                    color=TM,fontSize=12.sp)
                if(showLeave) {
                    Text(if(host)"Ha törlöd a csoportot, a többiek többé nem tudnak ehhez a gazdához kapcsolódni."
                        else "A kilépés törli a helyi csapattagságodat.",
                        color=TY,fontSize=12.sp)
                    Button(onClick={
                        try{ctx.stopService(Intent(ctx,TeamSyncService::class.java))}
                        catch(_:Exception){}
                        TeamNearby.stop()
                        TeamStore.leave(ctx)
                        showLeave=false;tick++
                    },modifier=Modifier.fillMaxWidth(),
                        colors=ButtonDefaults.buttonColors(containerColor=Color(0xFFDC7C82))) {
                        Text(if(host)"CSAPAT TÖRLÉSE" else "KILÉPÉS")
                    }
                    OutlinedButton(onClick={showLeave=false},
                        modifier=Modifier.fillMaxWidth()) {Text("Mégsem")}
                } else OutlinedButton(onClick={showLeave=true},
                    modifier=Modifier.fillMaxWidth()) {
                    Text(if(host)"CSAPAT MEGSZÜNTETÉSE" else "KILÉPÉS A CSAPATBÓL",
                        color=TY)
                }
            }
        }
        if(warning.isNotBlank())Text(warning,color=TY,fontSize=12.sp)
    }
}
