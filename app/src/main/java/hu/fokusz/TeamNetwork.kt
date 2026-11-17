package hu.fokusz

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.os.SystemClock
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URLEncoder
import java.net.URLDecoder

/** A telefonok egy közös Wi-Fi-n egymás között beszélgetnek.
 * A gazda telefonja az egyetlen, ami a közös egyenleget módosítja.
 * Az adatcsatorna LAN-on megy, nincs külső adatbázis vagy szerver.
 */
object TeamWire {
    const val PORT=45817
    private const val READ_TIMEOUT=3500
    fun ip():String {
        val all=try{NetworkInterface.getNetworkInterfaces().toList()}catch(_:Exception){emptyList()}
        val choices=all.filter{it.isUp && !it.isLoopback}
        val addresses=choices.flatMap { iface ->
            iface.inetAddresses.toList().filterIsInstance<Inet4Address>()
                .filter{!it.isLoopbackAddress && it.isSiteLocalAddress}
                .map{iface.name to it.hostAddress}
        }
        return addresses.firstOrNull { (name,_) ->
            name.startsWith("wlan") || name.startsWith("ap") || name.startsWith("swlan")
        }?.second ?: addresses.firstOrNull()?.second ?: ""
    }
    fun invite(c:Context):String? {
        val host=ip()
        if(!TeamPrefs.host(c)||host.isBlank())return null
        fun e(s:String)=URLEncoder.encode(s,"UTF-8")
        return "fokusz-team://join?version=1&ip=$host&port=$PORT"+
            "&group="+e(TeamPrefs.groupId(c))+
            "&secret="+e(TeamPrefs.secret(c))+
            "&name="+e(TeamPrefs.groupName(c))
    }
    private fun decode(url:String):Map<String,String> {
        if(!url.startsWith("fokusz-team://join?") || url.length>1000)
            throw IllegalArgumentException("Nem Fókusz csapatmeghívó.")
        return url.substringAfter('?').split('&').mapNotNull { token ->
            val p=token.split('=',limit=2)
            if(p.size!=2)null else p[0] to URLDecoder.decode(p[1],"UTF-8")
        }.toMap()
    }
    /** A csatlakozás után azonnal kommunikálunk a gazda telefonnal. */
    suspend fun join(c:Context,payload:String):String =
        kotlinx.coroutines.withContext(Dispatchers.IO) {
            if(TeamPrefs.linked(c))return@withContext "Már egy csapat tagja vagy. Előbb lépj ki."
            try{
                val args=decode(payload)
                val host=args["ip"] ?: throw IllegalArgumentException("Hiányzik az IP-cím.")
                val group=args["group"] ?: ""
                val token=args["secret"] ?: ""
                val label=args["name"] ?: "Csapat"
                val port=args["port"]?.toIntOrNull() ?: PORT
                if(port!=PORT || !host.matches(Regex("(\\d{1,3}\\.){3}\\d{1,3}")) ||
                    group.length !in 10..64 || token.length !in 20..80)
                    throw IllegalArgumentException("Hibás csapatmeghívó.")
                TeamPrefs.setClient(c,group,host,token,label)
                val result=sync(c)
                if(!result.optBoolean("ok")) {
                    TeamPrefs.leave(c)
                    "Nem sikerült csatlakozni: "+result.optString("error","nincs kapcsolat")
                }else {
                    start(c)
                    "✓ Csatlakoztál a csapathoz: "+label
                }
            }catch(e:Exception){"Nem sikerült csatlakozni: "+(e.localizedMessage ?: "hibás QR-kód")}
        }

