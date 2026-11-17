package hu.fokusz

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import com.google.zxing.BarcodeFormat
import com.google.zxing.MultiFormatWriter
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val CP=Color(0xFF7BE7C1)
private val CW=Color(0xFFF2F6FF)
private val CM=Color(0xFFB2C0D3)
private val CG=Color(0xFFFFD18D)
private fun teamQr(code:String):android.graphics.Bitmap?=try{
    val pixels=MultiFormatWriter().encode(code,BarcodeFormat.QR_CODE,512,512)
    val image=IntArray(512*512)
    for(y in 0 until 512)for(x in 0 until 512)
        image[y*512+x]=if(pixels[x,y])android.graphics.Color.BLACK else android.graphics.Color.WHITE
    android.graphics.Bitmap.createBitmap(image,512,512,android.graphics.Bitmap.Config.ARGB_8888)
}catch(_:Exception){null}

@Composable private fun CloudBox(title:String,body:@Composable androidx.compose.foundation.layout.ColumnScope.()->Unit){
    Column(Modifier.fillMaxWidth().background(Color(0xFF1A2940),RoundedCornerShape(20.dp))
        .border(1.dp,Color(0xFF33445E),RoundedCornerShape(20.dp)).padding(15.dp),
        verticalArrangement=Arrangement.spacedBy(11.dp)){
        Text(title,color=CP,fontSize=15.sp,fontWeight=FontWeight.ExtraBold)
        body()
    }
}
@Composable fun CloudTeamPanel(){
    val ctx=LocalContext.current
    val scope=rememberCoroutineScope()
    var tick by remember {mutableIntStateOf(0)}
    var teamName by remember {mutableStateOf("Fókusz csapat")}
    var nickname by remember {mutableStateOf(CloudTeams.nickname(ctx).takeIf{it!="Tag"}?:"")}
    var share by remember {mutableStateOf(CloudTeams.sharing(ctx))}
    var paste by remember {mutableStateOf("")}
    var giftMinutes by remember {mutableStateOf("")}
    var message by remember {mutableStateOf("")}
    var busy by remember {mutableStateOf(false)}
    var confirm by remember {mutableStateOf(false)}
    val active=CloudTeams.active(ctx)
    val state=CloudTeams.view
    val status=CloudTeams.status
    LaunchedEffect(active){if(active)CloudTeams.start(ctx)}
    LaunchedEffect(Unit){
        while(true){if(CloudTeams.active(ctx))CloudTeams.pulse(ctx);tick++;delay(3000)}
    }
    Column(verticalArrangement=Arrangement.spacedBy(16.dp)){
        Text("👥 CSAPATOK",color=CW,fontSize=25.sp,fontWeight=FontWeight.Black)
        Text("QR-kódos csatlakozás távolról, mobilinternetről vagy bármely Wi-Fi-ről. Nem kell központi telefon.",
            color=CM,fontSize=12.sp)
        if(!active){
            CloudBox("✨ CSAPAT LÉTREHOZÁSA"){
                OutlinedTextField(value=teamName,onValueChange={teamName=it.take(40)},
                    modifier=Modifier.fillMaxWidth(),label={Text("Csapat neve")},singleLine=true)
                OutlinedTextField(value=nickname,onValueChange={nickname=it.take(24)},
                    modifier=Modifier.fillMaxWidth(),label={Text("Beceneved")},singleLine=true)
                Row(verticalAlignment=Alignment.CenterVertically){
                    Checkbox(checked=share,onCheckedChange={share=it})
                    Text("Megosztom a hátralévő időmet és a napi használatomat",
                        color=CM,fontSize=12.sp)
                }
                Button(onClick={busy=true;scope.launch{
                    message=CloudTeams.create(ctx,teamName,nickname,share)
                    busy=false;tick++
                }},enabled=!busy&&teamName.trim().length>=3&&nickname.trim().length>=2,
                    modifier=Modifier.fillMaxWidth(),
                    colors=ButtonDefaults.buttonColors(containerColor=CP)){
                    Text("CSAPAT LÉTREHOZÁSA")
                }
            }
            CloudBox("📷 CSATLAKOZÁS QR-KÓDDAL"){
                Text("Olvasd be a meghívó QR-kódját. A QR akár képernyőképen is megmutatható.",
                    color=CM,fontSize=12.sp)
                OutlinedTextField(value=nickname,onValueChange={nickname=it.take(24)},
                    modifier=Modifier.fillMaxWidth(),label={Text("Beceneved")},singleLine=true)
                Button(onClick={
                    try {
                        GmsBarcodeScanning.getClient(ctx).startScan()
                            .addOnSuccessListener{barcode->
                                busy=true
                                scope.launch{
                                    message=CloudTeams.join(ctx,barcode.rawValue?:"",nickname,share)
                                    busy=false;tick++
                                }
                            }
                            .addOnFailureListener{message="QR-olvasó: "+(it.localizedMessage?:"hiba")}
                    }catch(e:Exception){message="QR-olvasó: "+(e.localizedMessage?:"hiba")}
                },enabled=!busy&&nickname.trim().length>=2,
                    modifier=Modifier.fillMaxWidth()){Text("📷 QR-KÓD BEOLVASÁSA")}
                OutlinedTextField(value=paste,onValueChange={paste=it.take(1200)},
                    modifier=Modifier.fillMaxWidth(),maxLines=3,
                    label={Text("Meghívókód beillesztése (alternatíva)")})
                OutlinedButton(onClick={busy=true;scope.launch{
                    message=CloudTeams.join(ctx,paste,nickname,share)
                    busy=false;tick++
                }},enabled=!busy&&nickname.trim().length>=2&&paste.startsWith("fokusz-firebase:v1:"),
                    modifier=Modifier.fillMaxWidth()){Text("CSATLAKOZÁS MEGHÍVÓKÓDDAL")}
                Text("A Firebase anonim bejelentkezést használ. Nincs külön regisztráció.",
                    color=CM,fontSize=11.sp)
            }
        }else{
            CloudBox("🌐 "+CloudTeams.teamName(ctx)){
                Text(if(CloudTeams.owner(ctx))"CSAPATGAZDA"else"CSAPATTAG",
                    color=CG,fontWeight=FontWeight.Bold,fontSize=12.sp)
                Text(status,color=if(state.cached)CG else CP,fontSize=12.sp)
                OutlinedButton(onClick={CloudTeams.pulse(ctx,true)},
                    modifier=Modifier.fillMaxWidth()){Text("CSAPAT FRISSÍTÉSE")}
                if(CloudTeams.owner(ctx)){
                    val invite=CloudTeams.invite(ctx)
                    val qr=remember(invite){if(invite.isNotBlank())teamQr(invite)else null}
                    if(qr!=null)Box(Modifier.fillMaxWidth(),contentAlignment=Alignment.Center){
                        Image(qr.asImageBitmap(),"Csapat QR-kód",
                            modifier=Modifier.size(245.dp).background(Color.White,
                                RoundedCornerShape(12.dp)).padding(8.dp))
                    }
                    Text("A QR-kódot elküldheted képernyőképként bárhová.",
                        color=CM,fontSize=12.sp)
                    OutlinedButton(onClick={
                        val clipboard=ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(ClipData.newPlainText("Fókusz csapat",invite))
                        message="Meghívókód másolva."
                    },modifier=Modifier.fillMaxWidth()){Text("MEGHÍVÓKÓD MÁSOLÁSA")}
                    Text("A meghívókódot csak olyanoknak mutasd meg, akiket beengednél.",
                        color=CG,fontSize=11.sp)
                }
            }
            CloudBox("⏳ KÖZÖS KÉPERNYŐIDŐ"){
                Text(duration(state.pool),color=CP,fontSize=27.sp,fontWeight=FontWeight.Black)
                Text("Naponta +10 perc/tag; minden teljesített saját quest után +5 perc a csapatnak.",
                    color=CM,fontSize=12.sp)
                Text("Ha elfogy a személyes idő, automatikusan a közös keret fogy, majd a bank.",
                    color=CW,fontSize=12.sp)
                Text("Bankkal együtt: "+duration((state.pool.toLong()+state.bank).coerceAtMost(Store.MAX_SAFE_SECONDS.toLong()).toInt()),color=CG)
                Text("Már lefoglalt közös időd: "+duration(CloudTeams.leased(ctx)),
                    color=CM,fontSize=12.sp)
            }
            CloudBox("🏦 CSAPATBANK · NAPI +10%"){
                Text(duration(state.bank),color=CP,fontSize=27.sp,fontWeight=FontWeight.Black)
                Text("A tegnapról megmaradt idő a bankba kerül. A bank minden új napon 10%-kal nő. Nincs 24 órás vagy napi banki korlát.",
                    color=CM,fontSize=12.sp)
            }
            CloudBox("🎁 EXTRA IDŐ A CSAPATNAK"){
                Text("A saját virtuális képernyőidődből bármennyit felajánlhatsz a csapatnak, vagy közvetlenül a bankjába teheted.",
                    color=CM,fontSize=12.sp)
                Text("Saját egyenleged: "+duration(Store.bal(ctx)),
                    color=CP,fontSize=14.sp,fontWeight=FontWeight.Bold)
                OutlinedTextField(value=giftMinutes,
                    onValueChange={v->if(v.length<=9&&v.all(Char::isDigit))giftMinutes=v},
                    modifier=Modifier.fillMaxWidth(),singleLine=true,
                    label={Text("Felajánlott idő (perc)")})
                val gift=giftMinutes.toLongOrNull()?.let{minutes->
                    (minutes*60L).takeIf{value->
                        value in 1L..Store.MAX_SAFE_SECONDS.toLong()
                    }?.toInt()
                }?:0
                val waiting=CloudTeams.pendingContribution(ctx)
                if(waiting)Text("⏳ Internetes jóváírás folyamatban. A befizetés nem vész el, újraküldjük.",
                    color=CG,fontSize=12.sp)
                Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){
                    Button(onClick={busy=true;scope.launch{
                        message=CloudTeams.contribute(ctx,gift,false)
                        busy=false;tick++
                    }},enabled=gift>0&&gift<=Store.bal(ctx)&&!waiting&&!busy,
                        modifier=Modifier.weight(1f)){
                        Text("KÖZÖS KERETBE",fontSize=11.sp)
                    }
                    OutlinedButton(onClick={busy=true;scope.launch{
                        message=CloudTeams.contribute(ctx,gift,true)
                        busy=false;tick++
                    }},enabled=gift>0&&gift<=Store.bal(ctx)&&!waiting&&!busy,
                        modifier=Modifier.weight(1f)){
                        Text("CSAPATBANKBA",fontSize=11.sp)
                    }
                }
                OutlinedButton(onClick={busy=true;scope.launch{
                    message=CloudTeams.movePoolToBank(ctx,gift)
                    busy=false;tick++
                }},enabled=gift>0&&gift<=state.pool&&!busy,
                    modifier=Modifier.fillMaxWidth()){
                    Text("KÖZÖS KERETBŐL → CSAPATBANKBA",fontSize=11.sp)
                }
                Text("Csak virtuális időt mozgatunk. A személyes egyenleg a telefonon tárolódik, ezért a felajánlás az alkalmazás használói közötti bizalmon alapul.",
                    color=CM,fontSize=11.sp)
            }
            CloudBox("👑 CSAPAT BOSS FIGHT"){
                val n=state.members.size.coerceAtLeast(1)
                Text("Minden tag eredménye összeadódik. Cél: 100 fekvő, 10 sakkparti és 3 óra tanulás tagonként.",
                    color=CM,fontSize=12.sp)
                listOf(Triple("Fekvőtámasz",state.push to state.targetPush,100*n),
                    Triple("Sakkparti",state.chess to state.targetChess,10*n),
                    Triple("Tanulás",state.study to state.targetStudy,10800*n))
                    .forEach{item->
                        val remainingGoal=item.third
                        val done=(item.second.first-(item.second.second-remainingGoal)).coerceAtLeast(0)
                        Row {
                            Text(item.first,color=CW,modifier=Modifier.weight(1f))
                            Text(if(item.first=="Tanulás")duration(done)+" / "+duration(remainingGoal)
                                else "$done / $remainingGoal",color=CP,fontSize=12.sp)
                        }
                        LinearProgressIndicator(progress={
                            (done.toFloat()/remainingGoal.coerceAtLeast(1)).coerceIn(0f,1f)
                        },modifier=Modifier.fillMaxWidth().height(8.dp),color=CP,
                            trackColor=Color(0xFF40526B))
                    }
                val ready=state.push>=state.targetPush&&state.chess>=state.targetChess&&
                    state.study>=state.targetStudy
                val cooldown=state.bossClaimed!=null &&
                    System.currentTimeMillis()-state.bossClaimed.time<7L*86400000L
                Button(onClick={busy=true;scope.launch{
                    message=CloudTeams.claimBoss(ctx);busy=false;tick++
                }},enabled=ready&&!cooldown&&!busy,
                    modifier=Modifier.fillMaxWidth()){
                    Text(if(cooldown)"ÚJ BOSS 7 NAP MÚLVA"
                         else "BOSS JUTALOM: +"+duration(n*900))
                }
                Text("Nincsenek külön csapatquestek és fókuszszoba.",
                    color=CM,fontSize=11.sp)
            }
            CloudBox("👤 CSAPATTAGOK · "+state.members.size){
                for(member in state.members){
                    Column(Modifier.fillMaxWidth().background(Color(0xFF24364D),
                        RoundedCornerShape(12.dp)).padding(10.dp),
                        verticalArrangement=Arrangement.spacedBy(3.dp)){
                        Row(verticalAlignment=Alignment.CenterVertically){
                            Text(member.name+(if(member.uid==CloudTeams.currentUid())" (én)"else""),
                                color=CW,fontWeight=FontWeight.Bold,modifier=Modifier.weight(1f))
                            if(CloudTeams.owner(ctx)&&member.uid!=CloudTeams.currentUid())
                                OutlinedButton(onClick={busy=true;scope.launch{
                                    message=CloudTeams.removeMember(ctx,member.uid)
                                    busy=false;tick++
                                }},enabled=!busy){Text("×",color=CG)}
                        }
                        Text(if(member.share)
                            "Maradék: "+duration(member.personalLeft.coerceAtLeast(0))+
                                " • Mai használat: "+duration(member.used.coerceAtLeast(0))
                            else "Időadatok: nem megosztva",color=CM,fontSize=11.sp)
                        Text("Fekvő: "+member.push+"  Sakk: "+member.chess+
                            "  Tanulás: "+duration(member.study),color=CM,fontSize=11.sp)
                    }
                }
                Row(verticalAlignment=Alignment.CenterVertically){
                    Checkbox(checked=CloudTeams.sharing(ctx),onCheckedChange={
                        CloudTeams.setSharing(ctx,it);tick++
                    })
                    Text("Megosztom a saját képernyőidőmet",color=CW,fontSize=12.sp)
                }
            }
            CloudBox("⚙️ CSAPATKEZELÉS"){
                Text("Csak csapattagok olvashatják az adatokat. A naptárad és az alkalmazáslistád nem látható.",
                    color=CM,fontSize=12.sp)
                if(confirm) {
                    Text("Biztosan kilépsz? Ez a művelet a csapatgazdánál az egész csapatot törli.",
                        color=CG,fontSize=12.sp)
                    Button(onClick={busy=true;scope.launch{
                        message=CloudTeams.leave(ctx)
                        busy=false;confirm=false;tick++
                    }},enabled=!busy,modifier=Modifier.fillMaxWidth()){
                        Text("IGEN, "+if(CloudTeams.owner(ctx))"CSAPAT TÖRLÉSE"else"KILÉPÉS")
                    }
                    OutlinedButton(onClick={confirm=false},
                        modifier=Modifier.fillMaxWidth()){Text("MÉGSEM")}
                }else OutlinedButton(onClick={confirm=true},
                    modifier=Modifier.fillMaxWidth()){
                    Text(if(CloudTeams.owner(ctx))"CSAPAT MEGSZÜNTETÉSE"else"KILÉPÉS")
                }
            }
        }
        if(message.isNotBlank())Text(message,color=CG,fontSize=12.sp)
        Text("Spark ingyenes csomag • A közös időt Firestore-tranzakciók számolják • A questadatokat a telefon jelenti.",
            color=CM,fontSize=10.sp)
    }
}
