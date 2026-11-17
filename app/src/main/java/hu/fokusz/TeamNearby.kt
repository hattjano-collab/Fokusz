package hu.fokusz

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.AdvertisingOptions
import com.google.android.gms.nearby.connection.ConnectionInfo
import com.google.android.gms.nearby.connection.ConnectionLifecycleCallback
import com.google.android.gms.nearby.connection.ConnectionResolution
import com.google.android.gms.nearby.connection.ConnectionsClient
import com.google.android.gms.nearby.connection.DiscoveredEndpointInfo
import com.google.android.gms.nearby.connection.DiscoveryOptions
import com.google.android.gms.nearby.connection.EndpointDiscoveryCallback
import com.google.android.gms.nearby.connection.Payload
import com.google.android.gms.nearby.connection.PayloadCallback
import com.google.android.gms.nearby.connection.PayloadTransferUpdate
import com.google.android.gms.nearby.connection.Strategy
import org.json.JSONObject
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap

/** Star-hálózat: a csoport gazdája hirdet, a tag QR-beolvasás után megkeresi.
 * A Nearby Connections kapcsolatot a Google Play services titkosítja.
 * A csapattitkot a QR kód adja át; csak ezután fogadjuk el a JOIN kérést.
 */
object TeamNearby {
    private const val SERVICE="hu.fokusz.nearby.team.v1"
    private val strategy=Strategy.P2P_STAR
    @Volatile var running=false
        private set
    @Volatile var status="Nem csatlakozik"
        private set
    @Volatile var connected=false
        private set
    private var client:ConnectionsClient?=null
    private var app:Context?=null
    private var guestEndpoint:String?=null
    private val peers=ConcurrentHashMap<String,String>()
    private var pendingDiscovery=false
    private var pendingConnect=false
    private var syncCounter=0

    fun permissions():Array<String> {
        val p=mutableListOf<String>()
        if(Build.VERSION.SDK_INT>=31) {
            p+=Manifest.permission.BLUETOOTH_SCAN
            p+=Manifest.permission.BLUETOOTH_CONNECT
            p+=Manifest.permission.BLUETOOTH_ADVERTISE
            if(Build.VERSION.SDK_INT>=33)p+=Manifest.permission.NEARBY_WIFI_DEVICES
        } else {
            p+=Manifest.permission.ACCESS_FINE_LOCATION
        }
        return p.toTypedArray()
    }
    fun allowed(c:Context):Boolean=permissions().all {
        ContextCompat.checkSelfPermission(c,it)==PackageManager.PERMISSION_GRANTED
    }

