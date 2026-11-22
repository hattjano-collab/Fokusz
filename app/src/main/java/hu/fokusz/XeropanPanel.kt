package hu.fokusz

import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import java.time.LocalDate
import java.time.ZoneId

/** Xeropan hiteles publikus API híján az Android által mért előtér-használatra épül.
 * Az 5 perc *alkalmazáshasználatot* igazolja, nem a leckék befejezését.
 */
object XeropanStore {
    const val PACKAGE="com.application.xeropan"
    private fun p(c:Context)=c.getSharedPreferences("fokusz_xeropan_v1",0)
    fun claimed(c:Context)=p(c).getString("claimed","")==LocalDate.now().toString()
    fun usedSeconds(c:Context):Int {
        if(!UsageGuard.allowed(c))return 0
        return try {
            val from=LocalDate.now().atStartOfDay(ZoneId.systemDefault())
                .toInstant().toEpochMilli()
            val service=c.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
            (service.queryAndAggregateUsageStats(from,System.currentTimeMillis())
                [PACKAGE]?.totalTimeInForeground?:0L).div(1000L)
                .coerceIn(0L,86400L).toInt()
        }catch(_:Exception){0}
    }
    @Synchronized fun claim(c:Context):String {
        if(claimed(c))return "A mai Xeropan-jutalmat már megkaptad."
        if(!UsageGuard.allowed(c))return "Engedélyezd a használati statisztikák hozzáférését!"
        val used=usedSeconds(c)
        if(used<300)return "Még "+duration(300-used)+" Xeropan-használat szükséges."
        p(c).edit().putString("claimed",LocalDate.now().toString()).commit()
        val received = Store.addStudyReward(c, 300, "Xeropan-jutalom")
        ProgressStore.earned(c,received)
        // Egy személyes nyelvtanulási quest: a csapatnak is +5 perc.
        ProgressStore.taskFinished(c,"LANGUAGE",300,0)
        return "✓ Xeropan teljesítve! +"+duration(received)+
            " személyes jutalom, +5 perc a csapatnak (ha van csapat)."
    }
    fun open(c:Context) {
        try {
            val start=c.packageManager.getLaunchIntentForPackage(PACKAGE)
            c.startActivity(start?:Intent(Intent.ACTION_VIEW,
                Uri.parse("https://play.google.com/store/apps/details?id="+PACKAGE)))
        }catch(_:Exception){}
    }
}
@Composable fun XeropanPanel() {
    val ctx=LocalContext.current
    var elapsed by remember{mutableIntStateOf(XeropanStore.usedSeconds(ctx))}
    var result by remember{mutableStateOf("")}
    var tick by remember{mutableIntStateOf(0)}
    LaunchedEffect(Unit) {
        while(true){elapsed=XeropanStore.usedSeconds(ctx);tick++;delay(5000)}
    }
    Column(verticalArrangement=Arrangement.spacedBy(16.dp)) {
        Text("📚 XEROPAN",color=Color(0xFF7BE7C1),
            fontSize=25.sp,fontWeight=FontWeight.Black)
        Column(Modifier.fillMaxWidth().background(Color(0xFF1A2940),
            RoundedCornerShape(18.dp)).padding(16.dp),
            verticalArrangement=Arrangement.spacedBy(12.dp)) {
            Text("Nyelvtanulásból képernyőidő",color=Color.White,
                fontWeight=FontWeight.Bold,fontSize=18.sp)
            Text("Napi legalább 5 perc Xeropan-használat után kapsz 5 perc személyes képernyőidőt. Ha csapatban vagy, a saját nyelvtanulási quested a csapatnak is +5 percet ad.",
                color=Color(0xFFB2C0D3),fontSize=13.sp)
            Text("Ma mért használat: "+duration(elapsed),color=Color(0xFF7BE7C1),
                fontWeight=FontWeight.Bold,fontSize=18.sp)
            Text(if(XeropanStore.claimed(ctx))"✓ A mai jutalom már jóváírva."
                else "A jutalom még nincs felvéve.",
                color=Color(0xFFFFD18D),fontSize=13.sp)
            OutlinedButton(onClick={XeropanStore.open(ctx)},
                modifier=Modifier.fillMaxWidth()){Text("XEROPAN MEGNYITÁSA")}
            Button(onClick={
                result=XeropanStore.claim(ctx)
                elapsed=XeropanStore.usedSeconds(ctx);tick++
            },enabled=!XeropanStore.claimed(ctx),
                modifier=Modifier.fillMaxWidth()){Text("MAI +5 PERC JUTALOM FELVÉTELE")}
            if(result.isNotBlank())Text(result,color=Color(0xFFFFD18D),fontSize=12.sp)
        }
        Text("Az ellenőrzés az Android előtér-használati idejét méri, nem a Xeropan feladatteljesítési adatait. Szükséges a Fókusz használati hozzáférése.",
            color=Color(0xFFB2C0D3),fontSize=12.sp)
        Text("A Duolingo külön továbbra is használható a Duolingo fülön.",
            color=Color(0xFFB2C0D3),fontSize=12.sp)
    }
}
