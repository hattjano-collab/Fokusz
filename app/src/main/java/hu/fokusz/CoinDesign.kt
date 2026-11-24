package hu.fokusz

import android.graphics.Canvas as AndroidCanvas
import android.graphics.Color as AColor
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.Typeface
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

@Composable internal fun BimetalCoin(angle: Float, flight: Float) {
    val degrees = (angle % 360f + 360f) % 360f
    val tails = degrees in 90f..270f
    val bounce = if (flight > .9f) sin((flight - .9f) * 10f * PI).toFloat() * 3.5f else 0f
    val lift = 4f * flight * (1f - flight) * 82f + bounce
    Box(Modifier.fillMaxWidth().height(206.dp), contentAlignment = Alignment.BottomCenter) {
        Canvas(Modifier.size(128.dp, 25.dp).graphicsLayer { alpha = 1f - (lift / 110f) }) {
            drawOval(Brush.radialGradient(listOf(Color(0x88000000), Color.Transparent),
                center = center, radius = size.width * .48f))
        }
        Box(Modifier.size(126.dp).graphicsLayer {
            rotationX = degrees
            rotationZ = sin(flight * PI).toFloat() * 13f
            cameraDistance = 18.dp.toPx()
            translationY = -(lift + 9f).dp.toPx()
        }) {
            Canvas(Modifier.size(126.dp).graphicsLayer {
                rotationX = if (tails) 180f else 0f
            }.semantics { contentDescription = if (tails) "Írás: 200 forint" else "Fej: Lánchíd" }) {
                drawIntoCanvas { compose ->
                    val target = compose.nativeCanvas
                    target.save()
                    target.scale(size.width / 200f, size.height / 200f)
                    drawCoinFace(target, tails, degrees)
                    target.restore()
                }
            }
        }
    }
}

private fun drawCoinFace(canvas: AndroidCanvas, tails: Boolean, angle: Float) {
    val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    fun color(hex: String): Int = AColor.parseColor(hex)
    fun ring(radius: Float, hex: String, line: Float) {
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = line
        paint.color = color(hex)
        canvas.drawCircle(100f, 100f, radius, paint)
        paint.style = Paint.Style.FILL
    }
    fun metal(radius: Float, colors: List<String>) {
        val shine = 23f * cos(angle * PI / 180).toFloat()
        paint.shader = RadialGradient(82f + shine, 44f, radius + 57f,
            colors.map(::color).toIntArray(), null, Shader.TileMode.CLAMP)
        canvas.drawCircle(100f, 100f, radius, paint)
        paint.shader = null
    }
    metal(98f, listOf("#FFF1BE", "#C39B55", "#926A36", "#513A23"))
    ring(96.5f, "#573B22", 2.5f)
    ring(93.8f, "#F4D9A1", 1.4f)
    paint.strokeWidth = 1.2f
    for (index in 0 until 96) {
        val theta = index * PI / 48.0
        val x = cos(theta).toFloat()
        val y = sin(theta).toFloat()
        paint.color = color(if (index % 2 == 0) "#E6CB8D" else "#6E4E28")
        canvas.drawLine(100 + 90 * x, 100 + 90 * y, 100 + 93 * x, 100 + 93 * y, paint)
    }
    ring(88f, "#7C552A", 1.5f)
    ring(69.2f, "#674B30", 2f)
    metal(67.5f, listOf("#F9F7EE", "#DBDEDA", "#B5BCC0", "#7D8284"))
    ring(66.5f, "#EDF1EE", 1.3f)
    ring(62.8f, "#859091", .8f)
    for (index in 0 until 64) {
        val theta = index * PI / 32.0
        paint.color = color("#838B88")
        canvas.drawCircle(100 + 60 * cos(theta).toFloat(), 100 + 60 * sin(theta).toFloat(), .75f, paint)
    }
    fun engravedText(value: String, x: Float, y: Float, size: Float, heavy: Boolean = false) {
        paint.typeface = Typeface.create("serif", if (heavy) Typeface.BOLD else Typeface.NORMAL)
        paint.textSize = size
        paint.textAlign = Paint.Align.CENTER
        paint.color = color("#FBF6DF")
        canvas.drawText(value, x, y + 1.1f, paint)
        paint.color = color("#44433B")
        canvas.drawText(value, x, y, paint)
    }
    fun relief(path: Path, width: Float) {
        paint.style = Paint.Style.STROKE
        paint.strokeCap = Paint.Cap.ROUND
        paint.strokeJoin = Paint.Join.ROUND
        paint.strokeWidth = width
        canvas.save()
        canvas.translate(0f, 1.15f)
        paint.color = color("#EFF1E7")
        canvas.drawPath(path, paint)
        canvas.restore()
        paint.color = color("#525955")
        canvas.drawPath(path, paint)
        paint.style = Paint.Style.FILL
    }
    engravedText(if (tails) "ÍRÁS" else "FEJ", 100f, 26f, 11f, true)
    engravedText("FÓKUSZ", 100f, 181f, 9f, true)
    engravedText("✧", 21f, 103f, 13f)
    engravedText("✧", 179f, 103f, 13f)
    if (tails) {
        engravedText("200", 100f, 113f, 49f, true)
        engravedText("FORINT", 100f, 136f, 15f, true)
        relief(Path().apply { moveTo(66f, 146f); quadTo(100f, 151f, 134f, 146f) }, 1.5f)
    } else {
        engravedText("LÁNCHÍD", 100f, 63f, 11f, true)
        relief(Path().apply {
            moveTo(47f, 116f); lineTo(153f, 116f)
            moveTo(49f, 121f); lineTo(151f, 121f)
            for (pylon in listOf(70f, 121f)) {
                moveTo(pylon, 130f); lineTo(pylon, 84f); lineTo(pylon + 9, 84f); lineTo(pylon + 9, 130f)
                moveTo(pylon - 1.5f, 87f); lineTo(pylon + 10.5f, 87f)
                moveTo(pylon - 1.5f, 127f); lineTo(pylon + 10.5f, 127f)
            }
            moveTo(47f, 111f); quadTo(63f, 106f, 75f, 89f)
            quadTo(100f, 129f, 125f, 89f)
            quadTo(137f, 106f, 153f, 111f)
        }, 2.35f)
        relief(Path().apply {
            for ((x,y) in listOf(54f to 109f, 61f to 105f, 83f to 101f, 91f to 106f,
                100f to 109f, 109f to 106f, 117f to 101f, 139f to 105f, 146f to 109f)) {
                moveTo(x,y); lineTo(x,115f)
            }
            for (y in listOf(134f, 140f, 146f)) {
                moveTo(58f,y); quadTo(81f,y-6,100f,y); quadTo(119f,y+6,142f,y)
            }
        }, 1.4f)
    }
}
