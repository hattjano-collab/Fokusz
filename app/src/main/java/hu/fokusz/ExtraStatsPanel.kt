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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
    val bank=TimeBank.ready(c)
    val pending=TimeBank.waiting(c)
    val (reps,chess,study)=BossQuest.progress(c)
    val days=ProgressStore.recent(c)
    val used=days.sumOf { it.usage }
    val estimate=(7*Store.DAILY-used).coerceAtLeast(0)
    Column(verticalArrangement=Arrangement.spacedBy(15.dp)) {
        FeatureBox("🏦 IDŐBANK") {
            Text("Eltehetsz 5 vagy 15 percet egy későbbi napra. A ma betett idő legkorábban holnap vehető ki. Napi befizetés maximum 30 perc, teljes bank maximum 120 perc.",
                color=SUB,fontSize=12.sp)
            Row {
                Text("Elérhető: "+duration(bank),modifier=Modifier.weight(1f),color=GREEN,
                    fontWeight=FontWeight.Bold)
                Text("Holnaptól: "+duration(pending),color=FG)
            }
            Text("Ma még betehető: "+duration(TimeBank.remainingDaily(c)),color=SUB,fontSize=12.sp)
            for (v in listOf(300,900)) {
                Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick={if(TimeBank.deposit(c,v))refresh++},
                        enabled=Store.bal(c)>=v && TimeBank.remainingDaily(c)>=v
                            && bank+pending+v<=7200,modifier=Modifier.weight(1f)) {
                        Text("Betesz "+v/60+" p")
                    }
                    OutlinedButton(onClick={if(TimeBank.withdraw(c,v))refresh++},
                        enabled=bank>=v && Store.bal(c)+v<=FocusPrefs.maxBalance(c),
                        modifier=Modifier.weight(1f)) {
                        Text("Kivesz "+v/60+" p")
                    }
                }
            }
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
            Text("Sakkfiók: "+(if(ChessVerifier.isLinked(c))
                ChessVerifier.username(c) else "Nincs összekötve"),color=SUB)
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
