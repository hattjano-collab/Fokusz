package hu.fokusz

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable fun GamblingSummaryPanel(refresh:Int) {
    val ctx=LocalContext.current
    val today=GambleStats.todayTotals(ctx)
    val history=GambleStats.history(ctx)
    val totalStaked=GambleStats.staked(ctx)
    Column(Modifier.fillMaxWidth().background(Color(0xFF182B3F),RoundedCornerShape(22.dp))
        .padding(17.dp),verticalArrangement=Arrangement.spacedBy(11.dp)) {
        Text("📊  MAI JÁTÉKSTATISZTIKA",fontWeight=FontWeight.ExtraBold,
            color=Color(0xFF7BE7C1),fontSize=17.sp)
        Row(horizontalArrangement=Arrangement.SpaceBetween,modifier=Modifier.fillMaxWidth()) {
            Text("✓ Győzelem: "+today.first,color=Color(0xFF7BE7C1),fontSize=12.sp)
            Text("○ Döntetlen: "+today.third,color=Color(0xFFA7B4CC),fontSize=12.sp)
            Text("× Vereség: "+today.second,color=Color(0xFFFFC78D),fontSize=12.sp)
        }
        Text("Mai összes megtett tét: "+duration(totalStaked),
            color=Color(0xFFF5F7FF),fontWeight=FontWeight.Bold)
        Text("Bármennyi virtuális idő feltehető a saját elérhető egyenlegből. Nincs napi limit. Valódi pénz nem használható.",
            color=Color(0xFFA7B4CC),fontSize=12.sp)
        Text("UTOLSÓ JÁTÉKOK",color=Color(0xFFA7B4CC),
            fontWeight=FontWeight.Bold,fontSize=11.sp)
        if(history.isEmpty())Text("Még nem játszottál.",color=Color(0xFFA7B4CC),fontSize=12.sp)
        history.take(7).forEach { item ->
            Row {
                Text(item.time.takeLast(5)+"  "+item.game,color=Color(0xFFF5F7FF),
                    modifier=Modifier.weight(1f),fontSize=12.sp)
                Text((if(item.delta>0)"+" else if(item.delta<0)"−" else "±")+
                    duration(kotlin.math.abs(item.delta)),
                    color=if(item.delta>0)Color(0xFF7BE7C1)
                        else if(item.delta<0)Color(0xFFFFC78D)
                        else Color(0xFFA7B4CC),fontSize=12.sp)
            }
        }
        Text("Kizárólag virtuális képernyőidő. A játék nem ad valódi pénzt, és nem befolyásolja a feladatok teljesítését.",
            color=Color(0xFFA7B4CC),fontSize=11.sp)
    }
}
