package hu.fokusz

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.PointF
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.pose.Pose
import com.google.mlkit.vision.pose.PoseDetection
import com.google.mlkit.vision.pose.PoseLandmark
import com.google.mlkit.vision.pose.defaults.PoseDetectorOptions
import java.util.concurrent.Executors
import kotlin.math.acos
import kotlin.math.sqrt

data class PoseFeedback(val message: String, val rep: Boolean = false, val visible: Boolean = true)

internal fun visibleSide(
    pose: Pose, left: List<Int>, right: List<Int>, threshold: Float
): List<PoseLandmark?> = listOf(left, right).map { ids -> ids.map(pose::getPoseLandmark) }
    .maxWith(compareBy<List<PoseLandmark?>> { side ->
        side.count { (it?.inFrameLikelihood ?: 0f) >= threshold }
    }.thenBy { side -> side.sumOf { (it?.inFrameLikelihood ?: 0f).toDouble() } })

internal fun visibilityFeedback(
    points: List<PoseLandmark?>, labels: List<String>, threshold: Float
): PoseFeedback? {
    val (visible, missing) = labels.indices.partition { (points[it]?.inFrameLikelihood ?: 0f) >= threshold }
    if (missing.isEmpty()) return null
    fun names(indices: List<Int>): String {
        val names = indices.map { labels[it] }
        return if (names.size == 1) names.single() else names.dropLast(1).joinToString(", ") + " és " + names.last()
    }
    val message = if (visible.isEmpty()) "Fordulj oldalra. A képen látszódjon ${names(missing)}."
        else "Látszik ${names(visible)}. Igazíts a kamerán, hogy ${names(missing)} is látszódjon."
    return PoseFeedback(message, visible = false)
}

/** A számláló és a webes edzésazonos döntési logikája:
 * váll–könyök–csukló, 120° lent, 145° fent, nem kell teljes test.
 */
