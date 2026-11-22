package hu.fokusz

import android.graphics.PointF
import android.os.SystemClock
import androidx.camera.core.CameraSelector
import androidx.camera.core.CameraState
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
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LiveData
import androidx.lifecycle.Observer
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.pose.Pose
import com.google.mlkit.vision.pose.PoseDetection
import com.google.mlkit.vision.pose.PoseLandmark
import com.google.mlkit.vision.pose.defaults.PoseDetectorOptions
import java.util.concurrent.Executors
import kotlinx.coroutines.delay
import kotlin.math.acos
import kotlin.math.hypot

internal data class PoseFeedback(
    val message: String,
    val rep: Boolean = false,
    val visible: Boolean = true,
    val rejection: String? = null
)

internal fun limbAngle(a: PointF, b: PointF, c: PointF): Double {
    val x = (a.x - b.x).toDouble()
    val y = (a.y - b.y).toDouble()
    val u = (c.x - b.x).toDouble()
    val v = (c.y - b.y).toDouble()
    val size = hypot(x, y) * hypot(u, v)
    return if (size < .001) Double.NaN else Math.toDegrees(acos(((x * u + y * v) / size).coerceIn(-1.0, 1.0)))
}

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
        val parts = indices.map { labels[it] }
        return if (parts.size == 1) parts.single() else parts.dropLast(1).joinToString(", ") + " és " + parts.last()
    }
    val message = if (visible.isEmpty()) "Fordulj oldalra. A képen látszódjon ${names(missing)}."
        else "Látszik ${names(visible)}. Kikerült a képből ${names(missing)}."
    return PoseFeedback(message, visible = false)
}

private class PushupCounter {
    private val cycle = PushupCycle()
    private var invisibleFrames = 0
    private var previousArm: Int? = null
    private val left = listOf(PoseLandmark.LEFT_SHOULDER, PoseLandmark.LEFT_ELBOW, PoseLandmark.LEFT_WRIST)
    private val right = listOf(PoseLandmark.RIGHT_SHOULDER, PoseLandmark.RIGHT_ELBOW, PoseLandmark.RIGHT_WRIST)
    private val labels = listOf("a vállad", "a könyököd", "a csuklód")

    fun reset() {
        cycle.reset()
        invisibleFrames = 0
        previousArm = null
    }

    fun analyze(pose: Pose, counting: Boolean): PoseFeedback {
        val threshold = .38f
        val preferred = if (previousArm == left.first()) left else right
        val arm = if (cycle.moving && previousArm != null) preferred.map(pose::getPoseLandmark)
            else visibleSide(pose, left, right, threshold)
        val missing = visibilityFeedback(arm, labels, threshold)
        if (missing != null) {
            if (++invisibleFrames >= 4) {
                val rejected = counting && cycle.moving
                reset()
                if (rejected) return missing.copy(rejection = missing.message)
            }
            return missing
        }
        invisibleFrames = 0
        val side = arm.first()!!.landmarkType
        if (previousArm != side) cycle.reset()
        previousArm = side
        val angle = limbAngle(arm[0]!!.position, arm[1]!!.position, arm[2]!!.position)
        if (!angle.isFinite()) return PoseFeedback("Fordítsd oldalra a kamerát, hogy elkülönüljön a három pont.", visible = false)
        if (!counting) return PoseFeedback("Az egyik vállad, könyököd és csuklód látszik.")
        return cycle.track(angle, SystemClock.elapsedRealtime())
    }
}

@Composable
internal fun WorkoutCamera(
    key: String,
    session: String,
    mode: String,
    speech: WorkoutSpeech = rememberWorkoutSpeech(),
    onRep: () -> Int?
) {
    CameraPermissionGate(requestOnEnter = true) {
        NativeWorkoutCamera(key, session, mode, speech, onRep)
    }
}

