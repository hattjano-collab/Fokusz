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
    var player by remember {mutableIntStateOf(1)}
    var dealer by remember {mutableIntStateOf(1)}
    var rolling by remember {mutableStateOf(false)}
    var round by remember {mutableIntStateOf(0)}
    var result by remember {mutableStateOf("Mindkét fél egy fair, hatoldalú kockával dob.")}
    val rotate=remember {Animatable(0f)}
    LaunchedEffect(round) {
        if(round<=0)return@LaunchedEffect
        rolling=true
        rotate.snapTo(0f)
        for(i in 1..8) {
            player=random.nextInt(6)+1
            dealer=random.nextInt(6)+1
            delay(90)
        }
        rotate.animateTo(720f,tween(800,easing=LinearEasing))
        rolling=false
        val net = result.substringAfter("::", "0").toIntOrNull()
        if(net!=null)result = when {
            net>0 -> "Nyerő dobás! +"+duration(net)
            net<0 -> "Most nem sikerült. −"+duration(-net)
            else -> "Döntetlen: a tét visszajár."
        }
    }
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(23.dp))
        .background(Color(0xFF22253B))
        .border(1.dp,Color(0xFF33435D),RoundedCornerShape(23.dp))
        .padding(18.dp),verticalArrangement=Arrangement.spacedBy(13.dp)) {
        Text("🎲  KOCKAPÁRBAJ",color=Color(0xFFB9AAFF),fontSize=19.sp,
            fontWeight=FontWeight.ExtraBold)
        Text("A te kockád az osztó ellen. Nagyobb szám: 2× kifizetés; ugyanannyi: tét vissza; kisebb: tét elvész.",
            color=Color(0xFFA7B4CC),fontSize=13.sp)
        Row(horizontalArrangement=Arrangement.SpaceEvenly,modifier=Modifier.fillMaxWidth()) {
            for((label,number) in listOf("TE" to player,"OSZTÓ" to dealer)) {
                Column(horizontalAlignment=Alignment.CenterHorizontally,
                    verticalArrangement=Arrangement.spacedBy(7.dp)) {
                    Text(label,color=Color(0xFFA7B4CC),fontSize=12.sp,fontWeight=FontWeight.Bold)
                    Box(Modifier.size(94.dp)
                        .graphicsLayer{rotationZ=rotate.value%360f}
                        .clip(RoundedCornerShape(19.dp))
                        .background(Color(0xFFF5F7FF)),
                        contentAlignment=Alignment.Center) {
                        Text(listOf("⚀","⚁","⚂","⚃","⚄","⚅")[number-1],
                            color=Color(0xFF152339),fontSize=66.sp)
                    }
                }
            }
        }
        Text(if(rolling)"A KOCKÁK GURULNAK…"else result,
            color=Color(0xFFF5F7FF),fontSize=15.sp,fontWeight=FontWeight.Bold,
            modifier=Modifier.fillMaxWidth(),textAlign=TextAlign.Center)
        Button(onClick={
            if(rolling||!GambleStats.reserve(context,stake))return@Button
            Store.add(context,-stake)
            val x=random.nextInt(6)+1
            val y=random.nextInt(6)+1
            val net=when {
                x>y -> stake
                x<y -> -stake
                else -> 0
            }
            if(x>y) Store.add(context,stake*2)
            else if(x==y)Store.add(context,stake)
            // A tényleges eredményt a dobás végén mutatjuk meg.
            result="::$net"
            GambleStats.record(context,"Kockapárbaj",net)
            onBalanceChange()
            round++
        },enabled=enabled&&!rolling,modifier=Modifier.fillMaxWidth().height(50.dp),
            shape=RoundedCornerShape(15.dp),
            colors=ButtonDefaults.buttonColors(containerColor=Color(0xFFB9AAFF),
                contentColor=Color(0xFF11172B))) {
            Text("🎲  KOCKÁK DOBÁSA • "+clock(stake),fontWeight=FontWeight.Bold)
        }
        Text("Valószínűségek: nyerés 15/36 (41,7%), döntetlen 6/36 (16,7%), vereség 15/36 (41,7%). Nincs valódi pénz.",
            color=Color(0xFFA7B4CC),fontSize=11.sp)
    }
}
