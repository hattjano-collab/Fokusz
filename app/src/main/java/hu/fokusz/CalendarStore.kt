package hu.fokusz

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

data class FocusEvent(val id: Long, val title: String, val time: LocalDateTime, val minutes: Int, val source: String)

object CalendarStore {
    private fun sp(c: Context) = c.getSharedPreferences("fokusz_calendar_v1",0)
    private fun raw(c: Context) = sp(c).getString("events","[]") ?: "[]"
    private fun localEvents(c: Context): List<FocusEvent> {
        val output = mutableListOf<FocusEvent>()
        try {
            val arr = JSONArray(raw(c))
            for(i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val dt = try { LocalDateTime.parse(o.getString("time")) } catch (_: Exception) {continue}
                output.add(FocusEvent(o.optLong("id"),o.optString("title"),dt,
                    o.optInt("minutes",30).coerceIn(5,600),"Fókusz"))
            }
        } catch (_: Exception) {}
        return output
    }
    @Synchronized fun add(c: Context,title:String,whenAt:LocalDateTime,minutes:Int):Boolean {
        val name=title.trim()
        if(name.length !in 3..90 || minutes !in 5..600) return false
        val arr=try { JSONArray(raw(c)) } catch (_:Exception) {JSONArray()}
        val obj=JSONObject().put("id",System.currentTimeMillis())
            .put("title",name).put("time",whenAt.toString()).put("minutes",minutes)
        arr.put(obj)
        sp(c).edit().putString("events",arr.toString()).commit()
        return true
    }
    @Synchronized fun remove(c:Context,id:Long) {
        val arr=JSONArray()
        for(e in localEvents(c).filter { it.id!=id }) {
            arr.put(JSONObject().put("id",e.id).put("title",e.title)
                .put("time",e.time.toString()).put("minutes",e.minutes))
        }
        sp(c).edit().putString("events",arr.toString()).apply()
    }
    fun hasDevicePermission(c:Context) =
        ContextCompat.checkSelfPermission(c,Manifest.permission.READ_CALENDAR)==PackageManager.PERMISSION_GRANTED

    /** Android rendszer-naptár olvasása csak külön engedély után. Nincs írás/szinkron. */
    fun deviceEvents(c: Context, start:LocalDate,end:LocalDate):List<FocusEvent> {
        if(!hasDevicePermission(c)) return emptyList()
        val zone=ZoneId.systemDefault()
        val min=start.atStartOfDay(zone).toInstant().toEpochMilli()
        val max=end.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val uri=CalendarContract.Instances.CONTENT_URI.buildUpon()
        android.content.ContentUris.appendId(uri,min)
        android.content.ContentUris.appendId(uri,max)
        val out=mutableListOf<FocusEvent>()
        try {
            val columns=arrayOf(CalendarContract.Instances.EVENT_ID,
                CalendarContract.Instances.TITLE,CalendarContract.Instances.BEGIN,
                CalendarContract.Instances.END)
            c.contentResolver.query(uri.build(),columns,null,null,
                CalendarContract.Instances.BEGIN+" ASC")?.use { cursor ->
                while(cursor.moveToNext() && out.size<150) {
                    val name=cursor.getString(1) ?: continue
                    val begin=cursor.getLong(2)
                    val finish=cursor.getLong(3)
                    val dt=Instant.ofEpochMilli(begin).atZone(zone).toLocalDateTime()
                    val duration=((finish-begin)/60000).toInt().coerceIn(5,600)
                    out.add(FocusEvent(cursor.getLong(0),name,dt,duration,"Telefon naptára"))
                }
            }
        } catch (_:SecurityException) {} catch (_:Exception) {}
        return out
    }
    fun events(c:Context,start:LocalDate,end:LocalDate): List<FocusEvent> =
        (localEvents(c).filter { !it.time.toLocalDate().isBefore(start) &&
            !it.time.toLocalDate().isAfter(end) } +
            deviceEvents(c,start,end)).sortedBy { it.time }

    fun upcoming(c:Context):List<FocusEvent> {
        val now=LocalDateTime.now()
        return events(c,LocalDate.now(),LocalDate.now().plusDays(7))
            .filter { it.time.isAfter(now.minusMinutes(1)) }.take(8)
    }
    fun nextEventMinutes(c:Context):Long? {
        val n=upcoming(c).firstOrNull() ?:return null
        return java.time.Duration.between(LocalDateTime.now(),n.time).toMinutes().coerceAtLeast(0)
    }
    fun reminderText(c:Context):String? {
        val e=upcoming(c).firstOrNull() ?:return null
        val mins=java.time.Duration.between(LocalDateTime.now(),e.time).toMinutes()
        return if(mins in 0..15) "Naptár: "+e.title.take(28)+" • "+mins+" perc múlva" else null
    }
    fun busySoon(c: Context): Boolean = (nextEventMinutes(c) ?:999L)<35L
    fun schoolEventToday(c:Context):Boolean =
        events(c,LocalDate.now(),LocalDate.now()).any {
            val t=it.title.lowercase()
            listOf("iskola","óra","dolgozat","felelés","tanulás","school","exam").any { word ->word in t}
        }
}
