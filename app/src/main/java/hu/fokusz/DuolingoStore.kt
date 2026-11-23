package hu.fokusz

import android.Manifest
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

data class DuoCheck(val success:Boolean,val verifiedToday:Boolean,
    val streak:Int,val todaysXp:Int,val message:String,val paid:Int=0)

/** Nem hivatalos, read-only Duolingo nyilvános profil API.
 * Nem kérünk jelszót, és csak a napi XP vagy a napi XP-cél teljesülése alapján
 * írunk jóvá jutalmat. Egy publikus felhasználónév nem hiteles fiókbejelentkezés.
 */
object DuolingoStore {
    private fun sp(c:Context)=c.getSharedPreferences("fokusz_duolingo_v1",Context.MODE_PRIVATE)
    fun linked(c:Context)=sp(c).getString("username","")?.isNotBlank()==true
    fun username(c:Context)=sp(c).getString("username","") ?: ""
    fun streak(c:Context)=sp(c).getInt("lastStreak",0)
    fun todayXp(c: Context): Int = sp(c).let {
        if (it.getString("xpDay", "") == LocalDate.now().toString()) it.getInt("todayXp", 0) else 0
    }
    fun todayFokuszXp(c: Context) = ProgressStore.receiptXp(c, "duolingo_${LocalDate.now()}")
    fun lastMessage(c:Context)=sp(c).getString("lastMessage","Napi teljesítés ellenőrzése.") ?: ""
    fun rewardedToday(c:Context)=sp(c).getString("rewardDay","")==LocalDate.now().toString()
    fun notifications(c:Context)=sp(c).getBoolean("reminders",true)
    fun setNotifications(c:Context,enabled:Boolean) {
        sp(c).edit().putBoolean("reminders",enabled).commit()
        scheduleReminders(c)
    }
    fun unlink(c:Context) {
        sp(c).edit().remove("username").remove("userId")
            .remove("lastMessage").commit()
        scheduleReminders(c)
    }

    private fun request(url:String):String {
        val http=URL(url).openConnection() as HttpURLConnection
        http.connectTimeout=8000
        http.readTimeout=12000
        http.setRequestProperty("Accept","application/json")
        http.setRequestProperty("User-Agent","FokuszAndroid/3.1 (daily language practice)")
        try {
            if(http.responseCode !in 200..299)throw IllegalStateException(
                "A Duolingo nem elérhető (HTTP "+http.responseCode+").")
            return http.inputStream.bufferedReader().use{it.readText().take(1200000)}
        } finally{http.disconnect()}
    }
    private fun lookup(name:String):JSONObject {
        val url="https://www.duolingo.com/2017-06-30/users?username="+
            URLEncoder.encode(name,"UTF-8")
        val obj=JSONObject(request(url))
        val users=obj.optJSONArray("users")
        val user=users?.optJSONObject(0) ?: throw IllegalStateException(
            "Nem található a Duolingo nyilvános profilja.")
        if(!user.optString("username","").equals(name,true))
            throw IllegalStateException("A megadott névvel nem található profil.")
        return user
    }

    suspend fun connect(c:Context,name:String):DuoCheck=withContext(Dispatchers.IO) {
        val user=name.trim()
        if(!user.matches(Regex("[A-Za-z0-9_.-]{2,40}")))
            return@withContext DuoCheck(false,false,0,0,
                "2–40 karakteres Duolingo-felhasználónevet adj meg.")
        try {
            val profile=lookup(user)
            val id=profile.optLong("id",0L)
            if(id<=0)throw IllegalStateException("Hiányzik a nyilvános azonosító.")
            val saved=sp(c)
            val editor = saved.edit().putString("username", user).putLong("userId", id)
            if (saved.getLong("userId", 0L) != id) {
                editor.putString("xpDay", LocalDate.now().toString()).putInt("todayXp", 0)
                    .putLong("observationAccount", id)
                    .putInt("observedTotal", profile.optInt("totalXp", -1))
                    .putInt("lastStreak", profile.optInt("streak", 0))
            }
            editor.commit()
            scheduleReminders(c)
            DuoCheck(true,false,streak(c),0,
                "A Duolingo-profil mentve. A napi XP ellenőrzése automatikusan elindul.")
        } catch(e:Exception) {
            DuoCheck(false,false,0,0,
                "A Duolingo-fiókot nem sikerült összekötni: "+
                    (e.localizedMessage ?: "ismeretlen hiba"))
        }
    }