@OptIn(ExperimentalGetImage::class)
@Composable
private fun NativeWorkoutCamera(key: String, session: String, mode: String, speech: WorkoutSpeech, onRep: () -> Int?) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val timer = remember(context, key, session) { WorkoutRest(context, key, session) }
    val onRepUpdated by rememberUpdatedState(onRep)
    val pushups = remember(session) { PushupCounter() }
    val exercise = remember(session, mode) { ExerciseCounter(mode) }
    var front by rememberSaveable { mutableStateOf(true) }
    var voice by rememberSaveable { mutableStateOf(true) }
    var ready by remember(session, front) { mutableStateOf(false) }
    var foreground by remember(owner) { mutableStateOf(owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) }
    var hint by remember(session, front) { mutableStateOf("Várakozás a kamera képére…") }
    var lastRejection by remember(session) { mutableStateOf("") }
    var rejected by remember(session) { mutableIntStateOf(0) }
    var failure by remember(front) { mutableStateOf(false) }
    var retry by remember { mutableIntStateOf(0) }
    var running by remember(session) { mutableStateOf(false) }
    var prepareSeconds by remember(timer) { mutableIntStateOf(timer.prepareSeconds()) }
    var restSeconds by remember(timer) { mutableIntStateOf(timer.restSeconds()) }
    var restDeadline by remember(timer) { mutableLongStateOf(timer.until()) }
    var prepareDeadline by remember(session) { mutableLongStateOf(0L) }
    var prepareLeft by remember(session) { mutableIntStateOf(0) }
    var restLeft by remember(timer) {
        mutableIntStateOf(((restDeadline - SystemClock.elapsedRealtime() + 999L) / 1000L).toInt().coerceAtLeast(0))
    }
    val previewView = remember(context) {
        PreviewView(context).apply {
            scaleType = PreviewView.ScaleType.FIT_CENTER
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
    }
    fun pause() {
        running = false
        prepareDeadline = 0L
        prepareLeft = 0
        pushups.reset()
        exercise.reset()
        speech.stop()
    }
    DisposableEffect(owner, session) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> foreground = true
                Lifecycle.Event.ON_PAUSE -> {
                    foreground = false
                    pause()
                    ready = false
                }
                else -> Unit
            }
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(prepareDeadline) {
        val deadline = prepareDeadline
        if (deadline == 0L) return@LaunchedEffect
        while (true) {
            val remaining = deadline - SystemClock.elapsedRealtime()
            if (remaining <= 0) break
            val seconds = ((remaining + 999L) / 1000L).toInt()
            prepareLeft = seconds
            if (seconds in 1..3) speech.cue()
            delay((remaining - (seconds - 1) * 1000L).coerceAtLeast(1L))
        }
        prepareDeadline = 0L
        prepareLeft = 0
        if (ready && !failure && foreground) {
            running = true
            speech.cue(finished = true)
        } else {
            hint = "Igazíts a kamerán, majd indítsd újra a felkészülést."
        }
    }
    LaunchedEffect(restDeadline) {
        val deadline = restDeadline
        if (deadline == 0L) return@LaunchedEffect
        while (true) {
            val remaining = deadline - SystemClock.elapsedRealtime()
            if (remaining <= 0) break
            restLeft = ((remaining + 999L) / 1000L).toInt()
            delay((remaining - (restLeft - 1) * 1000L).coerceAtLeast(1L))
        }
        restLeft = 0
        restDeadline = 0L
        timer.clear()
        if (foreground) speech.cue(finished = true)
    }

    Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
        AndroidView(factory = { previewView }, modifier = Modifier.fillMaxWidth().height(260.dp)
            .clip(RoundedCornerShape(16.dp)).background(Color.Black))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = {
                pause()
                front = !front
            }) { Text(if (front) "Hátsó kamera" else "Elülső kamera") }
            if (failure) OutlinedButton(onClick = {
                ready = false
                failure = false
                hint = "Kamera újraindítása…"
                retry++
            }) { Text("Újrapróbálás") }
        }
        Text(hint, color = if (ready) Color(0xFF7BE7C1) else Color(0xFFFFC78D))
        if (lastRejection.isNotBlank()) Text("Kimaradt mozdulatok: $rejected. Legutóbb: $lastRejection",
            color = Color(0xFFFFC78D), fontSize = 13.sp)
        WorkoutSpeechToggle(speech, voice) { voice = it }
        if (!running && prepareDeadline == 0L && restDeadline == 0L) {
            Text("Felkészülési idő", color = Color(0xFFF5F7FF))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(3, 5, 10).forEach { seconds ->
                    FilterChip(selected = prepareSeconds == seconds, onClick = {
                        prepareSeconds = seconds
                        timer.savePrepare(seconds)
                    }, label = { Text("$seconds mp") })
                }
            }
            Button(onClick = {
                prepareLeft = prepareSeconds
                prepareDeadline = SystemClock.elapsedRealtime() + prepareSeconds * 1000L
            }, enabled = ready && !failure) { Text("Számlálás indítása") }
        }
        if (prepareDeadline != 0L) {
            Text("Felkészülés: $prepareLeft mp", color = Color(0xFFF5F7FF), fontWeight = FontWeight.Bold)
            OutlinedButton(onClick = { pause() }) { Text("Visszaszámlálás leállítása") }
        } else if (restDeadline != 0L) {
            Text("Pihenő: $restLeft mp", color = Color(0xFFF5F7FF), fontWeight = FontWeight.Bold)
            OutlinedButton(onClick = {
                timer.clear()
                restDeadline = 0L
                restLeft = 0
            }) { Text("Pihenő befejezése") }
        } else {
            if (running) OutlinedButton(onClick = { pause() }) { Text("Számlálás szüneteltetése") }
            Text("Pihenőidő két sorozat között", color = Color(0xFFF5F7FF))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(30, 60, 90).forEach { seconds ->
                    FilterChip(selected = restSeconds == seconds, onClick = {
                        restSeconds = seconds
                        timer.saveRest(seconds)
                    }, label = { Text("$seconds mp") })
                }
            }
            OutlinedButton(onClick = {
                pause()
                restLeft = restSeconds
                restDeadline = timer.start(restSeconds)
            }) { Text("Pihenő indítása") }
        }
    }

    DisposableEffect(owner, front, retry, session) {
        val executor = Executors.newSingleThreadExecutor()
        val detector = PoseDetection.getClient(PoseDetectorOptions.Builder()
            .setDetectorMode(PoseDetectorOptions.STREAM_MODE).build())
        val main = ContextCompat.getMainExecutor(context)
        val providerFuture = ProcessCameraProvider.getInstance(context)
        val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
        val analysis = ImageAnalysis.Builder().setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()
        var provider: ProcessCameraProvider? = null
        var cameraState: LiveData<CameraState>? = null
        var disposed = false
        fun showFailure() {
            pause()
            ready = false
            failure = true
            hint = "A kamera nem indult el. Zárd be a többi kamerás appot, vagy próbáld a másik kamerát."
        }
        val cameraObserver = Observer<CameraState> { state ->
            if (state.error != null && !disposed) showFailure()
        }
        analysis.setAnalyzer(executor) { frame ->
            val image = frame.image
            if (image == null) {
                frame.close()
            } else {
                try {
                    val input = InputImage.fromMediaImage(image, frame.imageInfo.rotationDegrees)
                    detector.process(input).addOnSuccessListener(main) { pose ->
                        if (!disposed && foreground) {
                            failure = false
                            val feedback = if (mode == "pushups") pushups.analyze(pose, running) else exercise.analyze(pose, running)
                            ready = feedback.visible
                            hint = feedback.message
                            feedback.rejection?.let { reason ->
                                lastRejection = reason
                                rejected++
                            }
                            if (running && feedback.rep) {
                                onRepUpdated()?.let { count ->
                                    if (voice) speech.speak(count)
                                }
                            }
                        }
                    }.addOnFailureListener(main) {
                        if (!disposed && foreground) {
                            ready = false
                            hint = "A kamera képe megérkezett, de a testhelyzet elemzése nem sikerült."
                        }
                    }.addOnCompleteListener { frame.close() }
                } catch (_: Exception) {
                    frame.close()
                }
            }
        }
        providerFuture.addListener({
            if (!disposed) {
                try {
                    val cameraProvider = providerFuture.get()
                    provider = cameraProvider
                    val preferred = if (front) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
                    val alternative = if (front) CameraSelector.DEFAULT_BACK_CAMERA else CameraSelector.DEFAULT_FRONT_CAMERA
                    val camera = if (cameraProvider.hasCamera(preferred)) preferred else alternative
                    cameraState = cameraProvider.bindToLifecycle(owner, camera, preview, analysis).cameraInfo.cameraState
                    cameraState?.observe(owner, cameraObserver)
                } catch (_: Exception) {
                    showFailure()
                }
            }
        }, main)
        onDispose {
            disposed = true
            cameraState?.removeObserver(cameraObserver)
            analysis.clearAnalyzer()
            runCatching { provider?.unbind(preview, analysis) }
            runCatching { detector.close() }
            executor.shutdown()
        }
    }
}
