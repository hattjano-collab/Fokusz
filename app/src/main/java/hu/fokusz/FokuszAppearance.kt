package hu.fokusz

import android.content.Context
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.min

internal enum class FokuszLook(
    val id: String, val label: String,
    val background: Color, val surface: Color, val foreground: Color, val muted: Color,
    val accent: Color, val onAccent: Color, val outline: Color, val hero: Color,
    val radius: Int, val border: Int, val offset: Boolean = false, val dark: Boolean = false,
) {
    REBEL("rebel", "Rebel", Color(0xfff4f5dc), Color(0xffe9eacb), Color(0xff2a263b), Color(0xff67675b),
        Color(0xffd8f465), Color(0xff2a263b), Color(0xff2a263b), Color(0xffd8f465), 17, 2, offset = true),
    STUDIO("studio", "Studio", Color(0xffffffff), Color(0xfff1f4f6), Color(0xff202a37), Color(0xff606975),
        Color(0xff326fe8), Color.White, Color(0xffdce2ea), Color(0xfff1f4f6), 18, 1),
    NIGHT("night", "After Hours", Color(0xff141916), Color(0xff262e29), Color(0xfff0f2e8), Color(0xffa9b3a9),
        Color(0xffd4f56a), Color(0xff141916), Color(0xff566057), Color(0xff262e29), 12, 1, dark = true),
    PLAY("play", "Playroom", Color(0xfff6f0fd), Color(0xffe9ddf4), Color(0xff382447), Color(0xff6a5e73),
        Color(0xfffdc86d), Color(0xff382447), Color(0xff382447), Color(0xffdfcbef), 22, 2, offset = true),
    SAGE("sage", "Sunday", Color(0xfff6f5ef), Color(0xffe8eade), Color(0xff314339), Color(0xff63695e),
        Color(0xff52765c), Color.White, Color(0xffd7ded1), Color(0xffe1e7d8), 24, 1);

    val shape get() = RoundedCornerShape(radius.dp)
    val link get() = when (this) {
        REBEL -> Color(0xff414a1e)
        PLAY -> Color(0xff69448f)
        STUDIO -> Color(0xff2558ba)
        else -> accent
    }
    val heading get() = when (this) {
        NIGHT -> condensed
        SAGE -> FontFamily.Serif
        else -> bricolage
    }

    companion object {
        fun read(id: String?) = entries.firstOrNull { it.id == id } ?: REBEL
    }
}

private val bricolage = FontFamily(Font(R.font.bricolage_bold, FontWeight.Bold))
private val condensed = FontFamily(Font(R.font.barlow_condensed_bold, FontWeight.Bold))
private val manrope = FontFamily(Font(R.font.manrope_medium, FontWeight.Medium))

internal object FokuszAppearance {
    var look by mutableStateOf(FokuszLook.REBEL)
        private set

    fun load(c: Context) { look = FokuszLook.read(FocusPrefs.appearance(c)) }

    fun choose(c: Context, selected: FokuszLook) {
        FocusPrefs.setAppearance(c, selected.id)
        look = selected
    }
}

@Composable internal fun FokuszTheme(content: @Composable () -> Unit) {
    val look = FokuszAppearance.look
    val scheme = if (look.dark) darkColorScheme() else lightColorScheme()
    val defaults = Typography()
    MaterialTheme(
        colorScheme = scheme.copy(
            primary = look.link, onPrimary = if (look.dark) look.background else Color.White,
            primaryContainer = look.accent, onPrimaryContainer = look.onAccent,
            secondary = look.link, onSecondary = if (look.dark) look.background else Color.White,
            secondaryContainer = look.surface, onSecondaryContainer = look.foreground,
            background = look.background, onBackground = look.foreground,
            surface = look.surface, onSurface = look.foreground, onSurfaceVariant = look.muted,
            surfaceVariant = look.surface, surfaceContainer = look.surface,
            outline = look.outline, outlineVariant = look.outline.copy(alpha = .5f)
        ),
        shapes = Shapes(small = look.shape, medium = look.shape, large = look.shape, extraLarge = look.shape),
        typography = Typography(
            headlineLarge = defaults.headlineLarge.copy(fontFamily = look.heading, fontWeight = FontWeight.Bold),
            headlineMedium = defaults.headlineMedium.copy(fontFamily = look.heading, fontWeight = FontWeight.Bold),
            headlineSmall = defaults.headlineSmall.copy(fontFamily = look.heading, fontWeight = FontWeight.Bold),
            titleLarge = defaults.titleLarge.copy(fontFamily = look.heading),
            titleMedium = defaults.titleMedium.copy(fontFamily = manrope),
            titleSmall = defaults.titleSmall.copy(fontFamily = manrope),
            bodyLarge = defaults.bodyLarge.copy(fontFamily = manrope),
            bodyMedium = defaults.bodyMedium.copy(fontFamily = manrope),
            bodySmall = defaults.bodySmall.copy(fontFamily = manrope),
            labelLarge = defaults.labelLarge.copy(fontFamily = manrope),
            labelMedium = defaults.labelMedium.copy(fontFamily = manrope),
            labelSmall = defaults.labelSmall.copy(fontFamily = manrope)
        ),
        content = content
    )
}

