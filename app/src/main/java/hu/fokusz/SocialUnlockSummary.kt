package hu.fokusz

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

internal fun usageStreak(today: LocalDate, goalSeconds: Int, use: (LocalDate) -> Int?): Int {
    var day = (0L until 14L).map(today::minusDays).firstOrNull { use(it) != null } ?: return 0
    var streak = 0
    while (true) {
        val seconds = use(day) ?: break
        if (seconds > goalSeconds) break
        streak++
        day = day.minusDays(1)
    }
    return streak
}

@Composable internal fun SocialUnlockSummary(history: List<SocialUnlockEntry>) {
    val c = LocalContext.current
    val zone = ZoneId.systemDefault()
    val day = LocalDate.now()
    val dayFormat = remember { DateTimeFormatter.ofPattern("MM.dd.") }
    val timeFormat = remember { DateTimeFormatter.ofPattern("HH:mm") }
    var weekOffset by remember { mutableLongStateOf(0) }
    var chosenDay by remember(day) { mutableStateOf(day) }
    var detailed by remember { mutableStateOf(false) }
    var targetText by remember { mutableStateOf("") }
    var targetMinutes by remember { mutableIntStateOf(SocialUnlockStore.baseline(c)) }
    var preview by remember { mutableStateOf<Bitmap?>(null) }
    var feedback by remember { mutableStateOf("") }

    val fullDays = remember(day, history, zone) {
        history.groupBy { Instant.ofEpochMilli(it.grant.startedAt).atZone(zone).toLocalDate() }
    }
    fun observed(date: LocalDate) = SocialUnlockStore.dailyUsed(c, date) ?: fullDays[date]?.sumOf { it.usedSeconds }
    val endDay = day.minusWeeks(weekOffset)
    val days = (6L downTo 0).map { endDay.minusDays(it) }
    val dailyValues = days.map { observed(it) }
    val total = dailyValues.sumOf { it ?: 0 }
    val prior = days.sumOf { observed(it.minusWeeks(1)) ?: 0 }
    val grantRows = days.flatMap { fullDays[it].orEmpty() }
    val requested = grantRows.sumOf { it.grant.minutes.toLong() }
    val measured = grantRows.sumOf { it.usedSeconds.toLong() }
    val cancelled = days.sumOf { SocialUnlockStore.cancelled(c, it).toLong() }
    val max = maxOf(1, dailyValues.maxOf { it ?: 0 })
    val streak = if (targetMinutes > 0) usageStreak(day, targetMinutes * 60, ::observed) else 0
    val target = targetText.toIntOrNull()?.takeIf { it in 1..1440 }
    val trackedDays = dailyValues.count { it != null }
    val saved = if (targetMinutes > 0) (targetMinutes * 60 * trackedDays - total).coerceAtLeast(0) else 0

    Text("Heti használat", fontWeight = FontWeight.Bold)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { weekOffset++; chosenDay = day.minusWeeks(weekOffset) }) { Text("Előző hét") }
        OutlinedButton(enabled = weekOffset > 0L, onClick = {
            weekOffset--
            chosenDay = day.minusWeeks(weekOffset)
        }) { Text("Újabb hét") }
    }
    Text("${days.first().format(dayFormat)}–${endDay.format(dayFormat)} · ${duration(total)} használat")
    if (days.any { observed(it.minusWeeks(1)) != null }) {
        Text("Az előző héten ${duration(prior)} használatot mért a Fókusz.")
    }
    dailyValues.zip(days).forEach { (seconds, date) ->
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { chosenDay = date }) { Text(date.format(dayFormat)) }
            Text(seconds?.let(::duration) ?: "Nincs mérés")
        }
        if (seconds != null) LinearProgressIndicator(
            progress = { seconds.toFloat() / max }, Modifier.fillMaxWidth()
        )
    }
    Text("${chosenDay.format(dayFormat)} · Napi előzmények", fontWeight = FontWeight.Bold)
    val daily = fullDays[chosenDay].orEmpty()
    if (daily.isEmpty()) Text("Ezen a napon nincs mentett feloldás.")
    for (entry in daily) {
        val grant = entry.grant
        val start = Instant.ofEpochMilli(grant.startedAt).atZone(zone).format(timeFormat)
        val end = Instant.ofEpochMilli(grant.expiresAt).atZone(zone).format(timeFormat)
        Text("$start · ${grant.appName} · ${grant.minutes} percet kértél", fontWeight = FontWeight.SemiBold)
        Text("${duration(entry.usedSeconds)} használat · ${grant.reason.label} · Eredeti lejárat $end")
    }
    Text("${SocialUnlockStore.cancelled(c, chosenDay)} megszakított megnyitás")
    OutlinedButton(onClick = { detailed = !detailed }) {
        Text(if (detailed) "Bontás elrejtése" else "Indokok és napszakok")
    }
    if (detailed) {
        Text("Mentett kérések: $requested perc · Tényleges használat: ${duration(measured.toInt())}")
        Text("$cancelled megszakított megnyitás a kiválasztott héten")
        for (reason in SocialUnlockReason.entries) {
            val entries = grantRows.filter { it.grant.reason == reason }
            val minutes = entries.sumOf { it.grant.minutes.toLong() }
            val used = entries.sumOf { it.usedSeconds }
            Text("${reason.label} · ${entries.size} alkalom · $minutes perc kért · ${duration(used)} használat")
        }
        val parts = grantRows.groupingBy { Instant.ofEpochMilli(it.grant.startedAt).atZone(zone).hour / 6 }.eachCount()
        Text((0..3).joinToString(" · ") { dayPart -> "${dayPart * 6}–${(dayPart + 1) * 6} óra: ${parts[dayPart] ?: 0}" })
    }

    Text("Személyes használati cél", fontWeight = FontWeight.Bold)
    if (targetMinutes == 0) {
        OutlinedTextField(targetText, { targetText = it.filter(Char::isDigit).take(4) },
            singleLine = true, label = { Text("Napi kiindulási idő percben") })
        OutlinedButton(enabled = target != null, onClick = {
            if (target != null && SocialUnlockStore.setBaseline(c, target)) targetMinutes = target
        }) { Text("Kiindulási idő rögzítése") }
    } else {
        val next = listOf(3, 7, 14, 30, 60, 100).firstOrNull { it > streak }
        Text("Napi kiindulási idő: $targetMinutes perc · Sorozat: $streak nap")
        Text("A sorozat 14 nap mérés nélküli kihagyás után nullázódik.")
        if (next != null) Text("A $next napos jelvényig még ${next - streak} nap van.")
        Text("Megtakarított idő: ${duration(saved)}. A Fókusz a mért napokra jutó kiindulási időből vonja ki a heti használatot.")
    }
    OutlinedButton(onClick = {
        preview = createSummaryImage(days.first().format(dayFormat), endDay.format(dayFormat),
            total, saved.takeIf { targetMinutes > 0 }, streak.takeIf { targetMinutes > 0 })
    }) { Text("Heti eredménykártya előnézete") }
    if (feedback.isNotBlank()) Text(feedback)
    preview?.let { bitmap ->
        AlertDialog(onDismissRequest = { preview = null },
            title = { Text("Heti eredménykártya") },
            text = { Image(bitmap.asImageBitmap(), contentDescription = "A heti használat és a személyes cél eredménye") },
            confirmButton = {
                OutlinedButton(onClick = {
                    feedback = if (shareSummary(c, bitmap)) "" else "A kép megosztása most nem sikerült."
                    preview = null
                }) { Text("Kép megosztása") }
            },
            dismissButton = { OutlinedButton(onClick = { preview = null }) { Text("Bezárás") } })
    }
}