    /** A képernyőidő-jutalom egyszeri, az igazolt XP növekedését napközben is követjük. */
    @Synchronized private fun record(
        c: Context, id: Long, profile: JSONObject, providerDay: LocalDate, zone: ZoneId, summaryXp: Int
    ): DuoCheck {
        val prefs = sp(c)
        if (prefs.getLong("userId", 0L) != id) {
            return DuoCheck(false, false, streak(c), todayXp(c), "Megváltozott a mentett profil. Frissíts újra.")
        }
        val day = LocalDate.now()
        val key = day.toString()
        val sameObservation = prefs.getString("xpDay", "") == key &&
            prefs.getLong("observationAccount", 0L) == id
        val rawStreak = maxOf(profile.optInt("streak", 0),
            profile.optJSONObject("streakData")?.optJSONObject("currentStreak")?.optInt("length", 0) ?: 0)
        val total = profile.optInt("totalXp", -1)
        val baseline = if (sameObservation) prefs.getInt("observedTotal", -1) else -1
        val deltaXp = if (providerDay == day && baseline >= 0 && total >= baseline) total - baseline else 0
        val profileXp = if (providerDay == day) profile.optInt("xpToday", 0) else 0
        val xp = maxOf(summaryXp, profileXp, deltaXp, if (sameObservation) prefs.getInt("todayXp", 0) else 0)
        val newBaseline = when {
            baseline >= 0 -> baseline
            total >= 0 && xp > 0 -> (total - xp).coerceAtLeast(0)
            else -> total
        }
        val completed = providerDay == day && (xp > 0 || profile.optBoolean("xpGoalMetToday", false) ||
            DuoEvidence.verifiedStreak(profile, providerDay, zone) ||
            (sameObservation && rawStreak > prefs.getInt("lastStreak", rawStreak)))
        val xpAdded = if (completed) ProgressStore.awardXp(c, if (xp > 0) xp else 20, "duolingo_$key") else 0
        val paid = if (completed && !rewardedToday(c)) {
            val credited = Store.claimStudyReward(c, 180, "duolingo") ?: 0
            prefs.edit().putString("rewardDay", key).commit()
            ProgressStore.earned(c, credited)
            credited
        } else 0
        val message = when {
            completed && (paid > 0 || xpAdded > 0) -> buildString {
                append("Mai Duolingo-gyakorlás igazolva.")
                if (xpAdded > 0) append(" +$xpAdded Fókusz XP.")
                if (paid > 0) append(" +${duration(paid)} képernyőidő.")
            }
            completed || rewardedToday(c) -> "A mai Duolingo-jutalmat már megkaptad."
            else -> "Még nincs igazolt mai gyakorlás. Frissíts egy lecke után, hogy mérhető legyen az XP-változás."
        }
        prefs.edit().putString("xpDay", key).putLong("observationAccount", id)
            .putInt("observedTotal", newBaseline).putInt("lastStreak", rawStreak).putInt("todayXp", xp)
            .putString("lastMessage", message).commit()
        return DuoCheck(true, completed, rawStreak, xp, message, paid)
    }