    @Synchronized fun start(context:Context) {
        val c=context.applicationContext
        if(!TeamStore.active(c)||!allowed(c)) {
            status="A közelben lévő eszközökhöz engedély szükséges.";return
        }
        if(running)return
        app=c
        client=Nearby.getConnectionsClient(c)
        running=true
        status=if(TeamStore.host(c))"Csapat QR-kódja készen áll, keresés…" else
            "A csapat gazdájának telefonját keresem…"
        if(TeamStore.host(c)) advertise(c) else discover(c)
    }
    @Synchronized fun stop() {
        try{client?.stopAdvertising()}catch(_:Exception){}
        try{client?.stopDiscovery()}catch(_:Exception){}
        try{client?.stopAllEndpoints()}catch(_:Exception){}
        running=false;connected=false;pendingDiscovery=false;pendingConnect=false
        peers.clear();guestEndpoint=null;client=null;app=null
        status="A közelben történő szinkronizálás leállt."
    }
    private val receiver=object:PayloadCallback() {
        override fun onPayloadReceived(endpointId:String,payload:Payload) {
            val bytes=payload.asBytes() ?: return
            if(bytes.size>80_000)return
            val data=try {JSONObject(String(bytes,StandardCharsets.UTF_8))}catch(_:Exception){return}
            val c=app?:return
            val type=data.optString("type")
            if(TeamStore.host(c)) {
                if(type=="join") {
                    if(data.optString("secret")!=TeamStore.secret(c) ||
                        data.optString("team")!=TeamStore.teamId(c)) {
                        client?.disconnectFromEndpoint(endpointId);return
                    }
                    val profile=data.optJSONObject("profile")?:return
                    if(TeamStore.addMember(c,profile)) {
                        peers[endpointId]=profile.optString("id")
                        connected=peers.isNotEmpty()
                        status="Csapat csatlakozott: "+profile.optString("name")
                        sendTo(endpointId,TeamStore.snapshot(c))
                        broadcast(c)
                    }else client?.disconnectFromEndpoint(endpointId)
                    return
                }
                val id=peers[endpointId] ?:return
                when(type) {
                    "report" -> {
                        val profile=data.optJSONObject("profile")?:return
                        if(profile.optString("id")==id) {
                            TeamStore.addMember(c,profile)
                            broadcast(c)
                        }
                    }
                    "task" -> {
                        val event=data.optString("event")
                        if(event.startsWith(id+":")) {
                            TeamStore.addBonus(c,event)
                            sendTo(endpointId,JSONObject().put("type","ack")
                                .put("event",event))
                            broadcast(c)
                        }
                    }
                    "leaseRequest" -> {
                        val number=TeamStore.grant(c,60)
                        if(number>0) {
                            sendTo(endpointId,JSONObject().put("type","lease")
                                .put("seconds",number))
                            broadcast(c)
                        } else {
                            sendTo(endpointId,JSONObject().put("type","lease")
                                .put("seconds",0))
                        }
                    }
                    "boss" -> { TeamStore.claimBoss(c);broadcast(c) }
                    "refresh" -> sendTo(endpointId,TeamStore.snapshot(c))
                }
            } else if(endpointId==guestEndpoint) {
                when(type) {
                    "snapshot" -> {
                        TeamStore.acceptSnapshot(c,data)
                        connected=true
                        status="✓ Szinkronizálva a csoport gazdájával"
                        sendPending(c)
                    }
                    "lease" -> {
                        TeamStore.acceptLease(c,data.optInt("seconds",0))
                        status=if(data.optInt("seconds",0)>0)
                            "✓ Közös idő elérhető" else "A közös időkeret elfogyott."
                    }
                    "ack" -> TeamStore.ack(c,data.optString("event"))
                }
            }
        }
        override fun onPayloadTransferUpdate(endpointId:String,update:PayloadTransferUpdate){}
    }
    private val lifecycle=object:ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId:String,info:ConnectionInfo) {
            client?.acceptConnection(endpointId,receiver)
        }
        override fun onConnectionResult(endpointId:String,result:ConnectionResolution) {
            pendingConnect=false
            val c=app?:return
            if(!result.status.isSuccess) {
                status="Nem sikerült a közeli kapcsolat. Újrapróbálkozom."
                if(!TeamStore.host(c)) {
                    try{client?.stopDiscovery()}catch(_:Exception){}
                    pendingDiscovery=false
                    discover(c)
                }
                return
            }
            if(!TeamStore.host(c)) {
                guestEndpoint=endpointId
                connected=true
                try {client?.stopDiscovery()}catch(_:Exception){}
                pendingDiscovery=false
                sendTo(endpointId,JSONObject().put("type","join")
                    .put("team",TeamStore.teamId(c))
                    .put("secret",TeamStore.secret(c))
                    .put("profile",TeamStore.report(c)))
            } else {
                status="Új telefon csatlakozik, QR-kulcs ellenőrzése…"
            }
        }
        override fun onDisconnected(endpointId:String) {
            peers.remove(endpointId)
            if(endpointId==guestEndpoint) {
                guestEndpoint=null;connected=false
                status="A gazda nincs a közelben. A már lefoglalt percek megmaradnak."
                pendingDiscovery=false
                app?.let{discover(it)}
            } else {
                connected=peers.isNotEmpty()
                status="Aktív közeli tagok: "+peers.size
            }
        }
    }
    private val finder=object:EndpointDiscoveryCallback() {
        override fun onEndpointFound(endpointId:String,info:DiscoveredEndpointInfo) {
            val c=app?:return
            if(TeamStore.host(c)||pendingConnect||guestEndpoint!=null)return
            if(info.endpointName!="FT:"+TeamStore.teamId(c))return
            pendingConnect=true
            status="Megtaláltam a csapat gazdáját, csatlakozás…"
            client?.requestConnection(TeamStore.nickname(c),endpointId,lifecycle)
                ?.addOnFailureListener {
                    pendingConnect=false
                    status="Kapcsolati hiba: "+(it.localizedMessage ?: "nem sikerült")
                }
        }
        override fun onEndpointLost(endpointId:String){}
    }
    private fun advertise(c:Context) {
        client?.startAdvertising("FT:"+TeamStore.teamId(c),SERVICE,lifecycle,
            AdvertisingOptions.Builder().setStrategy(strategy).build())
            ?.addOnSuccessListener {
                status="✓ A csapat elérhető a közelben. Mutasd a QR-kódot!"
            }?.addOnFailureListener {
                status="Nem indul a csapatmegosztás: "+(it.localizedMessage ?: "hálózati hiba")
            }
    }
    private fun discover(c:Context) {
        if(!running||pendingDiscovery||guestEndpoint!=null)return
        pendingDiscovery=true
        client?.startDiscovery(SERVICE,finder,DiscoveryOptions.Builder()
            .setStrategy(strategy).build())
            ?.addOnSuccessListener {
                status="A QR-kódban szereplő közeli csapatot keresem…"
            }?.addOnFailureListener {
                status="Keresési hiba: "+(it.localizedMessage ?: "nincs hozzáférés")
                pendingDiscovery=false
            }
    }
    private fun sendTo(endpoint:String,data:JSONObject) {
        try{
            val bytes=data.toString().toByteArray(StandardCharsets.UTF_8)
            if(bytes.size<=80_000)client?.sendPayload(endpoint,Payload.fromBytes(bytes))
        }catch(_:Exception){}
    }
    fun broadcast(c:Context) {
        if(!TeamStore.host(c))return
        val state=TeamStore.snapshot(c)
        for(endpoint in peers.keys)sendTo(endpoint,state)
    }
    fun sendPending(c:Context) {
        if(TeamStore.host(c))return
        val endpoint=guestEndpoint?:return
        for(id in TeamStore.pending(c).take(40))
            sendTo(endpoint,JSONObject().put("type","task").put("event",id))
    }
    fun requestLease(c:Context) {
        if(TeamStore.host(c)||TeamStore.leased(c)>60)return
        val endpoint=guestEndpoint?:return
        sendTo(endpoint,JSONObject().put("type","leaseRequest"))
    }
    private var lastSync=0L
    private var lastLease=0L
    fun pulse(c:Context) {
        if(!running||!TeamStore.active(c))return
        val now=android.os.SystemClock.elapsedRealtime()
        if(TeamStore.host(c)) {
            if(now-lastSync>15000L) {
                TeamStore.settle(c)
                broadcast(c)
                lastSync=now
            }
        } else {
            if(guestEndpoint!=null) {
                if(now-lastSync>15000L) {
                    sendTo(guestEndpoint!!,JSONObject().put("type","report")
                        .put("profile",TeamStore.report(c)))
                    sendPending(c)
                    lastSync=now
                }
                if(Store.bal(c)==0 && TeamStore.leased(c)<=20 && now-lastLease>30000L) {
                    lastLease=now;requestLease(c)
                }
            }
        }
    }
    fun requestBoss(c:Context) {
        if(TeamStore.host(c))TeamStore.claimBoss(c)
        else guestEndpoint?.let {sendTo(it,JSONObject().put("type","boss"))}
    }
    fun sync(c:Context) {
        if(TeamStore.host(c))broadcast(c)
        else guestEndpoint?.let {
            sendTo(it,JSONObject().put("type","report").put("profile",TeamStore.report(c)))
            sendTo(it,JSONObject().put("type","refresh"))
            sendPending(c)
        }
    }
}