private class PushupCounter {
    private var stage=0 // 0: indulás, 1: fenti helyzet, 2: lent volt
    private var lastRepAt=0L
    private var invisibleFrames = 0
    private fun angle(a:PointF,b:PointF,c:PointF):Double {
        val abx=(a.x-b.x).toDouble();val aby=(a.y-b.y).toDouble()
        val cbx=(c.x-b.x).toDouble();val cby=(c.y-b.y).toDouble()
        val norm=sqrt(abx*abx+aby*aby)*sqrt(cbx*cbx+cby*cby)
        return if(norm<.001)0.0 else Math.toDegrees(
            acos(((abx*cbx+aby*cby)/norm).coerceIn(-1.0,1.0)))
    }
    fun analyze(pose:Pose):PoseFeedback {
        val left=listOf(PoseLandmark.LEFT_SHOULDER,PoseLandmark.LEFT_ELBOW,
            PoseLandmark.LEFT_WRIST)
        val right=listOf(PoseLandmark.RIGHT_SHOULDER,PoseLandmark.RIGHT_ELBOW,
            PoseLandmark.RIGHT_WRIST)
        val usable = visibleSide(pose, left, right, .38f)
        visibilityFeedback(usable, listOf("a vállad", "a könyököd", "a csuklód"), .38f)?.let {
            if (++invisibleFrames >= 4) stage = 0
            return it
        }
        invisibleFrames = 0
        val shoulder=usable[0]!!.position
        val elbow=usable[1]!!.position
        val wrist=usable[2]!!.position
        val armAngle=angle(shoulder,elbow,wrist)
        if(armAngle==0.0) return PoseFeedback("Kicsit mozdítsd el a kamerát a karod mellől.")
        if(stage==0) {
            if(armAngle>=145.0)stage=1
            return PoseFeedback("Indulj fent, közel nyújtott karral. ("+armAngle.toInt()+"°)")
        }
        if(stage==1) {
            if(armAngle<=120.0) {
                stage=2
                return PoseFeedback("Szuper, elég mély! Most nyomd fel magad.")
            }
            return PoseFeedback("Hajlítsd a könyököd 120°-ig. ("+armAngle.toInt()+"°)")
        }
        if(armAngle>=145.0) {
            stage=1
            val now=SystemClock.elapsedRealtime()
            if(now-lastRepAt>=450L) {
                lastRepAt=now
                return PoseFeedback("Fekvőtámasz +1! ("+armAngle.toInt()+"°)",true)
            }
        }
        return PoseFeedback("Nyomd fel magad 145° fölé. ("+armAngle.toInt()+"°)")
    }
}
@OptIn(ExperimentalGetImage::class)
@Composable fun PushupCamera(questId: Int, mode: String = "pushups", onIssue: ((String) -> Unit)? = null, onRep: () -> Unit) {
    val context = LocalContext.current
    val owner = context as ComponentActivity
    var granted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED)
    }
    var front by remember { mutableStateOf(true) }
    val alignment = if (mode == "pushups") "A vállad, a könyököd és a csuklód látszik."
        else "A vállad, a csípőd, a térded és a bokád látszik."
    var hint by remember(questId, mode, front) { mutableStateOf("Helyezd a telefont oldalra a gyakorlat előtt.") }
    var cameraReady by remember(questId, mode, front) { mutableStateOf(false) }
    val request = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        granted = it
        if (!it) hint = "A számláláshoz engedélyezned kell a kamerát."
    }
    val onRepUpdated by rememberUpdatedState(onRep)
    val onIssueUpdated by rememberUpdatedState(onIssue)
    var lastIssueAt by remember { mutableStateOf(0L) }
    var lastIssueText by remember { mutableStateOf("") }
    if (!granted) {
        Text("A kamerakép helyben kerül elemzésre, nem készül róla felvétel.",
            color = Color(0xFFA7B4CC))
        Button(onClick = { request.launch(Manifest.permission.CAMERA) }) {
            Text("Kamera engedélyezése")
        }
        return
    }

    var voiceEnabled by remember(questId, mode) { mutableStateOf(false) }
    var sessionCount by remember(questId, mode) { mutableIntStateOf(0) }
    val speech = rememberWorkoutSpeech()
    val previewView = remember(context) {
        PreviewView(context).apply { scaleType = PreviewView.ScaleType.FIT_CENTER }
    }
    val counter = remember(questId, front) { PushupCounter() }
    val exerciseCounter = remember(questId, front, mode) { ExerciseCounter(mode) }
    AndroidView(factory = { previewView },
        modifier = Modifier.fillMaxWidth().height(260.dp).background(Color.Black, RoundedCornerShape(16.dp)))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = { front = !front }, colors = ButtonDefaults.buttonColors(
            containerColor = Color(0xFF273650))) {
            Text(if (front) "Hátsó kamera" else "Elülső kamera")
        }
    }
    WorkoutSpeechToggle(speech, voiceEnabled) { voiceEnabled = it }
    if (cameraReady) Text(alignment, color = Color(0xFF7BE7C1))
    Text(hint, color = Color(0xFF7BE7C1))
    Text(if (mode == "pushups") "Az egyik karod látszódjon oldalról. A leengedés 120°-ig, a kinyomás 145°-ig számít."
        else "Fordulj oldalra, hogy a kamera lássa a vállad, a csípőd, a térded és a bokád.",
        color = Color(0xFFA7B4CC))
    Text("A felismerés csak becslés: a fényviszonyok és a kamera szöge befolyásolhatják.",
        color = Color(0xFFA7B4CC))

    DisposableEffect(questId, front, mode, owner) {
        val executor = Executors.newSingleThreadExecutor()
        val detector = PoseDetection.getClient(PoseDetectorOptions.Builder()
            .setDetectorMode(PoseDetectorOptions.STREAM_MODE).build())
        val providerFuture = ProcessCameraProvider.getInstance(context)
        var provider: ProcessCameraProvider? = null
        var disposed = false
        providerFuture.addListener({
            if (!disposed) {
                try {
                    val p = providerFuture.get()
                    provider = p
                    val preview = Preview.Builder().build()
                    preview.setSurfaceProvider(previewView.surfaceProvider)
                    val analysis = ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build()
                    analysis.setAnalyzer(executor) { frame ->
                        val raw = frame.image
                        if (raw == null) frame.close()
                        else {
                            try {
                                val input = InputImage.fromMediaImage(raw, frame.imageInfo.rotationDegrees)
                                detector.process(input)
                                    .addOnSuccessListener(ContextCompat.getMainExecutor(context)) { pose ->
                                        if (!disposed) {
                                            val f = if (mode == "pushups") counter.analyze(pose) else exerciseCounter.analyze(pose)
                                            cameraReady = f.visible
                                            hint = f.message
                                            if (f.rep) {
                                                sessionCount++
                                                if (voiceEnabled) speech.speak(sessionCount)
                                                onRepUpdated()
                                            }
                                            val nowFeedback=SystemClock.elapsedRealtime()
                                            val invalid=f.message.contains("egyenes",true) ||
                                                f.message.contains("mélység",true) ||
                                                f.message.contains("Nem",true) ||
                                                f.message.contains("Még hajlítsd",true)
                                            if (invalid && (nowFeedback-lastIssueAt>4000L ||
                                                f.message!=lastIssueText && nowFeedback-lastIssueAt>2000L)) {
                                                lastIssueAt=nowFeedback
                                                lastIssueText=f.message
                                                onIssueUpdated?.invoke(f.message)
                                            }
                                        }
                                    }
                                    .addOnFailureListener(ContextCompat.getMainExecutor(context)) {
                                        if (!disposed) {
                                            cameraReady = false
                                            hint = "Nem sikerült felismerni a pózt."
                                        }
                                    }
                                    .addOnCompleteListener { frame.close() }
                            } catch (_: Exception) { frame.close() }
                        }
                    }
                    val camera = if (front) CameraSelector.DEFAULT_FRONT_CAMERA
                                 else CameraSelector.DEFAULT_BACK_CAMERA
                    p.unbindAll()
                    p.bindToLifecycle(owner, camera, preview, analysis)
                } catch (_: Exception) {
                    hint = "A kamera nem indult el. Próbáld meg a másik kamerát!"
                }
            }
        }, ContextCompat.getMainExecutor(context))
        onDispose {
            disposed = true
            try { provider?.unbindAll() } catch (_: Exception) {}
            try { detector.close() } catch (_: Exception) {}
            executor.shutdown()
        }
    }
}
