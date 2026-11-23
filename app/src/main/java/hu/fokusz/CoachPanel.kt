package hu.fokusz

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable fun CoachPanel() {
    val ctx = LocalContext.current
    val modes = listOf("pushups" to "💪 Fekvőtámasz", "squats" to "🏋️ Guggolás",
        "lunges" to "👟 Kitörés", "plank" to "🧘 Plank")
    var mode by remember { mutableStateOf("pushups") }
    var count by remember(mode) { mutableIntStateOf(CoachRewards.count(ctx,mode)) }
    var rewardNote by remember(mode) { mutableStateOf("") }
    var running by remember(mode) { mutableStateOf(false) }
    var sessionCount by remember(mode, running) { mutableIntStateOf(0) }
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("AI-MOZGÁSEDZŐ", color = Color(0xFFF5F7FF), fontSize = 24.sp,
            fontWeight = FontWeight.Black)
        Text("Fekvőtámasznál elég az egyik kar. Engedd le magad 120°-ig, majd nyomd ki 145°-ig.",
            color = Color(0xFFA7B4CC), fontSize = 13.sp)
        modes.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { (key,label) ->
                    OutlinedButton(onClick = { mode = key },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = if (mode==key) Color(0xFF7BE7C1) else Color(0xFFA7B4CC))) {
                        Text(label, fontSize = 11.sp)
                    }
                }
            }
        }
        Text(if (mode == "plank") "Kitartott másodpercek: $count" else "Szabályosnak érzékelt ismétlések: $count",
            color = Color(0xFF7BE7C1), fontWeight = FontWeight.Bold, fontSize = 18.sp)
        val unit = if (mode == "plank") "minden kitartott másodpercért" else "minden elfogadott ismétlésért"
        Text("+${CoachRewards.secondsPerRep(ctx, mode)} mp $unit az egyenlegplafonig.",
            color=Color(0xFFFFC78D),fontSize=14.sp,fontWeight=FontWeight.Bold)
        if (rewardNote.isNotBlank()) Text(rewardNote,color=Color(0xFF7BE7C1),
            fontWeight=FontWeight.Bold)
        if (!running) {
            Button(onClick = {running=true}, modifier = Modifier.fillMaxWidth().height(50.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF7BE7C1),
                    contentColor = Color(0xFF090F1F)),
                shape = RoundedCornerShape(14.dp)) { Text("KAMERA INDÍTÁSA") }
        } else {
            OutlinedButton(onClick = {running=false}, modifier = Modifier.fillMaxWidth()) {
                Text("Kamera kikapcsolása")
            }
            Text(if (mode == "plank") "Ebben az edzésben: $sessionCount mp" else "Ebben az edzésben: $sessionCount ismétlés",
                color = Color(0xFFF5F7FF), fontSize = 14.sp)
            WorkoutCamera(key = "coach_$mode", session = "coach_$mode", mode = mode) {
                val result=CoachRewards.recognized(ctx,mode)
                count=result.count
                rewardNote = if (result.awarded > 0) "✦ +${result.awarded} mp képernyőidő!"
                    else "Elérted a képernyőidő-egyenleg plafonját."
                sessionCount += 1
                sessionCount
            }
        }
        Text("Az AI-edző külön is jutalmaz; a napi questek jutalma ettől független.",
            color = Color(0xFFA7B4CC), fontSize = 12.sp)
        Text("Egyszerű 2D testhelyzet-becslés: guggolásnál és kitörésnél a térdhajlítást, planknél az egyenes, vízszintes testhelyzetet figyeli.",
            color = Color(0xFFA7B4CC), fontSize = 12.sp)
    }
}