@Composable internal fun FokuszCard(
    modifier: Modifier = Modifier,
    color: Color = FokuszAppearance.look.surface,
    content: @Composable ColumnScope.() -> Unit,
) {
    val look = FokuszAppearance.look
    val styled = if (look.offset) modifier.padding(end = 3.dp, bottom = 4.dp).drawBehind {
        drawRoundRect(look.outline, topLeft = Offset(3.dp.toPx(), 4.dp.toPx()),
            cornerRadius = CornerRadius(look.radius.dp.toPx()))
    } else modifier
    Column(styled.fillMaxWidth().background(color, look.shape)
        .border(look.border.dp, look.outline, look.shape).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp), content = content)
}

@Composable internal fun FokuszAction(label: String, modifier: Modifier = Modifier,
                                      enabled: Boolean = true, primary: Boolean = true, onClick: () -> Unit) {
    val look = FokuszAppearance.look
    val sizing = modifier.heightIn(min = 54.dp)
    if (primary) Button(onClick, sizing, enabled = enabled, shape = look.shape,
        border = if (look.offset) BorderStroke(look.border.dp, look.outline) else null,
        colors = ButtonDefaults.buttonColors(containerColor = look.accent, contentColor = look.onAccent)) {
        Text(label, textAlign = TextAlign.Center)
    } else OutlinedButton(onClick, sizing, enabled = enabled, shape = look.shape,
        border = BorderStroke(look.border.dp, look.outline),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = look.foreground)) {
        Text(label, textAlign = TextAlign.Center)
    }
}

@Composable internal fun FokuszHeading(title: String, label: String = "") {
    val look = FokuszAppearance.look
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        if (label.isNotBlank()) Text(label, color = look.muted, style = MaterialTheme.typography.labelMedium)
        Text(if (look == FokuszLook.NIGHT) title.uppercase(java.util.Locale.forLanguageTag("hu")) else title,
            color = look.foreground, style = MaterialTheme.typography.headlineLarge)
    }
}

internal fun formattedTime(seconds: Long): String {
    val hour = seconds / 3600
    val minute = seconds / 60 % 60
    val second = seconds % 60
    return buildList {
        if (hour > 0) add("$hour ó")
        if (minute > 0 || hour > 0) add("$minute p")
        if (second > 0 || seconds == 0L) add("$second mp")
    }.joinToString(" ")
}

@Composable internal fun LargeTime(seconds: Int, modifier: Modifier = Modifier, color: Color = FokuszAppearance.look.foreground,
                                  centered: Boolean = false, size: Int = 58) {
    val look = FokuszAppearance.look
    val value = formattedTime(seconds.toLong())
    val scale = LocalDensity.current.fontScale.coerceAtLeast(1f)
    val preferred = when {
        value.length >= 16 -> min(size, 32)
        value.length >= 11 -> min(size, 41)
        else -> size
    }
    val font = (preferred / scale.coerceAtMost(1.35f)).coerceAtLeast(26f)
    Text(value, modifier, color = color, fontSize = font.sp, lineHeight = (font * 1.08f).sp,
        fontFamily = look.heading, fontWeight = FontWeight.Bold,
        textAlign = if (centered) TextAlign.Center else TextAlign.Start)
}

