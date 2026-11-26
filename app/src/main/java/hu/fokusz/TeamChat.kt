package hu.fokusz

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.drawable.AnimatedImageDrawable
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.util.Base64
import android.widget.ImageView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.MetadataChanges
import com.google.firebase.firestore.Query
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.util.UUID

internal data class ChatAttachment(val mime: String, val bytes: ByteArray) {
    val kind get() = when (mime) {
        "image/gif" -> "GIF"
        "audio/mp4" -> "Hangüzenet"
        else -> "Kép"
    }

    companion object {
        const val MAX_BYTES = 150_000

        fun fromUri(c: Context, uri: Uri): ChatAttachment? {
            val resolver = c.contentResolver
            val mime = resolver.getType(uri) ?: return null
            if (mime == "image/gif") {
                val data = resolver.openInputStream(uri)?.use { input ->
                    val bytes = ByteArray(MAX_BYTES + 1)
                    var read = 0
                    while (read < bytes.size) {
                        val count = input.read(bytes, read, bytes.size - read)
                        if (count <= 0) break
                        read += count
                    }
                    bytes.copyOf(read)
                } ?: return null
                return data.takeIf { it.size in 1..MAX_BYTES }?.let { ChatAttachment("image/gif", it) }
            }
            if (!mime.startsWith("image/")) return null
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 950) sample *= 2
            val options = BitmapFactory.Options().apply { inSampleSize = sample }
            val bitmap = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) } ?: return null
            return try {
                for (quality in listOf(82, 65, 45, 30, 18)) {
                    val output = ByteArrayOutputStream()
                    if (bitmap.compress(Bitmap.CompressFormat.JPEG, quality, output) && output.size() <= MAX_BYTES) {
                        return ChatAttachment("image/jpeg", output.toByteArray())
                    }
                }
                null
            } finally { bitmap.recycle() }
        }
    }
}

internal data class TeamChatItem(val id: String, val uid: String, val text: String, val mime: String,
                                 val encoded: String, val prelude: Boolean) {
    fun attachment(): ChatAttachment? = runCatching {
        if (mime !in setOf("image/jpeg", "image/gif", "audio/mp4") || encoded.length > 200_000) return null
        val data = Base64.decode(encoded, Base64.NO_WRAP)
        data.takeIf { it.size in 1..ChatAttachment.MAX_BYTES }?.let { ChatAttachment(mime, it) }
    }.getOrNull()
}

internal object TeamChat {
    private fun collection(team: String, request: String?) =
        FirebaseFirestore.getInstance().collection("teams").document(team).let { root ->
            if (request == null) root.collection("chat")
            else root.collection("socialRequests").document(request).collection("comments")
        }

    suspend fun post(c: Context, request: String?, id: String, text: String,
                     item: ChatAttachment?, prelude: Boolean) {
        val team = CloudTeams.teamId(c).takeIf(String::isNotBlank) ?: error("Nincs csapat.")
        val uid = CloudTeams.currentUid().takeIf(String::isNotBlank) ?: error("Nincs bejelentkezés.")
        require(text.length <= 500 && (text.isNotBlank() || item != null))
        require(!prelude || (request == null && item?.mime == "audio/mp4"))
        FirebaseFirestore.getInstance().runTransaction { tx ->
            val ref = collection(team, request).document("${uid}_$id")
            if (!tx.get(ref).exists()) {
                tx.set(ref, mapOf("uid" to uid, "text" to text.trim(), "mime" to item?.mime.orEmpty(),
                    "data" to (item?.let { Base64.encodeToString(it.bytes, Base64.NO_WRAP) } ?: ""),
                    "prelude" to prelude, "createdAt" to FieldValue.serverTimestamp()))
            }
        }.await()
    }

    fun listen(team: String, request: String?, onState: (List<TeamChatItem>?) -> Unit) =
        collection(team, request).orderBy("createdAt", Query.Direction.DESCENDING).limit(35)
            .addSnapshotListener { snap, error ->
                if (error != null) onState(null) else onState(snap?.documents.orEmpty().mapNotNull(::parse))
            }

    fun listenPrelude(team: String, callback: (TeamChatItem?) -> Unit) =
        collection(team, null).whereEqualTo("prelude", true).addSnapshotListener(MetadataChanges.INCLUDE) { snap, error ->
            if (error == null && snap != null && (!snap.metadata.isFromCache || !snap.isEmpty)) {
                val latest = snap.documents.maxByOrNull { it.getTimestamp("createdAt")?.toDate()?.time ?: 0L }
                callback(latest?.let(::parse))
            }
        }

    private fun parse(doc: com.google.firebase.firestore.DocumentSnapshot): TeamChatItem? {
        return TeamChatItem(doc.id, doc.getString("uid") ?: return null, doc.getString("text").orEmpty(),
            doc.getString("mime").orEmpty(), doc.getString("data").orEmpty(), doc.getBoolean("prelude") == true)
    }
}

