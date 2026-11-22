package hu.fokusz

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
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
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

@Composable
fun ChessAccountPanel() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val accent = Color(0xFF7BE7C1)
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("♟️ SAKKFIÓKOK", color = accent, fontWeight = FontWeight.Bold)
        Text("A Chess.com és a Lichess napi befejezett partijai összeadódnak. " +
            "A csatlakozáshoz elég a nyilvános felhasználónév.", color = Color(0xFFA7B4CC))

        for (platform in ChessPlatform.entries) {
            var linked by remember(platform) {
                mutableStateOf(ChessVerifier.accounts(ctx).find { it.platform == platform })
            }
            var username by remember(platform) { mutableStateOf(linked?.username.orEmpty()) }
            var checking by remember(platform) { mutableStateOf(false) }
            var info by remember(platform) { mutableStateOf("") }
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(platform.label, color = accent, fontWeight = FontWeight.Bold)
                if (linked != null) {
                    Text("✓ ${linked?.username}", color = Color.White)
                    OutlinedButton(
                        onClick = {
                            if (ChessVerifier.unlink(ctx, platform)) {
                                linked = null
                                info = "A ${platform.label}-fiók leválasztva."
                            } else {
                                info = "A leválasztás sikertelen. Próbáld újra."
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("${platform.label}-fiók leválasztása") }
                } else {
                    OutlinedTextField(
                        value = username,
                        onValueChange = { if (it.length <= 25) username = it },
                        label = { Text("Nyilvános felhasználónév") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Button(
                        onClick = {
                            checking = true
                            scope.launch {
                                try {
                                    val result = ChessVerifier.link(ctx, platform, username)
                                    info = result.message
                                    if (result.success) {
                                        linked = ChessVerifier.accounts(ctx).find { it.platform == platform }
                                    }
                                } finally {
                                    checking = false
                                }
                            }
                        },
                        enabled = !checking && username.trim().length >= 2,
                        modifier = Modifier.fillMaxWidth()
                    ) { Text(if (checking) "ELLENŐRZÉS…" else "${platform.label}-fiók összekötése") }
                }
                if (info.isNotEmpty()) Text(info, color = Color(0xFFFFC78D))
            }
        }
    }
}
