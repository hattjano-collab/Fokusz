package hu.fokusz

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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

@Composable fun BaseQuestSettings() {
    val context=LocalContext.current
    val stored=BaseQuests.configured(context)
    var first by remember { mutableIntStateOf(stored.first) }
    var second by remember { mutableIntStateOf(stored.second) }
    var expanded by remember { mutableIntStateOf(-1) }
    var result by remember { mutableStateOf("") }
    val accent=Color(0xFF7BE7C1)
    val white=Color(0xFFF6F8FF)
    val soft=Color(0xFFA9B5CD)
    Column(verticalArrangement=Arrangement.spacedBy(12.dp)) {
        Text("⭐ VÁLASZD KI A 2 NAPI ALAPQUESTET",color=accent,
            fontSize=14.sp,fontWeight=FontWeight.ExtraBold)
        Text("20 féle feladatból választhatsz pontosan kettőt. Ezeket minden nap teljesítheted tetszőleges sorrendben, és csak utánuk nyílnak az extra questek.",
            color=soft,fontSize=12.sp)
        for(slot in 0..1) {
            val choiceId=if(slot==0)first else second
            val choice=BaseQuests.choice(choiceId)
            Column(Modifier.fillMaxWidth().background(Color(0xFF202E46),RoundedCornerShape(16.dp))
                .padding(12.dp),verticalArrangement=Arrangement.spacedBy(7.dp)) {
                Text(if(slot==0)"1. KÖTELEZŐ ALAPQUEST" else "2. KÖTELEZŐ ALAPQUEST",
                    color=accent,fontSize=11.sp,fontWeight=FontWeight.Bold)
                OutlinedButton(onClick={expanded=slot},modifier=Modifier.fillMaxWidth()) {
                    Text(choice.icon+" "+choice.name+"   ▾",color=white)
                }
                DropdownMenu(expanded=expanded==slot,onDismissRequest={expanded=-1}) {
                    BaseQuests.choices.forEach { item ->
                        DropdownMenuItem(
                            text={Text(item.icon+" "+item.name,
                                color=if(item.id==choiceId)accent else white)},
                            enabled=item.id!=if(slot==0)second else first,
                            onClick={
                                if(slot==0)first=item.id else second=item.id
                                expanded=-1
                                result=""
                            }
                        )
                    }
                }
                Text(choice.details,color=soft,fontSize=12.sp)
                Text("Jutalom: +"+choice.rewardMinutes+" perc  •  "+
                    when(choice.kind) {
                        QuestKind.CHESS -> "Sakkfiók szükséges"
                        QuestKind.PUSHUPS,QuestKind.EXERCISE -> "Kötelező kamera"
                        QuestKind.TIMED -> "Zárolt időzítő"
                    },color=accent,fontSize=11.sp)
            }
        }
        Button(onClick={
            val today=BaseQuests.save(context,first,second)
            result=if(today) "✓ Mentve! Már ma ezt a két alapquestet kapod."
                else "✓ Mentve! Mivel a napi feladatokat már elkezdted, holnaptól lép életbe."
        },modifier=Modifier.fillMaxWidth(),enabled=first!=second,
            colors=ButtonDefaults.buttonColors(containerColor=accent,
                contentColor=Color(0xFF101729)),
            shape=RoundedCornerShape(14.dp)) {
            Text("KÉT ALAPQUEST MENTÉSE",fontWeight=FontWeight.Bold)
        }
        if(result.isNotEmpty())Text(result,color=accent,fontSize=12.sp)
        Text("A sakkmeccs jutalma +5 perc az összes napi parti után, plusz az egyes játszmák győzelmi/veszteségi jutalma. A videós és időzített feladatokat az eddigi ellenőrzés védi.",
            color=soft,fontSize=11.sp)
    }
}
