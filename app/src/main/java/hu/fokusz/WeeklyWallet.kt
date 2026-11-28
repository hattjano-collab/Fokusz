package hu.fokusz

import android.content.Context
import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

internal object WeeklyWalletClock {
    val zone = ZoneId.of("Europe/Budapest")
    fun today(): LocalDate = LocalDate.now(zone)
    fun monday(day: LocalDate) = day.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
}

internal enum class WeeklyMode(val api: String, val label: String) {
    SAFE("safe", "Biztos megtartás"),
    RISK("risk", "Mindent vagy semmit");

    companion object { fun read(api: String) = if (api == RISK.api) RISK else SAFE }
}

internal data class WeeklyReceipt(
    val week: String, val device: String, val mode: WeeklyMode, val closing: Long,
    val sureTenths: Long, val retainedTenths: Long, val openingTenths: Long,
    val rank: Int?, val percent: Int, val won: Boolean?,
) {
    companion object {
        fun read(row: JSONObject) = WeeklyReceipt(
            row.getString("week"), row.getString("device"), WeeklyMode.read(row.getString("mode")),
            row.getLong("closing"), row.getLong("sureTenths"), row.getLong("retainedTenths"),
            row.getLong("openingTenths"),
            if (row.isNull("rank")) null else row.getInt("rank"), row.getInt("percent"),
            if (row.isNull("won")) null else row.getBoolean("won")
        )
    }
}

internal data class WeeklyStatus(
    val week: String, val cutoff: Long, val mode: WeeklyMode, val closing: Long,
    val sureTenths: Long, val rank: Int?, val percent: Int, val receipts: List<WeeklyReceipt>,
)

internal object WeeklyWallet {
    private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Mutex()
    private val automaticSync = AtomicBoolean(false)
    @Volatile private var retryAt = 0L

    var status by mutableStateOf<WeeklyStatus?>(null)
        private set
    var message by mutableStateOf("")
        private set

    fun pulse(c: Context) {
        if (SystemClock.elapsedRealtime() < retryAt || !automaticSync.compareAndSet(false, true)) return
        io.launch {
            try { synchronize(c.applicationContext) }
            finally { automaticSync.set(false) }
        }
    }

    suspend fun synchronize(c: Context, choice: WeeklyMode? = null, week: String? = null): Boolean =
        withContext(Dispatchers.IO) {
            lock.withLock {
                try {
                    val app = c.applicationContext
                    val auth = FirebaseAuth.getInstance()
                    val user = auth.currentUser ?: auth.signInAnonymously().await().user
                        ?: error("Authentication unavailable")
                    val token = user.getIdToken(false).await().token ?: error("Token missing")
                    val device = installation(app)
                    val (wallet, bank) = Store.weeklySnapshot(app)
                    val payload = JSONObject().put("device", device).put("wallet", wallet).put("bank", bank)
                        .put("team", CloudTeams.teamId(app))
                    if (choice != null && week != null) payload.put("mode", choice.api).put("expectedWeek", week)
                    val row = request(token, payload)
                    val history = row.getJSONArray("history")
                    val receipts = (0 until history.length()).map { WeeklyReceipt.read(history.getJSONObject(it)) }
                    for (receipt in receipts.asReversed()) {
                        if (receipt.device == device) Store.applyWeeklyReceipt(app, receipt)
                    }
                    val active = WeeklyStatus(row.getString("week"), row.getLong("deadline"),
                        WeeklyMode.read(row.getString("mode")), row.getLong("closing"), row.getLong("sureTenths"),
                        if (row.isNull("rank")) null else row.getInt("rank"), row.getInt("percent"), receipts)
                    Store.registerWeeklyPeriod(app, active.week)
                    status = active
                    message = ""
                    val untilCutoff = (active.cutoff - row.getLong("now")).coerceAtLeast(0)
                    retryAt = SystemClock.elapsedRealtime() + if (untilCutoff < 180_000L) 5_000L else 55_000L
                    true
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (problem: WeeklyRequestFailure) {
                    message = if (problem.code == 404) "A heti elszámolás szervere még nem érhető el."
                        else if (problem.code == 400 || problem.code == 412) "A választás nem érkezett meg. Frissítsd a heti állapotot."
                        else "A heti állapot szinkronizálása most nem sikerült."
                    retryAt = SystemClock.elapsedRealtime() + if (problem.code == 404) 1_800_000L else 60_000L
                    false
                } catch (_: Exception) {
                    message = "A heti állapot szinkronizálása most nem sikerült."
                    retryAt = SystemClock.elapsedRealtime() + 60_000L
                    false
                }
            }
        }

    private fun installation(c: Context): String {
        val file = File(c.noBackupFilesDir, "weekly-wallet-device")
        return if (file.isFile) file.readText().trim()
        else UUID.randomUUID().toString().also { file.writeText(it) }
    }

    private fun request(token: String, payload: JSONObject): JSONObject {
        val project = checkNotNull(FirebaseApp.getInstance().options.projectId)
        val address = URL("https://europe-west1-$project.cloudfunctions.net/syncWeeklyWallet")
        val connection = address.openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 8_000
            connection.readTimeout = 10_000
            connection.requestMethod = "POST"
            connection.instanceFollowRedirects = false
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("Authorization", "Bearer $token")
            val bytes = JSONObject().put("data", payload).toString().toByteArray()
            connection.setFixedLengthStreamingMode(bytes.size)
            connection.outputStream.use { it.write(bytes) }
            val code = connection.responseCode
            if (code != 200) throw WeeklyRequestFailure(code)
            return connection.inputStream.bufferedReader().use { JSONObject(it.readText()).getJSONObject("result") }
        } finally {
            connection.disconnect()
        }
    }

    private class WeeklyRequestFailure(val code: Int) : Exception()
}