private fun createSummaryImage(start: String, end: String, total: Int, saved: Int?, streak: Int?): Bitmap {
    val image = Bitmap.createBitmap(1000, 760, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(image)
    canvas.drawColor(Color.rgb(11, 17, 32))
    val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    fun draw(text: String, y: Float, size: Float, color: Int, bold: Boolean = false) {
        paint.color = color
        paint.textSize = size
        paint.typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        canvas.drawText(text, 84f, y, paint)
    }
    val mint = Color.rgb(101, 226, 200)
    val white = Color.rgb(246, 248, 255)
    draw("FÓKUSZ", 138f, 54f, mint, true)
    draw("Heti képernyőidő · $start–$end", 218f, 40f, white)
    draw(duration(total), 391f, 90f, white, true)
    if (saved != null) draw("${duration(saved)} megtakarítva", 530f, 47f, mint, true)
    if (streak != null) draw("$streak napos sorozat", 621f, 43f, white)
    return image
}

private fun shareSummary(c: Context, bitmap: Bitmap): Boolean = runCatching {
    val directory = File(c.cacheDir, "social-share").apply { mkdirs() }
    val file = File(directory, "Fokusz-heti-eredmeny.png")
    file.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
    val uri = FileProvider.getUriForFile(c, "${c.packageName}.quiz_camera", file)
    val share = Intent(Intent.ACTION_SEND).setType("image/png")
        .putExtra(Intent.EXTRA_STREAM, uri)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    share.clipData = ClipData.newUri(c.contentResolver, "Heti eredménykártya", uri)
    c.startActivity(Intent.createChooser(share, "Heti eredménykártya"))
}.isSuccess