    fun send(c:Context,request:JSONObject):JSONObject {
        if(TeamPrefs.host(c))return TeamLedger.handle(c,request)
        val host=TeamPrefs.hostAddress(c)
        if(!TeamPrefs.client(c) || host.isBlank())
            return JSONObject().put("ok",false).put("error","Nincs csapatkapcsolat.")
        return try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(host,PORT),READ_TIMEOUT)
                socket.soTimeout=READ_TIMEOUT
                socket.getOutputStream().bufferedWriter(Charsets.UTF_8).use { writer ->
                    // Nem zárjuk a socketet a válasz olvasása előtt.
                    writer.write(request.toString())
                    writer.write("\n")
                    writer.flush()
                    val line=socket.getInputStream().bufferedReader(Charsets.UTF_8).readLine()
                        ?:throw IllegalStateException("Nincs válasz a gazda telefonjától.")
                    if(line.length>200000)throw IllegalStateException("Túl hosszú csapatüzenet.")
                    JSONObject(line)
                }
            }
        }catch(e:Exception) {
            TeamPrefs.error(c,"Nincs helyi Wi-Fi kapcsolat a csapatgazdával.")
            JSONObject().put("ok",false).put("error",
                e.localizedMessage?.take(150) ?: "Nincs kapcsolat.")
        }
    }
    fun sync(c:Context,op:String="sync",seconds:Int=0,removeId:String=""):JSONObject {
        if(!TeamPrefs.linked(c))return JSONObject().put("ok",false)
        val response=send(c,TeamLedger.report(c,op,seconds,removeId))
        if(response.optBoolean("ok")) {
            response.optJSONObject("state")?.let{TeamPrefs.cache(c,it)}
        }
        return response
    }
    fun reserve(c:Context,seconds:Int=20):Int {
        if(!TeamPrefs.linked(c))return 0
        val response=sync(c,"reserve",seconds.coerceIn(1,30))
        val granted=response.optInt("allowed",0).coerceIn(0,30)
        if(granted>0)TeamPrefs.addPrepaid(c,granted)
        return granted
    }
    fun start(c:Context) {
        if(!TeamPrefs.linked(c))return
        try{
            ContextCompat.startForegroundService(c,Intent(c,TeamService::class.java))
        }catch(_:Exception){
            TeamPrefs.error(c,"A háttérszinkronizálás indítását az Android blokkolta.")
        }
    }
}

class TeamService:Service() {
    companion object {
        @Volatile var running=false
        @Volatile var hosting=false
    }
    private val work=CoroutineScope(SupervisorJob()+Dispatchers.IO)
    private var server:ServerSocket?=null
    private var hostJob:Job?=null
    private var syncJob:Job?=null
    override fun onCreate(){
        super.onCreate()
        val channel="fokusz-team-sync"
        val nm=getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel(channel,"Fókusz csapatkapcsolat",
            NotificationManager.IMPORTANCE_LOW))
        val open=PendingIntent.getActivity(this,42,
            Intent(this,MainActivity::class.java).putExtra("focus_tab",9),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        startForeground(42,Notification.Builder(this,channel)
            .setSmallIcon(android.R.drawable.ic_menu_share)
            .setContentTitle("Fókusz csapat")
            .setContentText("Helyi Wi-Fi szinkronizálás")
            .setContentIntent(open).setOngoing(true).build())
        running=true
    }
    override fun onStartCommand(intent:Intent?,flags:Int,startId:Int):Int {
        if(!TeamPrefs.linked(this)){stopSelf();return START_NOT_STICKY}
        if(TeamPrefs.host(this) && (hostJob==null || hostJob?.isActive==false)) {
            hostJob=work.launch {
                try {
                    ServerSocket().use { socket ->
                        socket.reuseAddress=true
                        socket.bind(InetSocketAddress(TeamWire.PORT))
                        server=socket
                        hosting=true
                        while(isActive) {
                            val peer=socket.accept()
                            launch {
                                peer.use {
                                    try{
                                        it.soTimeout=3500
                                        val reader=it.getInputStream().bufferedReader(Charsets.UTF_8)
                                        val request=reader.readLine() ?: ""
                                        val answer=if(request.length in 2..32000)
                                            TeamLedger.handle(this@TeamService,JSONObject(request))
                                        else JSONObject().put("ok",false)
                                            .put("error","Érvénytelen üzenet.")
                                        val writer=it.getOutputStream().bufferedWriter(Charsets.UTF_8)
                                        writer.write(answer.toString())
                                        writer.newLine();writer.flush()
                                    }catch (_:Exception){}
                                }
                            }
                        }
                    }
                }catch (e:Exception){
                    TeamPrefs.error(this@TeamService,"A csapatgazda helyi portja nem indult: "+
                        (e.localizedMessage ?: "hiba"))
                }finally{hosting=false;server=null}
            }
        }
        if(syncJob==null || syncJob?.isActive==false) {
            syncJob=work.launch {
                while(isActive && TeamPrefs.linked(this@TeamService)) {
                    TeamWire.sync(this@TeamService)
                    delay(5000)
                }
            }
        }
        return START_STICKY
    }
    override fun onDestroy(){
        running=false;hosting=false
        try{server?.close()}catch (_:Exception){}
        work.cancel()
        super.onDestroy()
    }
    override fun onBind(intent:Intent?):IBinder?=null
}