package hu.fokusz

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import kotlinx.coroutines.launch

@Composable fun ChessAccountPanel() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var linked by remember { mutableStateOf(ChessVerifier.isLinked(ctx)) }
    var provider by remember { mutableStateOf(ChessVerifier.platform(ctx)) }
    var username by remember { mutableStateOf(ChessVerifier.username(ctx)) }
    var checking by remember { mutableStateOf(false) }
    var info by remember { mutableStateOf("") }
    Column(verticalArrangement = Arrangement.spacedBy(androidx.compose.ui.unit.Dp(10f))) {
        Text("♟️ SAKKFIÓK EGYSZERI ÖSSZEKÖTÉSE",
            color = Color(0xFF7BE7C1),fontWeight=FontWeight.Bold)
        Text("A napi 1–5 partit a megadott fiók nyilvános játszmáiból ellenőrizzük. Nem szükséges újra beírni. Ez felhasználónév-alapú kapcsolat, nem OAuth-bejelentkezés.",
            color=Color(0xFFA7B4CC))
        if (linked) {
            Text("✓ Mentett sakkfiók: " + if (provider=="lichess") "Lichess" else "Chess.com",
                color=Color(0xFF7BE7C1),fontWeight=FontWeight.Bold)
            Text(username,color=Color.White)
            OutlinedButton(onClick={ChessVerifier.unlink(ctx);linked=false;info="Sakkfiók leválasztva."},
                modifier=Modifier.fillMaxWidth()) {Text("Másik fiók beállítása")}
        } else {
            Row(horizontalArrangement=Arrangement.spacedBy(androidx.compose.ui.unit.Dp(8f))) {
                listOf("lichess" to "Lichess","chesscom" to "Chess.com").forEach { (value,label) ->
                    OutlinedButton(onClick={provider=value},modifier=Modifier.weight(1f),
                        colors=ButtonDefaults.outlinedButtonColors(
                            contentColor=if(provider==value)Color(0xFF7BE7C1) else Color.White)) {
                        Text(label)
                    }
                }
            }
            OutlinedTextField(value=username,onValueChange={if(it.length<=25)username=it},
                label={Text("Nyilvános felhasználónév")},singleLine=true,modifier=Modifier.fillMaxWidth())
            Button(onClick={
                checking=true
                scope.launch {
                    val r=ChessVerifier.link(ctx,provider,username)
                    info=r.message;linked=r.success;checking=false
                }
            }, enabled=!checking && username.trim().length>=2, modifier=Modifier.fillMaxWidth()) {
                Text(if(checking)"ELLENŐRZÉS..." else "FIÓK ÖSSZEKÖTÉSE")
            }
        }
        if(info.isNotEmpty()) Text(info,color=Color(0xFFFFC78D))
    }
}
