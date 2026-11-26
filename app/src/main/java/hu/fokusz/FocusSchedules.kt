package hu.fokusz

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import org.json.JSONArray
import org.json.JSONObject
import java.time.DayOfWeek
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.UUID

internal data class FocusSchedule(val id: String, val days: Set<Int>, val start: LocalTime, val end: LocalTime) {
    fun endsAt(now: LocalDateTime): LocalDateTime? {
        val today = now.toLocalDate()
        val day = now.dayOfWeek.value
        val time = now.toLocalTime()
        if (end > start) {
            return if (day in days && time >= start && time < end) today.atTime(end) else null
        }
        return when {
            day in days && time >= start -> today.plusDays(1).atTime(end)
            now.dayOfWeek.minus(1).value in days && time < end -> today.atTime(end)
            else -> null
        }
    }
}

internal object FocusSchedules {
    private fun prefs(c: Context) = c.getSharedPreferences("fokusz_focus_schedules", Context.MODE_PRIVATE)

    fun all(c: Context): List<FocusSchedule> = runCatching {
        val elements = JSONArray(prefs(c).getString("schedules", "[]"))
        List(elements.length()) { i ->
            val row = elements.getJSONObject(i)
            val days = row.getJSONArray("days")
            FocusSchedule(row.getString("id"), (0 until days.length()).map(days::getInt).toSet(),
                LocalTime.parse(row.getString("start")), LocalTime.parse(row.getString("end")))
        }
    }.getOrDefault(emptyList())

    private fun endsAt(c: Context, now: LocalDateTime): LocalDateTime? =
        all(c).mapNotNull { it.endsAt(now) }.maxOrNull()

    fun secondsLeft(c: Context, now: LocalDateTime = LocalDateTime.now()): Int {
        val zone = ZoneId.systemDefault()
        return endsAt(c, now)?.let { end ->
            val remaining = ChronoUnit.MILLIS.between(now.atZone(zone).toInstant(), end.atZone(zone).toInstant())
            (remaining.coerceAtLeast(0L) + 999L).div(1000L).toInt()
        } ?: 0
    }

    fun endEpoch(c: Context, now: LocalDateTime = LocalDateTime.now()): Long =
        endsAt(c, now)?.atZone(ZoneId.systemDefault())?.toEpochSecond() ?: 0L

    fun allowsException(c: Context, pkg: String): Boolean =
        prefs(c).getLong("exception_$pkg", 0L) > System.currentTimeMillis()

    fun acceptException(c: Context, pkg: String, windowEnd: Int, minutes: Int): Boolean {
        if (endEpoch(c) != windowEnd.toLong() || minutes !in 1..120) return true
        val deadline = minOf(windowEnd * 1000L, System.currentTimeMillis() + minutes * 60_000L)
        return prefs(c).edit().putLong("exception_$pkg", deadline).commit()
    }

    fun save(c: Context, list: List<FocusSchedule>): Boolean {
        val rows = list.map { schedule ->
            JSONObject().put("id", schedule.id).put("days", JSONArray(schedule.days.sorted()))
                .put("start", schedule.start.toString()).put("end", schedule.end.toString())
        }
        return prefs(c).edit().putString("schedules", JSONArray(rows).toString()).commit()
    }
}

private val dayNames = listOf("H", "K", "Sze", "Cs", "P", "Szo", "V")
private fun timeField(value: String) = value.takeIf { it.matches(Regex("[0-2][0-9]:[0-5][0-9]")) }
    ?.let { runCatching { LocalTime.parse(it) }.getOrNull() }

@Composable internal fun FocusSchedulesPanel() {
    val c = LocalContext.current
    var expanded by remember { mutableStateOf(false) }
    var items by remember { mutableStateOf(FocusSchedules.all(c)) }
    var days by remember { mutableStateOf(setOf(1, 2, 3, 4, 5)) }
    var start by remember { mutableStateOf("08:00") }
    var end by remember { mutableStateOf("14:00") }
    val begins = timeField(start)
    val ends = timeField(end)
    FeatureBox("Ismétlődő fókuszsávok") {
        OutlinedButton(onClick = { expanded = !expanded }) {
            Text(if (expanded) "Fókuszsávok elrejtése" else "Fókuszsávok beállítása")
        }
        if (expanded) {
            Text("A megadott napokon a fókuszmód alkalmazásszabályai lépnek életbe.")
            for (item in items) {
                Text("${item.days.sorted().joinToString(", ") { dayNames[it - 1] }} · ${item.start}–${item.end}")
                OutlinedButton(onClick = {
                    val remaining = items.filterNot { it.id == item.id }
                    if (FocusSchedules.save(c, remaining)) items = remaining
                }) { Text("Fókuszsáv törlése") }
            }
            for (row in DayOfWeek.entries.chunked(4)) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (day in row) FilterChip(selected = day.value in days,
                        onClick = { days = if (day.value in days) days - day.value else days + day.value },
                        label = { Text(dayNames[day.value - 1]) })
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(start, { start = it.take(5) }, Modifier.weight(1f),
                    singleLine = true, label = { Text("Kezdés") })
                OutlinedTextField(end, { end = it.take(5) }, Modifier.weight(1f),
                    singleLine = true, label = { Text("Befejezés") })
            }
            OutlinedButton(enabled = begins != null && ends != null && begins != ends && days.isNotEmpty() && items.size < 20,
                onClick = {
                    val next = items + FocusSchedule(UUID.randomUUID().toString(), days, checkNotNull(begins), checkNotNull(ends))
                    if (FocusSchedules.save(c, next)) items = next
                }) { Text("Ismétlődő fókuszsáv mentése") }
        }
    }
}