@Composable internal fun TimeGraphic(seconds: Int) {
    val look = FokuszAppearance.look
    val progress = (seconds / Store.DAILY.toFloat()).coerceIn(0f, 1f)
    if (look == FokuszLook.NIGHT) {
        FokuszCard {
            Text("Elérhető saját idő", color = look.muted)
            LargeTime(seconds, size = 76)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                repeat(15) { i ->
                    Box(Modifier.weight(1f).height(13.dp).clip(RoundedCornerShape(3.dp))
                        .background(if (i < progress * 15) look.accent else look.outline))
                }
            }
        }
        return
    }
    val round = look == FokuszLook.REBEL || look == FokuszLook.STUDIO
    val scaled = LocalDensity.current.fontScale > 1.55f
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        val meterSize = if (round) Modifier.widthIn(max = if (scaled) 288.dp else 248.dp).fillMaxWidth().aspectRatio(1f)
            else Modifier.fillMaxWidth().heightIn(min = 190.dp)
        Box(meterSize.defaultMinSize(minHeight = 185.dp), contentAlignment = Alignment.Center) {
            Canvas(Modifier.matchParentSize()) {
                val w = size.width
                val h = size.height
                val stroke = look.border.dp.toPx()
                when (look) {
                    FokuszLook.REBEL -> {
                        val radius = min(w, h) / 2 - 3.dp.toPx()
                        val path = Path().apply { addOval(androidx.compose.ui.geometry.Rect(center, radius)) }
                        val bubbles = listOf(Color(0xfff29d78), look.accent, Color(0xffb79ce7), Color(0xff93c9d0))
                        clipPath(path) {
                            for (i in 0 until 12) {
                                val y = h * (.76f + (i * 17 % 25) / 100f) - if (i < progress * 12) h * .07f else 0f
                                val point = Offset(w * ((i * 19 % 102) / 100f), y)
                                val r = w * (.055f + (i * 13 % 9) / 100f)
                                drawCircle(bubbles[i % 4], r, point)
                                drawCircle(look.outline, r, point, style = Stroke(.8f * stroke))
                            }
                        }
                        drawCircle(look.outline, radius, style = Stroke(stroke))
                    }
                    FokuszLook.STUDIO -> {
                        val inset = 9.dp.toPx()
                        val arc = Size(w - 2 * inset, h - 2 * inset)
                        val line = Stroke(6.dp.toPx(), cap = StrokeCap.Round)
                        drawArc(look.outline, -90f, 360f, false, Offset(inset, inset), arc, style = line)
                        if (progress > 0f) drawArc(look.accent, -90f, 360f * progress, false,
                            Offset(inset, inset), arc, style = line)
                    }
                    FokuszLook.PLAY -> {
                        val cloud = Path().apply {
                            moveTo(w*.06f,h*.45f)
                            cubicTo(w*.02f,h*.1f,w*.68f,-h*.03f,w*.9f,h*.22f)
                            cubicTo(w*1.03f,h*.36f,w*.98f,h*.61f,w*.96f,h*.72f)
                            cubicTo(w*.89f,h*1.02f,w*.12f,h*.99f,w*.06f,h*.75f)
                            close()
                        }
                        drawPath(cloud, look.hero)
                        drawPath(cloud, look.outline, style = Stroke(stroke))
                        drawCircle(look.accent, 12.dp.toPx(), Offset(w*.9f,h*.17f))
                    }
                    FokuszLook.SAGE -> {
                        val arch = Path().apply {
                            moveTo(w*.12f,h*.9f)
                            lineTo(w*.12f,h*.62f)
                            cubicTo(w*.12f,-h*.08f,w*.88f,-h*.08f,w*.88f,h*.62f)
                            lineTo(w*.88f,h*.9f)
                            close()
                        }
                        drawPath(arch, look.hero)
                        drawPath(arch, look.outline, style = Stroke(stroke))
                    }
                    else -> Unit
                }
            }
            Column(Modifier.padding(horizontal = if (round) 23.dp else 38.dp, vertical = if (round && scaled) 20.dp else 45.dp),
                horizontalAlignment = Alignment.CenterHorizontally) {
                LargeTime(seconds, Modifier.fillMaxWidth(), centered = true, size = if (round && scaled) 47 else if (round) 57 else 66)
                Text("Saját képernyőidő", color = look.muted, textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.labelLarge)
                if (look == FokuszLook.SAGE) Spacer(Modifier.height(12.dp))
            }
        }
    }
}

@Composable internal fun ThemePicker() {
    val c = LocalContext.current
    val active = FokuszAppearance.look
    FokuszCard {
        Text("Megjelenés", style = MaterialTheme.typography.headlineSmall)
        for (look in FokuszLook.entries) {
            val selected = look == active
            Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).clip(look.shape)
                .selectable(selected, role = Role.RadioButton, onClick = { FokuszAppearance.choose(c, look) })
                .background(look.background).border(look.border.dp, look.outline, look.shape)
                .padding(10.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(Modifier.size(30.dp).clip(look.shape).background(look.accent).border(1.dp, look.outline, look.shape))
                Text(look.label, Modifier.weight(1f), color = look.foreground, fontFamily = look.heading,
                    fontWeight = FontWeight.Bold, fontSize = 18.sp)
                if (selected) Text("✓ Kiválasztva", color = look.muted, style = MaterialTheme.typography.labelMedium)
            }
        }
        FokuszAction("Dizájn váltása", Modifier.fillMaxWidth(), primary = false) {
            FokuszAppearance.choose(c, FokuszLook.entries[(active.ordinal + 1) % FokuszLook.entries.size])
        }
    }
}
