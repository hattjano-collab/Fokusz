package hu.fokusz

import android.graphics.PointF
import android.os.SystemClock
import com.google.mlkit.vision.pose.Pose
import com.google.mlkit.vision.pose.PoseLandmark
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.sqrt

/** On-device, heuristic 2D pose counting for three exercises; not medical-grade verification. */
class ExerciseCounter(private val mode: String) {
    private var stage = 0
    private var stable = 0
    private var plankSince = 0L
    private fun angle(a: PointF, b: PointF, c: PointF): Double {
        val ax = (a.x-b.x).toDouble(); val ay = (a.y-b.y).toDouble()
        val cx = (c.x-b.x).toDouble(); val cy = (c.y-b.y).toDouble()
        val n = sqrt((ax*ax+ay*ay)*(cx*cx+cy*cy))
        return if (n<.001) 0.0 else Math.toDegrees(acos(((ax*cx+ay*cy)/n).coerceIn(-1.0,1.0)))
    }
    fun analyze(pose: Pose): PoseFeedback {
        val left = listOf(PoseLandmark.LEFT_SHOULDER, PoseLandmark.LEFT_HIP,
            PoseLandmark.LEFT_KNEE, PoseLandmark.LEFT_ANKLE)
        val right = listOf(PoseLandmark.RIGHT_SHOULDER, PoseLandmark.RIGHT_HIP,
            PoseLandmark.RIGHT_KNEE, PoseLandmark.RIGHT_ANKLE)
        fun limbs(ids: List<Int>) = ids.map { pose.getPoseLandmark(it) }
        val a = limbs(left); val b = limbs(right)
        val selected = if (a.sumOf { (it?.inFrameLikelihood ?: 0f).toDouble() } >
            b.sumOf { (it?.inFrameLikelihood ?: 0f).toDouble() }) a else b
        if (selected.any { it == null || it.inFrameLikelihood<.60f }) {
            stable = 0;plankSince = 0
            return PoseFeedback("Oldalról látszódjon váll, csípő, térd, boka!")
        }
        val shoulder=selected[0]!!.position;val hip=selected[1]!!.position
        val knee=selected[2]!!.position;val ankle=selected[3]!!.position
        if (mode=="plank") {
            val line=angle(shoulder,hip,ankle)
            val horizontal=abs(shoulder.x-ankle.x)>abs(shoulder.y-ankle.y)*1.1f
            if(line < 158 || !horizontal) {
                plankSince=0L
                return PoseFeedback("Plank: egyenes, vízszintes testtartás szükséges.")
            }
            val now=SystemClock.elapsedRealtime()
            if (plankSince==0L) plankSince=now
            if(now-plankSince>=1000L) {
                plankSince=now
                return PoseFeedback("Plank +1 mp",true)
            }
            return PoseFeedback("Tartsd a planket stabil csípővel.")
        }
        val kneeAngle=angle(hip,knee,ankle)
        val high=kneeAngle>=157
        val low=if(mode=="squats") kneeAngle in 65.0..122.0 else kneeAngle in 65.0..115.0
        if(stage==0) {
            stable=if(high)stable+1 else 0
            if(stable>=2){stage=1;stable=0}
            return PoseFeedback("Nyújtott lábbal kezdd a gyakorlatot.")
        }
        if(stage==1){
            stable=if(low)stable+1 else 0
            if(stable>=2){stage=2;stable=0}
            return PoseFeedback(if(stage==2) "Megfelelő mélység, állj fel!" else
                "Még hajlítsd a térdedet. ("+kneeAngle.toInt()+"°)")
        }
        stable=if(high)stable+1 else 0
        if(stable>=2){stage=1;stable=0;return PoseFeedback("Jó ismétlés! +1",true)}
        return PoseFeedback("Állj fel teljesen! ("+kneeAngle.toInt()+"°)")
    }
}
