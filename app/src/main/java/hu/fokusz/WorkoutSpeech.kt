package hu.fokusz

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.ToneGenerator
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

/** A becsomagolt magyar számok telepített beszédmotor nélkül is működnek. */
internal class WorkoutSpeech(private val context: Context) {
    private val pending = ArrayDeque<Int>()
    private var player: MediaPlayer? = null
    private var tone: ToneGenerator? = null

    fun speak(count: Int) {
        if (count < 0) return
        if (count <= 100) pending.addLast(count) else count.toString().forEach { pending.addLast(it.digitToInt()) }
        playNext()
    }

    private fun playNext() {
        if (player != null || pending.isEmpty()) return
        val next = pending.removeFirst()
        val audio = MediaPlayer()
        player = audio
        fun finish() {
            if (player !== audio) return
            player = null
            audio.release()
            playNext()
        }
        try {
            audio.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            context.assets.openFd("workout_numbers/$next.ogg").use { asset ->
                audio.setDataSource(asset.fileDescriptor, asset.startOffset, asset.length)
            }
            audio.setOnCompletionListener { finish() }
            audio.setOnErrorListener { _, _, _ -> finish(); true }
            audio.setOnPreparedListener {
                if (player === audio && runCatching { audio.start() }.isFailure) finish()
            }
            audio.prepareAsync()
        } catch (_: Exception) {
            finish()
        }
    }

    fun cue(finished: Boolean = false) {
        runCatching {
            val beeper = tone ?: ToneGenerator(AudioManager.STREAM_MUSIC, 75).also { tone = it }
            beeper.startTone(if (finished) ToneGenerator.TONE_PROP_ACK else ToneGenerator.TONE_PROP_BEEP,
                if (finished) 350 else 130)
        }
    }

    fun stop() {
        pending.clear()
        player?.release()
        player = null
        tone?.stopTone()
    }

    fun close() {
        stop()
        tone?.release()
        tone = null
    }
}

@Composable
internal fun rememberWorkoutSpeech(): WorkoutSpeech {
    val context = LocalContext.current.applicationContext
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
        })
    }
}