    suspend fun check(c: Context): DuoCheck = withContext(Dispatchers.IO) {
        if (!linked(c)) return@withContext DuoCheck(false, false, 0, 0,
            "A Duolingo-fülön add meg a felhasználóneved.")
        try {
            val name = username(c)
            val profile = lookup(name)
            val id = profile.optLong("id", sp(c).getLong("userId", 0L))
            val zone = runCatching { ZoneId.of(profile.optString("timezone", ZoneId.systemDefault().id)) }
                .getOrDefault(ZoneId.systemDefault())
            val today = LocalDate.now(zone)
            val summary = if (id > 0 && today == LocalDate.now()) {
                try {
                    val address = "https://www.duolingo.com/2017-06-30/users/$id/xp_summaries" +
                        "?startDate=${today.minusDays(1)}&endDate=${today.plusDays(1)}&timezone=" +
                        URLEncoder.encode(zone.id, "UTF-8")
                    DuoEvidence.dailyXp(request(address), today, zone)
                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    throw cancelled
                } catch (_: Exception) { 0 }
            } else 0
            record(c, id, profile, today, zone, summary)
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            val message = "Sikertelen Duolingo-ellenőrzés: ${failure.localizedMessage ?: "a nyilvános profil nem válaszol"}"
            sp(c).edit().putString("lastMessage", message).apply()
            DuoCheck(false, false, streak(c), todayXp(c), message)
        }
    }
    private val reminderHours=listOf(17 to 0,20 to 30,22 to 30)

    /** Nem pontos Android-riasztások, néhány perc késés előfordulhat. */
    fun scheduleReminders(c:Context) {
        val alarm=c.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        reminderHours.forEachIndexed { idx,(hour,minute) ->
            val intent=Intent(c,DuolingoReminderReceiver::class.java)
                .setAction("hu.fokusz.DUOLINGO_REMINDER")
                .putExtra("reminder_slot",idx)
            val pi=PendingIntent.getBroadcast(c,710+idx,intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            alarm.cancel(pi)
            if(!linked(c)||!notifications(c))return@forEachIndexed
            var whenAt=LocalDate.now().atTime(hour,minute)
            if(!whenAt.isAfter(LocalDateTime.now()))whenAt=whenAt.plusDays(1)
            try {
                alarm.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP,
                    whenAt.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(),pi)
            }catch (_:Exception){}
        }
    }

    fun scheduleOne(c:Context,slot:Int) {
        if(!linked(c)||!notifications(c)||slot !in reminderHours.indices)return
        val (hour,minute)=reminderHours[slot]
        val intent=Intent(c,DuolingoReminderReceiver::class.java)
            .setAction("hu.fokusz.DUOLINGO_REMINDER")
            .putExtra("reminder_slot",slot)
        val pi=PendingIntent.getBroadcast(c,710+slot,intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val alarm=c.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val next=LocalDate.now().plusDays(1).atTime(hour,minute)
        try {
            alarm.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP,
                next.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(),pi)
        }catch (_:Exception){}
    }
}

class DuolingoReminderReceiver:BroadcastReceiver() {
    override fun onReceive(context:Context,intent:Intent?) {
        val slot=intent?.getIntExtra("reminder_slot",0) ?: 0
        DuolingoStore.scheduleOne(context,slot)
        if(!DuolingoStore.linked(context)||!DuolingoStore.notifications(context)
            ||DuolingoStore.rewardedToday(context))return
        if(Build.VERSION.SDK_INT>=33 &&
            ContextCompat.checkSelfPermission(context,Manifest.permission.POST_NOTIFICATIONS)
                !=PackageManager.PERMISSION_GRANTED)return
        try {
            val manager=context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val channel="fokusz-duolingo-reminders"
            manager.createNotificationChannel(NotificationChannel(channel,
                "Duolingo napi emlékeztetők",NotificationManager.IMPORTANCE_DEFAULT))
            val open=Intent(context,MainActivity::class.java)
                .putExtra("focus_tab",8)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
            val pi=PendingIntent.getActivity(context,81,open,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val note=Notification.Builder(context,channel)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle("🦉 Duolingo-streak: mai lecke!")
                .setContentText("Még nincs igazolt mai teljesítés. Duolingo leckéért +3 perc Fókusz-idő.")
                .setAutoCancel(true)
                .setContentIntent(pi)
                .build()
            manager.notify(830+slot,note)
        }catch (_:Exception){}
    }
}
