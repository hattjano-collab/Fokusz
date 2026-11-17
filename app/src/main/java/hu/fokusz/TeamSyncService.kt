package hu.fokusz

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper

/** A csapatszinkron külön, látható szolgáltatás. A gazdának elérhetőnek kell
 * lennie az új jóváírások és idő-kölcsönzések elfogadásához.
 */
class TeamSyncService:Service() {
    companion object { @Volatile var isRunning=false }
    private val handler=Handler(Looper.getMainLooper())
    private val refresh=object:Runnable {
        override fun run() {
            if(!TeamStore.active(this@TeamSyncService)) {
                stopSelf();return
            }
            try {
                TeamNearby.pulse(this@TeamSyncService)
            }catch(_:Exception){}
            handler.postDelayed(this,3000L)
        }
    }
    override fun onCreate() {
        super.onCreate()
        val manager=getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(NotificationChannel("fokusz-teams",
            "Fókusz – csapat a közelben",NotificationManager.IMPORTANCE_LOW))
        val open=Intent(this,MainActivity::class.java)
            .putExtra("focus_tab",9)
        val pi=PendingIntent.getActivity(this,29,open,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val note=Notification.Builder(this,"fokusz-teams")
            .setSmallIcon(android.R.drawable.ic_menu_share)
            .setContentTitle("👥 Fókusz – "+TeamStore.name(this))
            .setContentText("Közeli csapatszinkron aktív. Koppints a csapat megnyitásához.")
            .setContentIntent(pi).setOngoing(true).build()
        startForeground(29,note)
        isRunning=true
        TeamNearby.start(this)
        handler.post(refresh)
    }
    override fun onStartCommand(intent:Intent?,flags:Int,startId:Int):Int {
        if(intent?.action=="hu.fokusz.STOP_TEAM") {
            stopSelf();return START_NOT_STICKY
        }
        if(!TeamStore.active(this)) {
            stopSelf();return START_NOT_STICKY
        }
        if(!TeamNearby.running)TeamNearby.start(this)
        return START_STICKY
    }
    override fun onDestroy() {
        handler.removeCallbacks(refresh)
        TeamNearby.stop()
        isRunning=false
        super.onDestroy()
    }
    override fun onBind(intent:Intent?):IBinder?=null
}
