package hu.fokusz

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat

/** Android újraindításakor a feladatadatok megmaradnak.
 * Az automatikus háttérvédelem visszaindítása csak felhasználói engedéllyel,
 * a rendszer által engedélyezett feltételek mellett történik. */
class FokuszBootReceiver:BroadcastReceiver() {
    override fun onReceive(context:Context,intent:Intent?) {
        if(intent?.action != Intent.ACTION_BOOT_COMPLETED) return
        if(!FocusPrefs.autoRestart(context)) return
        val allowed=try {
            UsageGuard.allowed(context)&&Settings.canDrawOverlays(context)
        } catch (_:Exception) {false}
        if(allowed) {
            try {
                ContextCompat.startForegroundService(context,
                    Intent(context,FocusService::class.java))
                return
            } catch (_:Exception) {}
        }
        // Biztonságos helyreállítás: értesítjük a felhasználót, nem állítunk
        // olyasmit, hogy működik a védelem, ha az Android megtiltotta.
        try {
            val nm=context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val channel="fokusz-restore"
            nm.createNotificationChannel(NotificationChannel(channel,
                "Fókusz újraindítás",NotificationManager.IMPORTANCE_DEFAULT))
            val pi=PendingIntent.getActivity(context,15,Intent(context,MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            nm.notify(15,Notification.Builder(context,channel)
                .setSmallIcon(android.R.drawable.ic_lock_idle_lock)
                .setContentTitle("Fókusz: védelem újraindítása")
                .setContentText("Az Android leállította a háttérvédelmet. Érintsd meg az újraindításhoz.")
                .setContentIntent(pi).setAutoCancel(true).build())
        } catch (_:Exception) {}
    }
}
