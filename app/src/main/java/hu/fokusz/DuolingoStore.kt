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
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset
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
    fun todayXp(c:Context)=sp(c).getInt("todayXp",0)
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
        http.setRequestProperty("User-Agent","FokuszAndroid/2.4 (daily language streak)")
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
            saved.edit().putString("username",user).putLong("userId",id).commit()
            scheduleReminders(c)
            DuoCheck(true,false,streak(c),0,
                "A Duolingo-profil mentve. A napi XP ellenőrzése automatikusan elindul.")
        } catch(e:Exception) {
            DuoCheck(false,false,0,0,
                "A Duolingo-fiókot nem sikerült összekötni: "+
                    (e.localizedMessage ?: "ismeretlen hiba"))
        }
    }

    /** Egy profil ellenőrzése legfeljebb egyszer írhat jóvá +180 másodpercet naponta. */
    @Synchronized private fun credit(c:Context):Int {
        val s=sp(c)
        val day=LocalDate.now().toString()
        if(s.getString("rewardDay","")==day)return 0
        // A dátumot előbb mentjük, így két párhuzamos hálózati válasz sem dupláz.
        s.edit().putString("rewardDay",day).commit()
        val before=Store.bal(c)
        Store.add(c,180)
        val paid=(Store.bal(c)-before).coerceAtLeast(0)
        ProgressStore.earned(c,paid)
        return paid
    }

    suspend fun check(c:Context):DuoCheck=withContext(Dispatchers.IO) {
        if(!linked(c))return@withContext DuoCheck(false,false,0,0,
            "A Duolingo-fülön add meg a felhasználóneved.")
        try {
            val profile=lookup(username(c))
            val id=profile.optLong("id",sp(c).getLong("userId",0L))
            val streak= maxOf(profile.optInt("streak",0),
                profile.optJSONObject("streakData")?.optJSONObject("currentStreak")
                    ?.optInt("length",0) ?: 0)
            val zone=try {
                ZoneId.of(profile.optString("timezone",ZoneId.systemDefault().id))
            }catch (_:Exception){ZoneId.systemDefault()}
            val today=LocalDate.now(zone)
            var xp=0
            if(id>0) {
                try {
                    val from=today.minusDays(1).toString()
                    val until=today.plusDays(1).toString()
                    val address="https://www.duolingo.com/2017-06-30/users/$id/xp_summaries"+
                        "?startDate=$from&endDate=$until&timezone="+
                        URLEncoder.encode(zone.id,"UTF-8")
                    val response=request(address).trim()
                    val summaries=if(response.startsWith("["))JSONArray(response)
                        else JSONObject(response).optJSONArray("summaries") ?: JSONArray()
                    for(i in 0 until summaries.length()) {
                        val item=summaries.optJSONObject(i) ?: continue
                        val recorded=item.optLong("date",0L)
                        val day=try{
                            Instant.ofEpochSecond(recorded).atZone(ZoneOffset.UTC).toLocalDate()
                        } catch (_:Exception){null}
                        if(day==today && !item.optBoolean("frozen",false))
                            xp+=item.optInt("gainedXp",0).coerceAtLeast(0)
                    }
                } catch (_:Exception) { /* A nyilvános XP-végpont nem mindig elérhető. */ }
            }
            // Az xpGoalMetToday csak akkor számít, ha a profil ezt tényleg visszaadja.
            val hasGoal=profile.optBoolean("xpGoalMetToday",false)
            val completed= (xp>0 || hasGoal) && today==LocalDate.now()
            val paid=if(completed)credit(c)else 0
            val message=when {
                completed && paid>0 -> "✅ Mai Duolingo teljesítve! +"+duration(paid)+
                    " képernyőidő jóváírva."
                completed -> "✅ A mai Duolingo már teljesítve, a napi jutalom felhasználva."
                else -> "⏳ Ma még nem látszik ellenőrzött Duolingo XP. Gyakorolj, majd frissíts!"
            }
            sp(c).edit().putInt("lastStreak",streak).putInt("todayXp",xp)
                .putString("lastMessage",message).putLong("lastChecked",System.currentTimeMillis())
                .commit()
            DuoCheck(true,completed,streak,xp,message,paid)
        } catch(e:Exception){
            val message="Sikertelen Duolingo-ellenőrzés: "+
                (e.localizedMessage ?: "a nem hivatalos API nem válaszol")
            sp(c).edit().putString("lastMessage",message).commit()
            DuoCheck(false,false,streak(c),todayXp(c),message)
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
