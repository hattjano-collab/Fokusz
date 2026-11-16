package hu.fokusz

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.LocalDate
import java.time.format.DateTimeFormatter

data class ChessCheck(val found: Boolean, val message: String, val gameId: String = "")

object ChessVerifier {
    private fun prefs(c: Context) = c.getSharedPreferences("fokusz_chess_v1", 0)
    fun username(c: Context) = prefs(c).getString("username", "") ?: ""
    fun platform(c: Context) = prefs(c).getString("platform", "lichess") ?: "lichess"
    fun setAccount(c: Context, platform: String, username: String) {
        prefs(c).edit().putString("platform", platform)
            .putString("username", username.trim()).apply()
    }
    @Suppress("BlockingMethodInNonBlockingContext")
    suspend fun check(c: Context): ChessCheck = withContext(Dispatchers.IO) {
        val name = username(c)
        if (!name.matches(Regex("[A-Za-z0-9_-]{2,25}"))) {
            return@withContext ChessCheck(false, "Adj meg egy érvényes Lichess/Chess.com felhasználónevet.")
        }
        val since = QuestStore.chessStartedAt(c)
        if (since <= 0) return@withContext ChessCheck(false, "Előbb indítsd el a sakkquestet.")
        val lastId = prefs(c).getString("last_game", "")
        try {
            val lichess = platform(c) == "lichess"
            val encoded = URLEncoder.encode(name, "UTF-8")
            val url = if (lichess) {
                "https://lichess.org/api/games/user/" + encoded + "?max=25&since=" + since + "&finished=true"
            } else {
                val month = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy/MM"))
                "https://api.chess.com/pub/player/" + encoded + "/games/" + month
            }
            val connection = URL(url).openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = 7000
            connection.readTimeout = 12000
            connection.setRequestProperty("Accept", if (lichess) "application/x-ndjson" else "application/json")
            connection.setRequestProperty("User-Agent", "FokuszAndroid/1.5 (public game checker)")
            try {
                if (connection.responseCode !in 200..299)
                    return@withContext ChessCheck(false, "A sakkoldal " + connection.responseCode + " hibát jelzett. Ellenőrizd a felhasználónevet.")
                val body = connection.inputStream.bufferedReader().use { it.readText().take(1_500_000) }
                if (lichess) {
                    val games = body.lineSequence().filter { it.startsWith("{") }.take(25)
                        .mapNotNull { try { JSONObject(it) } catch (_: Exception) { null } }
                    for (g in games) {
                        val ended = g.optLong("lastMoveAt", 0L)
                        val id = "lichess:" + g.optString("id")
                        val status = g.optString("status")
                        if (ended >= since && id != lastId && id.length > 10 && status != "started") {
                            prefs(c).edit().putString("last_game", id).apply()
                            return@withContext ChessCheck(true, "Befejezett Lichess-parti megtalálva!", id)
                        }
                    }
                } else {
                    val games = JSONObject(body).optJSONArray("games")
                    if (games != null) for (i in 0 until games.length()) {
                        val g = games.optJSONObject(i) ?: continue
                        val white = g.optJSONObject("white")?.optString("username") ?: ""
                        val black = g.optJSONObject("black")?.optString("username") ?: ""
                        val ended = g.optLong("end_time", 0) * 1000L
                        val id = "chesscom:" + g.optString("url")
                        if (ended >= since && (white.equals(name, true) || black.equals(name, true))
                            && id != lastId && g.has("end_time")) {
                            prefs(c).edit().putString("last_game", id).apply()
                            return@withContext ChessCheck(true, "Befejezett Chess.com-parti megtalálva!", id)
                        }
                    }
                }
                ChessCheck(false, "Még nem látszik új, befejezett parti a megadott nyilvános fiókban. Próbáld meg újra néhány perc múlva.")
            } finally {
                connection.disconnect()
            }
        } catch (e: Exception) {
            ChessCheck(false, "Nem sikerült az online ellenőrzés: " + (e.localizedMessage ?: "hálózati hiba"))
        }
    }
}
