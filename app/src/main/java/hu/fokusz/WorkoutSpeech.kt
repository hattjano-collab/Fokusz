package hu.fokusz

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import java.util.Locale

internal class WorkoutSpeech(context: Context) {
    private val handler = Handler(Looper.getMainLooper())
    var available by mutableStateOf<Boolean?>(null)
        private set
    private var engine: TextToSpeech? = null

    init {
        engine = TextToSpeech(context.applicationContext) { status ->
            handler.post {
                val tts = engine ?: return@post
                available = status == TextToSpeech.SUCCESS &&
                    tts.setLanguage(Locale.forLanguageTag("hu-HU")) >= TextToSpeech.LANG_AVAILABLE
            }
        }
    }

    fun speak(count: Int) {
        if (available == true) {
            engine?.speak(count.toString(), TextToSpeech.QUEUE_FLUSH, null, "fokusz-rep-$count")
        }
    }

    fun stop() {
        engine?.stop()
    }

    fun close() {
        handler.removeCallbacksAndMessages(null)
        engine?.shutdown()
        engine = null
    }
}

@Composable
internal fun rememberWorkoutSpeech(): WorkoutSpeech {
    val context = LocalContext.current
    val speech = remember(context) { WorkoutSpeech(context) }
    DisposableEffect(speech) { onDispose { speech.close() } }
    return speech
}

@Composable
internal fun WorkoutSpeechToggle(speech: WorkoutSpeech, enabled: Boolean, onToggle: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("Hangos ismétlésszámláló", color = Color(0xFFF5F7FF))
        Switch(checked = enabled, onCheckedChange = { next ->
            if (!next) speech.stop()
            onToggle(next)
        }, enabled = speech.available != false)
    }
    if (speech.available == false) {
        Text("A készüléken nincs elérhető magyar felolvasóhang.", color = Color(0xFFA7B4CC))
    }
}
