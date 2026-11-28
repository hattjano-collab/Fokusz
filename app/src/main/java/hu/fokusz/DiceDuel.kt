package hu.fokusz

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import java.security.SecureRandom

/** Tiszta, 36 lehetséges dobáskombinációjú virtuális kockapárbaj.
 * A nagyobb kocka nyer, azonos kocka döntetlen.
 */
@Composable fun DiceDuel(stake:Int,enabled:Boolean,onBalanceChange:()->Unit) {
    val context=LocalContext.current
    val random=remember {SecureRandom()}
    val look = FokuszAppearance.look
    var player by rememberSaveable {mutableIntStateOf(1)}
    var dealer by rememberSaveable {mutableIntStateOf(1)}
    var rolling by rememberSaveable {mutableStateOf(false)}
    var finalPlayer by rememberSaveable {mutableIntStateOf(1)}
    var finalDealer by rememberSaveable {mutableIntStateOf(1)}
    var round by rememberSaveable {mutableIntStateOf(0)}
    var result by rememberSaveable {mutableStateOf("Mindkét fél egy fair, hatoldalú kockával dob.")}
    val rotate=remember {Animatable(0f)}
    LaunchedEffect(round) {
        if(round<=0 || !rolling)return@LaunchedEffect
        rolling=true
        rotate.snapTo(0f)
        for(i in 1..8) {
            player=random.nextInt(6)+1
            dealer=random.nextInt(6)+1
            delay(90)
        }
        rotate.animateTo(720f,tween(800,easing=LinearEasing))
        player=finalPlayer
        dealer=finalDealer
        rolling=false
        val net = result.substringAfter("::", "0").toIntOrNull()
        if(net!=null)result = when {
            net>0 -> "Nyerő dobás! +"+duration(net)
            net<0 -> "Most nem sikerült. −"+duration(-net)
            else -> "Döntetlen: a tét visszajár."
        }
    }
    FokuszCard {
        Text("🎲  KOCKAPÁRBAJ",color=look.link,fontSize=19.sp,
            fontWeight=FontWeight.ExtraBold)
        Text("A te kockád az osztó ellen. Nagyobb szám: 2× kifizetés; ugyanannyi: tét vissza; kisebb: tét elvész.",
            color=look.muted,fontSize=13.sp)
        Row(horizontalArrangement=Arrangement.SpaceEvenly,modifier=Modifier.fillMaxWidth()) {
            for((label,number) in listOf("TE" to player,"OSZTÓ" to dealer)) {
                Column(horizontalAlignment=Alignment.CenterHorizontally,
                    verticalArrangement=Arrangement.spacedBy(7.dp)) {
                    Text(label,color=look.muted,fontSize=12.sp,fontWeight=FontWeight.Bold)
                    Box(Modifier.size(94.dp)
                        .graphicsLayer{rotationZ=rotate.value%360f}
                        .clip(look.shape)
                        .background(look.background)
                        .border(look.border.dp, look.outline, look.shape),
                        contentAlignment=Alignment.Center) {
                        Text(listOf("⚀","⚁","⚂","⚃","⚄","⚅")[number-1],
                            color=look.foreground,fontSize=66.sp)
                    }
                }
            }
        }
        Text(if(rolling)"A KOCKÁK GURULNAK…"else result,
            color=look.foreground,fontSize=15.sp,fontWeight=FontWeight.Bold,
            modifier=Modifier.fillMaxWidth(),textAlign=TextAlign.Center)
        FokuszAction("Kockák dobása · " + clock(stake), Modifier.fillMaxWidth(), enabled && !rolling) {
            if (rolling || !GambleStats.reserve(context, stake, "Kockapárbaj tétje")) return@FokuszAction
            rolling = true
            val x=random.nextInt(6)+1
            val y=random.nextInt(6)+1
            finalPlayer=x
            finalDealer=y
            val net=when {
                x>y -> stake
                x<y -> -stake
                else -> 0
            }
            if (x > y) Store.addUncapped(context, stake.toLong() * 2L, "Kockapárbaj kifizetése")
            else if (x == y) Store.addUncapped(context, stake.toLong(), "Kockapárbaj tétjének visszaadása")
            // A tényleges eredményt a dobás végén mutatjuk meg.
            result="::$net"
            GambleStats.record(context,"Kockapárbaj",net)
            onBalanceChange()
            round++
        }
        Text("Valószínűségek: nyerés 15/36 (41,7%), döntetlen 6/36 (16,7%), vereség 15/36 (41,7%). Nincs valódi pénz.",
            color=look.muted,fontSize=11.sp)
    }
}
