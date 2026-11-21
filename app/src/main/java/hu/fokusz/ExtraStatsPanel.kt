package hu.fokusz

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.delay
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.LocalDate

private val GREEN=Color(0xFF7BE7C1)
private val FG=Color(0xFFF5F7FF)
private val SUB=Color(0xFFA7B4CC)

@Composable fun ExtraStatsPanel() {
    val c=LocalContext.current
    var refresh by remember { mutableIntStateOf(0) }
    var bankMinutes by remember {mutableStateOf("")}
    var bankNotice by remember {mutableStateOf("")}
    LaunchedEffect(Unit) { while (true) { delay(4000); refresh++ } }
    val bank=remember(refresh) { TimeBank.ready(c) }
    val pending=remember(refresh) { TimeBank.waiting(c) }
    val tomorrow=remember(refresh) { TimeBank.projectedTomorrow(c) }
    val tomorrowBonus=remember(refresh) { TimeBank.projectedInterest(c) }
    val todayBonus=remember(refresh) { TimeBank.earnedToday(c) }
    val (reps,chess,study)=BossQuest.progress(c)
    val days=ProgressStore.recent(c)
    val used=days.sumOf { it.usage }
    val estimate=(7*Store.DAILY-used).coerceAtLeast(0)
    Column(verticalArrangement=Arrangement.spacedBy(15.dp)) {
        FeatureBox("🏦 IDŐBANK  •  NAPI +10%") {
            Text("A bankban tárolt virtuális idő minden nap 10%-kal nő. Nincs 120 perces vagy napi befizetési korlát. A ma betett időt holnaptól használhatod.",
                color=SUB,fontSize=12.sp)
            Column(Modifier.fillMaxWidth().animateContentSize()
                .background(Color(0xFF1B3B42),RoundedCornerShape(16.dp))
                .padding(15.dp),verticalArrangement=Arrangement.spacedBy(5.dp)) {
                Text("HOLNAPI VÁRHATÓ EGYENLEG",color=SUB,fontSize=11.sp,fontWeight=FontWeight.Bold)
                Text(duration(tomorrow),color=GREEN,fontSize=29.sp,fontWeight=FontWeight.Black)
                Text("Holnapi jóváírás: +" + duration(tomorrowBonus),
                    color=Color(0xFFFFC78D),fontSize=13.sp)
            }
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                Column(Modifier.weight(1f).background(Color(0xFF233148),RoundedCornerShape(14.dp))
                    .padding(12.dp)) {
                    Text("MOST ELÉRHETŐ",color=SUB,fontSize=10.sp)
                    Text(duration(bank),color=FG,fontSize=17.sp,fontWeight=FontWeight.Bold)
                }
                Column(Modifier.weight(1f).background(Color(0xFF233148),RoundedCornerShape(14.dp))
                    .padding(12.dp)) {
                    Text("MA FÉLRETÉVE",color=SUB,fontSize=10.sp)
                    Text(duration(pending),color=FG,fontSize=17.sp,fontWeight=FontWeight.Bold)
                }
            }
            if(todayBonus>0) Text("✓ A mai napi kamat: +" + duration(todayBonus),
                color=GREEN,fontSize=13.sp)
            Text("Bármennyi saját virtuális percet félretehetsz, amennyi az egyenlegeden van.",
                color=SUB,fontSize=12.sp)
            OutlinedTextField(
                value=bankMinutes,
                onValueChange={v->if(v.length<=9&&v.all(Char::isDigit))bankMinutes=v},
                label={Text("Összeg percben (nincs napi korlát)")},
                modifier=Modifier.fillMaxWidth(),singleLine=true)
            val customAmount=bankMinutes.toLongOrNull()?.let {
                (it*60L).takeIf{value->value in 1..Store.MAX_SAFE_SECONDS.toLong()}
                    ?.toInt()
            }?:0
            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){
                Button(onClick={
                    bankNotice=if(TimeBank.deposit(c,customAmount))
                        "✓ Befizetve. Holnap kivehető." else "Nincs elég saját idő."
                    refresh++
                },enabled=customAmount>0&&customAmount<=Store.bal(c),
                    modifier=Modifier.weight(1f)){Text("BETESZEM")}
                OutlinedButton(onClick={
                    bankNotice=if(TimeBank.withdraw(c,customAmount))
                        "✓ Kivéve a személyes egyenlegedre."else"Nincs ennyi elérhető banki idő."
                    refresh++
                },enabled=customAmount>0&&customAmount<=bank,
                    modifier=Modifier.weight(1f)){Text("KIVESZEM")}
            }
            OutlinedButton(onClick={
                val all=Store.bal(c)
                bankNotice=if(TimeBank.deposit(c,all))"✓ Teljes egyenleg félretéve."
                    else"Nem sikerült a befizetés."
                refresh++
            },enabled=Store.bal(c)>0,modifier=Modifier.fillMaxWidth()){
                Text("TELJES SAJÁT EGYENLEG BETÉTELE")
            }
            if(bankNotice.isNotBlank())Text(bankNotice,color=GREEN,fontSize=12.sp)
            Text("Az összeg a befizetés után rögtön frissül. Az éjfél utáni napi növekedést az app megnyitásakor és nyitott képernyőn is elszámolja. Kerekítés egész másodpercre.",
                color=SUB,fontSize=11.sp)
        }
        FeatureBox("👑 HETI BOSS QUEST") {
            Text("Teljesítsd mindhárom célt egy naptári héten. Jutalom: +15 perc és Boss-jelvény.",
                color=SUB,fontSize=12.sp)
            val goals=listOf(
                Triple("Fekvőtámasz",reps,100),
                Triple("Befejezett sakkmeccs",chess,10),
                Triple("Tanulás másodpercben",study,10800)
            )
            for((label,value,target) in goals) {
                Row {
                    Text(label,modifier=Modifier.weight(1f),color=FG,fontSize=12.sp)
                    Text(if(target>=3600) duration(value)+"/3 óra" else
                        value.toString()+"/"+target,color=GREEN,fontSize=12.sp)
                }
                LinearProgressIndicator(progress={(value.toFloat()/target).coerceIn(0f,1f)},
                    modifier=Modifier.fillMaxWidth().height(8.dp),
                    color=GREEN,trackColor=Color(0xFF32415C))
            }
            if(BossQuest.claimed(c)) Text("🏅 Heti Boss legyőzve!",color=GREEN,
                fontWeight=FontWeight.Bold)
            else Button(onClick={if(BossQuest.claim(c))refresh++},
                enabled=BossQuest.ready(c),modifier=Modifier.fillMaxWidth(),
                colors=ButtonDefaults.buttonColors(containerColor=GREEN,
                    contentColor=Color(0xFF0B1020))) {Text("BOSS JUTALOM FELVÉTELE")}
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