private class ChatRecorder(private val c: Context) {
    private var recorder: MediaRecorder? = null
    private var path: File? = null

    @Suppress("DEPRECATION")
    fun start(): Boolean = runCatching {
        val file = File.createTempFile("fokusz-voice-", ".m4a", c.cacheDir)
        path = file
        val engine = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(c) else MediaRecorder()
        recorder = engine
        engine.setAudioSource(MediaRecorder.AudioSource.MIC)
        engine.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
        engine.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
        engine.setAudioSamplingRate(22_050)
        engine.setAudioEncodingBitRate(32_000)
        engine.setOutputFile(file.absolutePath)
        engine.prepare()
        engine.start()
    }.getOrElse { close(); return false }.let { true }

    fun stop(): ChatAttachment? {
        val stopped = runCatching { recorder?.stop() }.isSuccess
        val file = path
        val result = if (stopped && file != null && file.length() in 1L..ChatAttachment.MAX_BYTES.toLong()) {
            runCatching { ChatAttachment("audio/mp4", file.readBytes()) }.getOrNull()
        } else null
        close()
        return result
    }

    fun close() {
        runCatching { recorder?.release() }
        recorder = null
        path?.delete()
        path = null
    }
}

internal class TeamVoicePlayer(private val context: Context) {
    private var player: MediaPlayer? = null
    private var file: File? = null

    fun play(item: ChatAttachment, onEnd: (Boolean) -> Unit): Boolean {
        stop()
        return runCatching {
            val path = File.createTempFile("fokusz-listen-", ".m4a", context.cacheDir).apply { writeBytes(item.bytes) }
            file = path
            player = MediaPlayer().apply {
                setDataSource(path.absolutePath)
                setOnCompletionListener { stop(); onEnd(true) }
                setOnErrorListener { _, _, _ -> stop(); onEnd(false); true }
                setOnPreparedListener { it.start() }
                prepareAsync()
            }
        }.isSuccess.also { if (!it) stop() }
    }

    fun stop() {
        runCatching { player?.release() }
        player = null
        file?.delete()
        file = null
    }
}

