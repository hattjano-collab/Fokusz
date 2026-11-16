package hu.fokusz

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.PointF
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
import kotlin.math.abs
import kotlin.math.sqrt

private data class PoseFeedback(val message: String, val rep: Boolean = false)

private class PushupCounter {
    private var stage = 0 // várjuk a fent -> lent -> fent sorozatot
    private var stableFrames = 0

    private fun angle(a: PointF, b: PointF, c: PointF): Double {
        val abx = (a.x - b.x).toDouble()
        val aby = (a.y - b.y).toDouble()
        val cbx = (c.x - b.x).toDouble()
        val cby = (c.y - b.y).toDouble()
        val norm = sqrt(abx * abx + aby * aby) * sqrt(cbx * cbx + cby * cby)
        return if (norm < 0.001) 0.0 else Math.toDegrees(
            acos(((abx * cbx + aby * cby) / norm).coerceIn(-1.0, 1.0))
        )
    }

    fun analyze(pose: Pose): PoseFeedback {
        val left = listOf(PoseLandmark.LEFT_SHOULDER, PoseLandmark.LEFT_ELBOW,
            PoseLandmark.LEFT_WRIST, PoseLandmark.LEFT_HIP, PoseLandmark.LEFT_ANKLE)
        val right = listOf(PoseLandmark.RIGHT_SHOULDER, PoseLandmark.RIGHT_ELBOW,
            PoseLandmark.RIGHT_WRIST, PoseLandmark.RIGHT_HIP, PoseLandmark.RIGHT_ANKLE)
        fun fetch(ids: List<Int>) = ids.map { pose.getPoseLandmark(it) }
        val ls = fetch(left)
        val rs = fetch(right)
        val side = if (ls.sumOf { (it?.inFrameLikelihood ?: 0f).toDouble() } >=
            rs.sumOf { (it?.inFrameLikelihood ?: 0f).toDouble() }) ls else rs
        if (side.any { it == null || it.inFrameLikelihood < .62f }) {
            stableFrames = 0
            return PoseFeedback("Mutasd a teljes tested oldalról, fejtől lábig!")
        }
        val shoulder = side[0]!!.position
        val elbow = side[1]!!.position
        val wrist = side[2]!!.position
        val hip = side[3]!!.position
        val ankle = side[4]!!.position
        if (abs(shoulder.x - ankle.x) < abs(shoulder.y - ankle.y) * 1.15f ||
            abs(shoulder.x - ankle.x) < 80f) {
            stableFrames = 0
            return PoseFeedback("A fekvő teljes hossza látszódjon a kamerában!")
        }
        val spine = angle(shoulder, hip, ankle)
        if (spine < 154.0) {
            stableFrames = 0
            return PoseFeedback("Egyenes törzs és csípő szükséges. (Most: " + spine.toInt() + "°)")
        }
        val arm = angle(shoulder, elbow, wrist)
        if (stage == 0) {
            stableFrames = if (arm >= 155.0) stableFrames + 1 else 0
            if (stableFrames >= 2) { stage = 1; stableFrames = 0 }
            return PoseFeedback("Kezdj nyújtott karral. (" + arm.toInt() + "°)")
        }
        if (stage == 1) {
            stableFrames = if (arm in 45.0..103.0) stableFrames + 1 else 0
            if (stableFrames >= 2) { stage = 2; stableFrames = 0 }
            return PoseFeedback(if (stage == 2) "Megvan a mély pont! Nyomd vissza magad."
                else "Engedd le magad kb. 90°-os könyökig. (" + arm.toInt() + "°)")
        }
        stableFrames = if (arm >= 155.0) stableFrames + 1 else 0
        if (stableFrames >= 2) {
            stage = 1
            stableFrames = 0
            return PoseFeedback("Szabályos fekvőtámasz! +1", true)
        }
        return PoseFeedback("Nyomd ki magad teljesen. (" + arm.toInt() + "°)")
    }
}

@OptIn(ExperimentalGetImage::class)
@Composable fun PushupCamera(questId: Int, onRep: () -> Unit) {
    val context = LocalContext.current
    val owner = context as ComponentActivity
    var granted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED)
    }
    var front by remember { mutableStateOf(false) }
    var hint by remember { mutableStateOf("Tedd le a telefont oldalra, majd lépj a képbe.") }
    val request = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        granted = it
        if (!it) hint = "A számláláshoz engedélyezned kell a kamerát."
    }
    val onRepUpdated by rememberUpdatedState(onRep)

    if (!granted) {
        Text("A kamerakép helyben kerül elemzésre, nem készül róla felvétel.",
            color = Color(0xFFA7B4CC))
        Button(onClick = { request.launch(Manifest.permission.CAMERA) }) {
            Text("Kamera engedélyezése")
        }
        return
    }

    val previewView = remember(front) {
        PreviewView(context).apply { scaleType = PreviewView.ScaleType.FIT_CENTER }
    }
    val counter = remember(questId, front) { PushupCounter() }
    AndroidView(factory = { previewView },
        modifier = Modifier.fillMaxWidth().height(260.dp).background(Color.Black, RoundedCornerShape(16.dp)))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = { front = !front }, colors = ButtonDefaults.buttonColors(
            containerColor = Color(0xFF273650))) {
            Text(if (front) "Hátsó kamera" else "Elülső kamera")
        }
    }
    Text(hint, color = Color(0xFF7BE7C1))
    Text("Oldalnézetből, teljes testtel a képen. A számláló figyeli a könyökszöget és az egyenes törzset.",
        color = Color(0xFFA7B4CC))
    Text("A felismerés csak becslés: a fényviszonyok és a kamera szöge befolyásolhatják.",
        color = Color(0xFFA7B4CC))

    DisposableEffect(questId, front, owner) {
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
                                            val f = counter.analyze(pose)
                                            hint = f.message
                                            if (f.rep) onRepUpdated()
                                        }
                                    }
                                    .addOnFailureListener(ContextCompat.getMainExecutor(context)) {
                                        if (!disposed) hint = "Nem sikerült felismerni a pózt."
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
