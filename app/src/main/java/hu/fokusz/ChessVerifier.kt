package hu.fokusz

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

data class ChessAccountCheck(val success: Boolean, val message: String)
data class ChessDailyResult(val count: Int, val message: String, val success: Boolean)

/**
 * Nyilvános profil ellenőrzése, nem OAuth bejelentkezés. A játékok olvasása
 * Chess.com PubAPI / Lichess nyilvános API felületén történik.
 */
object ChessVerifier {
    private fun prefs(c: Context) = c.getSharedPreferences("fokusz_chess_v2", Context.MODE_PRIVATE)
    fun isLinked(c: Context) = prefs(c).getBoolean("linked", false) &&
        username(c).isNotBlank() && platform(c) in listOf("lichess","chesscom")
    fun username(c: Context) = prefs(c).getString("username","") ?: ""
    fun platform(c: Context) = prefs(c).getString("platform","lichess") ?: "lichess"
    fun unlink(c: Context) { prefs(c).edit().clear().apply() }

    private fun request(url: String, accept: String = "application/json"): String {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 9000
        connection.readTimeout = 12000
        connection.requestMethod = "GET"
        connection.setRequestProperty("User-Agent", "FokuszAndroid/2.1 (personal focus app)")
        connection.setRequestProperty("Accept", accept)
        try {
            val responseCode = connection.responseCode
            if (responseCode !in 200..299) throw IllegalStateException(
                "A sakkoldal HTTP " + responseCode + " hibát küldött.")
            return connection.inputStream.bufferedReader().use { it.readText().take(3_500_000) }
        } finally {
            connection.disconnect()
        }
    }

    /** Összekötés a Beállításokban egyszer, később a felhasználónév újrakérése nélkül. */
    suspend fun link(c: Context, source: String, name: String): ChessAccountCheck = withContext(Dispatchers.IO) {
        val user = name.trim()
        if (source !in listOf("lichess","chesscom") ||
            !user.matches(Regex("[A-Za-z0-9_-]{2,25}")))
            return@withContext ChessAccountCheck(false,"A felhasználónév csak betűt, számot, _ és - jelet tartalmazhat (2–25 karakter).")
        try {
            val encoded = URLEncoder.encode(user,"UTF-8")
            val address = if(source=="lichess") "https://lichess.org/api/user/" + encoded
                else "https://api.chess.com/pub/player/" + encoded
            val profile = JSONObject(request(address))
            val found = if(source=="lichess") profile.optString("id").isNotBlank()
                else profile.optString("username").isNotBlank()
            if (!found) ChessAccountCheck(false, "Nem sikerült ellenőrizni a nyilvános profilt.")
            else {
                prefs(c).edit().putString("platform",source)
                    .putString("username",user).putBoolean("linked",true).commit()
                ChessAccountCheck(true, "A nyilvános sakkprofil mentve! Ezt mostantól automatikusan használja az app.")
            }
        } catch (e: Exception) {
            ChessAccountCheck(false,"Nem sikerült az összekötés: " + (e.localizedMessage ?: "hálózati hiba"))
        }
    }

    /** A helyi naptári nap során befejezett nyilvános partijait számolja. */
    suspend fun dailyProgress(c: Context): ChessDailyResult = withContext(Dispatchers.IO) {
        if(!isLinked(c)) return@withContext ChessDailyResult(0,"A Beállításokban először köss össze egy sakkfiókot.",false)
        val name = username(c)
        val platform = platform(c)
        val midnight = LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val ending = System.currentTimeMillis()
        try {
            val ids = linkedSetOf<String>()
            val encoded = URLEncoder.encode(name,"UTF-8")
            if(platform=="lichess") {
                // A Lichess a since paramétert milliszekundumban várja, a válasz NDJSON.
                val content = request("https://lichess.org/api/games/user/" + encoded +
                    "?since=" + midnight + "&until=" + ending + "&max=100&finished=true&moves=false", "application/x-ndjson")
                for (line in content.lineSequence()) {
                    if(!line.trimStart().startsWith("{")) continue
                    val game = try { JSONObject(line) } catch (_: Exception) { continue }
                    val id = game.optString("id")
                    val finishedAt = game.optLong("lastMoveAt", 0L)
                    val status = game.optString("status","")
                    if(id.isNotBlank() && finishedAt in midnight..ending && status != "started" &&
                        status != "created") ids.add(id)
                }
            } else {
                val currentMonth = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy/MM"))
                val currentGames = fetchChessCom(encoded, currentMonth, name, midnight, ending)
                ids.addAll(currentGames)
                // Ha éjfél környékén hónapot váltott, a tegnap végi áthúzódó játékot
                // a mai havi archívum már tartalmazza.
            }
            ChessDailyResult(ids.size, ids.size.toString() + "/" + QuestStore.chessGoal(c) +
                " ellenőrzött mai sakkjátszma",true)
        } catch (e: Exception) {
            ChessDailyResult(0,"Nem sikerült ellenőrizni a sakkfiókot: " +
                (e.localizedMessage ?: "hálózati hiba"),false)
        }
    }

    private fun fetchChessCom(user: String, month: String, username: String, after: Long, before: Long): Set<String> {
        val output = mutableSetOf<String>()
        val text = request("https://api.chess.com/pub/player/" + user + "/games/" + month)
        val games = JSONObject(text).optJSONArray("games") ?: return output
        for(i in 0 until games.length()) {
            val game = games.optJSONObject(i) ?: continue
            val w = game.optJSONObject("white")?.optString("username") ?: ""
            val b = game.optJSONObject("black")?.optString("username") ?: ""
            val end = game.optLong("end_time",0L) * 1000L
            val id = game.optString("url","")
            if(end in after..before && id.isNotBlank() &&
                (w.equals(username,ignoreCase = true)||b.equals(username,ignoreCase = true))) output.add(id)
        }
        return output
    }
}
