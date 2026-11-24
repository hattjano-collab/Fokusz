package hu.fokusz

import android.os.SystemClock
import com.google.mlkit.vision.pose.Pose
import com.google.mlkit.vision.pose.PoseLandmark
import kotlin.math.abs

/** Helyben futó, élő képből becsült testtartás és ismétlésszámlálás. */
internal class ExerciseCounter(private val mode: String) {
    private var stage = 0
    private var stable = 0
    private var bent = false
    private var plankSince = 0L
    private var invisibleFrames = 0
    private val labels = listOf("a vállad", "a csípőd", "a térded", "a bokád")
    private val left = listOf(PoseLandmark.LEFT_SHOULDER, PoseLandmark.LEFT_HIP,
        PoseLandmark.LEFT_KNEE, PoseLandmark.LEFT_ANKLE)
    private val right = listOf(PoseLandmark.RIGHT_SHOULDER, PoseLandmark.RIGHT_HIP,
        PoseLandmark.RIGHT_KNEE, PoseLandmark.RIGHT_ANKLE)

    fun reset() {
        stage = 0
        stable = 0
        bent = false
        plankSince = 0L
        invisibleFrames = 0
    }

    fun analyze(pose: Pose, counting: Boolean): PoseFeedback {
        val selected = visibleSide(pose, left, right, .60f)
        visibilityFeedback(selected, labels, .60f)?.let { missing ->
            val wasPlank = plankSince != 0L
            stable = 0
            plankSince = 0L
            if (++invisibleFrames >= 4) {
                val moving = bent || stage == 2 || wasPlank
                reset()
                if (counting && moving) return missing.copy(rejection = missing.message)
            }
            return if (counting && wasPlank) missing.copy(rejection = missing.message) else missing
        }
        invisibleFrames = 0
        if (!counting) return PoseFeedback("A vállad, a csípőd, a térded és a bokád látszik.")
        val shoulder = selected[0]!!.position
        val hip = selected[1]!!.position
        val knee = selected[2]!!.position
        val ankle = selected[3]!!.position
        if (mode == "plank") {
            val line = limbAngle(shoulder, hip, ankle)
            val horizontal = abs(shoulder.x - ankle.x) > abs(shoulder.y - ankle.y) * 1.1f
            if (!line.isFinite() || line < 158 || !horizontal) {
                val reason = "Tartsd egy vonalban a vállad, a csípőd és a bokád."
                val rejected = plankSince != 0L
                plankSince = 0L
                return PoseFeedback(reason, rejection = reason.takeIf { rejected })
            }
            val now = SystemClock.elapsedRealtime()
            if (plankSince == 0L || now - plankSince > 1800L) plankSince = now
            if (now - plankSince >= 1000L) {
                plankSince = now
                return PoseFeedback("Plank +1 mp", rep = true)
            }
            return PoseFeedback("Tartsd stabilan a planket.")
        }

        val angle = limbAngle(hip, knee, ankle)
        val high = angle >= 157
        val limit = if (mode == "squats") 122 else 115
        val low = angle in 65.0..limit.toDouble()
        if (stage == 0) {
            stable = if (high) stable + 1 else 0
            if (stable >= 2) { stage = 1; stable = 0 }
            return PoseFeedback("Nyújtott lábbal kezdd a gyakorlatot.")
        }
        if (stage == 1) {
            if (angle < 149) bent = true
            if (bent && high) {
                bent = false
                stable = 0
                val reason = "A térded nem érte el a szükséges hajlítást."
                return PoseFeedback(reason, rejection = reason)
            }
            stable = if (low) stable + 1 else 0
            if (stable >= 2) { stage = 2; stable = 0 }
            return if (stage == 2) PoseFeedback("Megfelelő mélység. Állj fel!")
                else PoseFeedback("Hajlítsd tovább a térded.")
        }
        stable = if (high) stable + 1 else 0
        if (stable >= 2) {
            stage = 1
            stable = 0
            bent = false
            return PoseFeedback("Jó ismétlés! +1", rep = true)
        }
        return PoseFeedback("Állj fel teljesen, hogy számolhassam az ismétlést.")
    }
}