@Composable internal fun TeamChatPanel(team: CloudTeamView, request: String? = null) {
    val c = LocalContext.current
    val scope = rememberCoroutineScope()
    var opened by remember(team.id, request) { mutableStateOf(false) }
    var rows by remember(team.id, request) { mutableStateOf<List<TeamChatItem>?>(null) }
    var error by remember { mutableStateOf("") }
    var message by remember { mutableStateOf("") }
    var media by remember { mutableStateOf<ChatAttachment?>(null) }
    var prelude by remember { mutableStateOf(false) }
    var recording by remember { mutableStateOf(false) }
    var sending by remember { mutableStateOf(false) }
    var draftId by remember { mutableStateOf(UUID.randomUUID().toString()) }
    val recorder = remember(c) { ChatRecorder(c) }
    val player = remember(c) { TeamVoicePlayer(c) }
    var gateMode by remember { mutableIntStateOf(TeamVoiceGate.mode(c)) }

    fun beginRecording() {
        recording = recorder.start()
        if (!recording) error = "A hangfelvétel most nem indítható."
    }
    fun endRecording() {
        media = recorder.stop()
        recording = false
        if (media == null) error = "A hangfelvétel túl rövid, vagy megszakadt."
    }

    val microphone = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        if (it) beginRecording() else error = "A hangfelvételhez mikrofonengedély kell."
    }
    val picture = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) scope.launch {
            media = withContext(Dispatchers.IO) { runCatching { ChatAttachment.fromUri(c, uri) }.getOrNull() }
            prelude = false
            draftId = UUID.randomUUID().toString()
            error = if (media == null) "Ez a kép vagy GIF nem olvasható, vagy túl nagy." else ""
        }
    }
    DisposableEffect(opened, team.id, request) {
        val registration = if (opened) TeamChat.listen(team.id, request) { current ->
            rows = current
            if (current == null) error = "A beszélgetés most nem érhető el."
        } else null
        onDispose {
            registration?.remove()
            recorder.close()
            player.stop()
        }
    }
    LaunchedEffect(recording) {
        if (recording) {
            delay(15_000L)
            endRecording()
        }
    }

    OutlinedButton(onClick = { opened = !opened; recording = false }) {
        Text(if (opened) "Beszélgetés bezárása" else if (request == null) "Csapatbeszélgetés" else "Hozzászólások")
    }
    if (!opened) return
    if (request == null) {
        Text("Hangüzenet a közös idő előtt")
        for ((mode, label) in listOf(0 to "Kikapcsolva", 1 to "Felajánlja", 2 to "Végig kell hallgatni")) {
            androidx.compose.material3.FilterChip(gateMode == mode, onClick = {
                gateMode = mode
                TeamVoiceGate.setMode(c, mode)
                TeamVoiceGate.watch(c)
            }, label = { Text(label) })
        }
    }
    if (rows == null && error.isBlank()) Text("Üzenetek betöltése…")
    if (rows?.isEmpty() == true) Text("Még nincs üzenet.")
    for (row in rows.orEmpty().asReversed().filterNot { TeamCommunity.hidden(c, it.uid) }) key(row.id) {
        val who = team.members.firstOrNull { it.uid == row.uid }?.name ?: "Korábbi tag"
        Text(if (row.prelude) "$who · Csapatidő előtti üzenet" else who, fontWeight = FontWeight.SemiBold)
        if (row.text.isNotBlank()) Text(row.text)
        val attachment = remember(row.id, row.encoded) { row.attachment() }
        when (attachment?.mime) {
            "audio/mp4" -> OutlinedButton(onClick = {
                if (!player.play(attachment) { ok -> if (!ok) error = "A hang most nem játszható le." }) {
                    error = "A hang most nem játszható le."
                }
            }) { Text("Hangüzenet lejátszása") }
            "image/jpeg", "image/gif" -> ChatPicture(attachment)
        }
    }
    if (error.isNotBlank()) Text(error)
    if (rows == null) return
    OutlinedTextField(message, { message = it.take(500); draftId = UUID.randomUUID().toString() },
        modifier = Modifier.fillMaxWidth(), label = { Text("Üzenet") }, enabled = !sending)
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        OutlinedButton(enabled = !recording && !sending, onClick = { picture.launch("image/*") }) { Text("Kép vagy GIF") }
        OutlinedButton(enabled = !sending, onClick = {
            if (recording) endRecording() else {
                draftId = UUID.randomUUID().toString()
                if (ContextCompat.checkSelfPermission(c, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                    beginRecording()
                } else microphone.launch(Manifest.permission.RECORD_AUDIO)
            }
        }) { Text(if (recording) "Felvétel befejezése" else "Hang felvétele") }
    }
    media?.let { attachment ->
        Text("${attachment.kind} csatolva")
        if (attachment.mime == "audio/mp4") {
            OutlinedButton(onClick = { player.play(attachment) { } }) { Text("Felvétel meghallgatása") }
            if (request == null) OutlinedButton(onClick = {
                prelude = !prelude
                draftId = UUID.randomUUID().toString()
            }) { Text(if (prelude) "Csapatidő előtti üzenetként küldöm ✓" else "Csapatidő előtti üzenetnek jelölöm") }
        }
        OutlinedButton(enabled = !sending, onClick = { media = null; prelude = false; draftId = UUID.randomUUID().toString() }) {
            Text("Csatolmány eltávolítása")
        }
    }
    OutlinedButton(enabled = !sending && !recording && (message.isNotBlank() || media != null), onClick = {
        sending = true
        scope.launch {
            try {
                TeamChat.post(c, request, draftId, message, media, prelude && media?.mime == "audio/mp4")
                message = ""
                media = null
                prelude = false
                error = ""
                draftId = UUID.randomUUID().toString()
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { error = "Az üzenet megmaradt. Később újra elküldheted." }
            finally { sending = false }
        }
    }) { Text("Küldés") }
}

@Composable private fun ChatPicture(attachment: ChatAttachment) {
    AndroidView(modifier = Modifier.fillMaxWidth().heightIn(max = 240.dp), factory = { c ->
        ImageView(c).apply {
            adjustViewBounds = true
            contentDescription = "Csatolt ${attachment.kind}"
            runCatching {
                if (Build.VERSION.SDK_INT >= 28) {
                    val source = ImageDecoder.createSource(ByteBuffer.wrap(attachment.bytes))
                    val drawable = ImageDecoder.decodeDrawable(source) { decoder, header, _ ->
                        val size = header.size
                        val divisor = maxOf(1, maxOf(size.width, size.height) / 600 + 1)
                        decoder.setTargetSize(maxOf(1, size.width / divisor), maxOf(1, size.height / divisor))
                    }
                    setImageDrawable(drawable)
                    if (drawable is AnimatedImageDrawable) {
                        drawable.repeatCount = 2
                        drawable.start()
                    }
                } else {
                    val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeByteArray(attachment.bytes, 0, attachment.bytes.size, options)
                    var sample = 1
                    while (maxOf(options.outWidth, options.outHeight) / sample > 600) sample *= 2
                    options.inJustDecodeBounds = false
                    options.inSampleSize = sample
                    setImageBitmap(BitmapFactory.decodeByteArray(attachment.bytes, 0, attachment.bytes.size, options))
                }
            }
        }
    })
}
