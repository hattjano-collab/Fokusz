package hu.fokusz

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONException
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

enum class ChessPlatform(val key: String, val label: String) {
    CHESS_COM("chesscom", "Chess.com"),
    LICHESS("lichess", "Lichess")
}

data class ChessAccount(val platform: ChessPlatform, val username: String) {
    val label: String get() = "${platform.label}: $username"
}

data class ChessAccountCheck(val success: Boolean, val message: String)

data class ChessDailyResult(
    val count: Int,
    val message: String,
    val success: Boolean,
    val day: LocalDate
)

enum class ChessOutcome(val key: String, val seconds: Int, val label: String) {
    WIN("wins", 60, "Győzelem"),
    LOSS("losses", 30, "Vereség"),
    DRAW("draws", 45, "Döntetlen")
}

data class ChessReward(
    val id: String,
    val platform: ChessPlatform,
    val opponent: String?,
    val outcome: ChessOutcome,
    val playedAt: Instant,
    val verifiedAt: Instant,
    val creditedSeconds: Int,
    val gameUrl: String?
)

private data class ChessGame(
    val id: String,
    val outcome: ChessOutcome,
    val opponent: String?,
    val playedAt: Instant,
    val url: String?
)

object ChessVerifier {
    private const val HISTORY_PREFIX = "reward_history_"
    private val accountLock = Any()
    private val progressLock = Mutex()
    private val usernamePattern = Regex("[A-Za-z0-9_-]{2,25}")
    private val chessComGamePath = Regex("/(?:game/(?:live|daily)|(?:live|daily)/game)/[0-9]+/?")
    private val lichessGamePath = Regex("/[A-Za-z0-9]{1,16}/?")
    private val archiveFormat = DateTimeFormatter.ofPattern("uuuu/MM", Locale.ROOT)

    private fun accountPrefs(c: Context): SharedPreferences {
        val prefs = c.getSharedPreferences("fokusz_chess_v2", Context.MODE_PRIVATE)
        if (prefs.contains("linked")) {
            val savedPlatform = ChessPlatform.entries.find { it.key == prefs.getString("platform", null) }
            val savedName = prefs.getString("username", null).orEmpty()
            val editor = prefs.edit().remove("platform").remove("username").remove("linked")
            if (prefs.getBoolean("linked", false) && savedPlatform != null && savedName.isNotBlank()) {
                editor.putString(savedPlatform.key, savedName)
            }
            editor.commit()
        }
        return prefs
    }

    private fun scores(c: Context) =
        c.getSharedPreferences("fokusz_chess_scores", Context.MODE_PRIVATE)

    fun accounts(c: Context): List<ChessAccount> = synchronized(accountLock) {
        val prefs = accountPrefs(c)
        ChessPlatform.entries.mapNotNull { platform ->
            prefs.getString(platform.key, null)?.takeIf { it.isNotBlank() }
                ?.let { ChessAccount(platform, it) }
        }
    }

    fun isLinked(c: Context) = accounts(c).isNotEmpty()

    fun unlink(c: Context, platform: ChessPlatform) = synchronized(accountLock) {
        accountPrefs(c).edit().remove(platform.key).commit()
    }

    fun wins(c: Context) = scores(c).getInt(ChessOutcome.WIN.key, 0)
    fun losses(c: Context) = scores(c).getInt(ChessOutcome.LOSS.key, 0)
    fun draws(c: Context) = scores(c).getInt(ChessOutcome.DRAW.key, 0)

    suspend fun rewardHistory(c: Context): List<ChessReward> = withContext(Dispatchers.IO) {
        scores(c).all.mapNotNull { (key, value) ->
            if (!key.startsWith(HISTORY_PREFIX) || value !is String) return@mapNotNull null
            try {
                val entry = JSONObject(value)
                val platform = ChessPlatform.entries.find { it.key == entry.getString("platform") }
                    ?: return@mapNotNull null
                val outcome = ChessOutcome.entries.find { it.key == entry.getString("outcome") }
                    ?: return@mapNotNull null
                ChessReward(
                    id = key.removePrefix(HISTORY_PREFIX),
                    platform = platform,
                    opponent = entry.optionalText("opponent"),
                    outcome = outcome,
                    playedAt = Instant.ofEpochMilli(entry.getLong("playedAt")),
                    verifiedAt = Instant.ofEpochMilli(entry.getLong("verifiedAt")),
                    creditedSeconds = entry.getInt("creditedSeconds"),
                    gameUrl = gameUrl(platform, entry.optString("url"))
                )
            } catch (_: JSONException) {
                null
            }
        }.sortedWith(compareByDescending<ChessReward> { it.playedAt }.thenBy { it.id })
    }

    fun weekGames(c: Context): Int {
        val today = LocalDate.now()
        return (0 until today.dayOfWeek.value).sumOf { offset ->
            scores(c).getInt("day_" + today.minusDays(offset.toLong()), 0)
        }
    }

    private fun request(
        url: String,
        accept: String = "application/json",
        missingArchiveIsEmpty: Boolean = false
    ): String {
        val connection = URI(url).toURL().openConnection() as HttpURLConnection
        connection.connectTimeout = 9000
        connection.readTimeout = 15000
        connection.requestMethod = "GET"
        connection.setRequestProperty("User-Agent", "FokuszAndroid (public chess account)")
        connection.setRequestProperty("Accept", accept)
        try {
            val status = connection.responseCode
            if (status == 404 && missingArchiveIsEmpty) return "{}"
            check(status in 200..299) { "A sakkoldal HTTP $status hibát küldött." }
            return connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    suspend fun link(c: Context, platform: ChessPlatform, name: String): ChessAccountCheck =
        withContext(Dispatchers.IO) {
            val user = name.trim().lowercase(Locale.ROOT)
            if (!usernamePattern.matches(user)) {
                return@withContext ChessAccountCheck(false, "Érvénytelen felhasználónév (2–25 karakter).")
            }
            try {
                val encoded = URLEncoder.encode(user, "UTF-8")
                val url = when (platform) {
                    ChessPlatform.LICHESS -> "https://lichess.org/api/user/$encoded"
                    ChessPlatform.CHESS_COM -> "https://api.chess.com/pub/player/$encoded"
                }
                val profile = JSONObject(request(url))
                val profileKey = if (platform == ChessPlatform.LICHESS) "id" else "username"
                val canonicalName = profile.optString(profileKey)
                if (canonicalName.isBlank()) {
                    return@withContext ChessAccountCheck(false, "Nem található ilyen nyilvános sakkprofil.")
                }
                val saved = synchronized(accountLock) {
                    accountPrefs(c).edit().putString(platform.key, canonicalName).commit()
                }
                if (saved) ChessAccountCheck(true, "Mentett sakkfiók: $canonicalName.")
                else ChessAccountCheck(false, "A sakkfiókot nem sikerült menteni. Próbáld újra.")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ChessAccountCheck(false, "A profil ellenőrzése sikertelen. ${e.localizedMessage.orEmpty()}")
            }
        }

    private fun lichessGames(name: String, from: Long, to: Long): List<ChessGame> {
        val encoded = URLEncoder.encode(name, "UTF-8")
        // The export indexes creation time. Include the previous day for games played across midnight.
        val firstStarted = from - 24 * 60 * 60 * 1000L
        val url = "https://lichess.org/api/games/user/$encoded" +
            "?since=$firstStarted&until=$to&finished=true&moves=false"
        return request(url, "application/x-ndjson").lineSequence()
            .filter { it.isNotBlank() }
            .mapNotNull { line ->
                val game = JSONObject(line)
                val end = game.optLong("lastMoveAt")
                val status = game.optString("status")
                val id = game.optString("id")
                if (end !in from..to || status in setOf("started", "created", "aborted", "noStart") ||
                    id.isBlank()) return@mapNotNull null
                val players = game.optJSONObject("players")
                val ownColor = listOf("white", "black").firstOrNull { color ->
                    players?.optJSONObject(color)?.optJSONObject("user")?.optString("id").equals(name, true)
                } ?: return@mapNotNull null
                val otherColor = if (ownColor == "white") "black" else "white"
                val player = players?.optJSONObject(otherColor)
                val user = player?.optJSONObject("user")
                val opponent = user?.optionalText("name") ?: user?.optionalText("id")
                    ?: player?.optInt("aiLevel")?.takeIf { it > 0 }?.let { "Stockfish $it" }
                val outcome = when (game.optString("winner")) {
                    "" -> ChessOutcome.DRAW
                    ownColor -> ChessOutcome.WIN
                    else -> ChessOutcome.LOSS
                }
                ChessGame("l:$id", outcome, opponent, Instant.ofEpochMilli(end),
                    gameUrl(ChessPlatform.LICHESS, "https://lichess.org/$id"))
            }.toList()
    }

    private fun chessComGames(name: String, from: Long, to: Long): List<ChessGame> {
        val encoded = URLEncoder.encode(name.lowercase(Locale.ROOT), "UTF-8")
        // Chess.com archives use UTC. A local day can straddle two API archive months.
        val first = YearMonth.from(Instant.ofEpochMilli(from).atOffset(ZoneOffset.UTC))
        val last = YearMonth.from(Instant.ofEpochMilli(to).atOffset(ZoneOffset.UTC))
        val draws = setOf("agreed", "stalemate", "repetition", "insufficient", "50move",
            "timevsinsufficient", "draw")
        return generateSequence(first) { it.plusMonths(1) }.takeWhile { it <= last }.flatMap { month ->
            val archive = request(
                "https://api.chess.com/pub/player/$encoded/games/${month.format(archiveFormat)}",
                missingArchiveIsEmpty = true
            )
            val games = JSONObject(archive).optJSONArray("games") ?: return@flatMap emptySequence()
            (0 until games.length()).asSequence().mapNotNull { index ->
                val game = games.optJSONObject(index) ?: return@mapNotNull null
                val end = game.optLong("end_time") * 1000L
                val id = game.optString("url")
                if (end !in from..to || id.isBlank()) return@mapNotNull null
                val white = game.optJSONObject("white")
                val black = game.optJSONObject("black")
                val own = listOfNotNull(white, black)
                    .firstOrNull { it.optString("username").equals(name, true) }
                    ?: return@mapNotNull null
                val opponent = (if (own === white) black else white)?.optionalText("username")
                val code = own.optString("result")
                if (code.isBlank()) return@mapNotNull null
                val outcome = when (code) {
                    "win" -> ChessOutcome.WIN
                    in draws -> ChessOutcome.DRAW
                    else -> ChessOutcome.LOSS
                }
                ChessGame("c:$id", outcome, opponent, Instant.ofEpochMilli(end),
                    gameUrl(ChessPlatform.CHESS_COM, id))
            }
        }.toList()
    }

    private fun JSONObject.optionalText(key: String): String? =
        if (isNull(key)) null else optString(key).takeIf { it.isNotBlank() }

    private fun gameUrl(platform: ChessPlatform, value: String): String? {
        val url = runCatching { URI(value) }.getOrNull() ?: return null
        if (!url.scheme.equals("https", true) || url.userInfo != null ||
            url.port != -1 && url.port != 443) return null
        val host = url.host?.lowercase(Locale.ROOT)
        val path = url.path.orEmpty()
        val valid = when (platform) {
            ChessPlatform.CHESS_COM ->
                (host == "www.chess.com" || host == "chess.com") && chessComGamePath.matches(path)
            ChessPlatform.LICHESS ->
                host == "lichess.org" && lichessGamePath.matches(path)
        }
        return value.takeIf { valid }
    }

    private fun cacheKey(account: ChessAccount) =
        "${account.platform.key}_${account.username.lowercase(Locale.ROOT)}"

    private fun cachedIds(prefs: SharedPreferences, account: ChessAccount, today: LocalDate): Set<String> {
        val key = cacheKey(account)
        if (prefs.getString("verified_date_$key", null) != today.toString()) return emptySet()
        return prefs.getStringSet("verified_games_$key", emptySet()).orEmpty()
    }

    fun todayGames(c: Context): Int {
        val today = LocalDate.now()
        val prefs = scores(c)
        return accounts(c).sumOf { cachedIds(prefs, it, today).size }
    }

    private fun record(c: Context, account: ChessAccount, day: LocalDate, games: List<ChessGame>): Boolean {
        val prefs = scores(c)
        val legacyIds = prefs.getString("seen", null).orEmpty().lineSequence().toHashSet()
        val editor = prefs.edit()
        val totals = ChessOutcome.entries.associateWith { prefs.getInt(it.key, 0) }.toMutableMap()
        var dayTotal = prefs.getInt("day_$day", 0)
        val cached = cachedIds(prefs, account, day)
        val ids = cached.toMutableSet()
        var newCredit = false
        for (game in games.distinctBy { it.id }) {
            if (game.id !in legacyIds && !prefs.contains("game_${game.id}")) {
                val credited = Store.creditChessGame(c, game.id, game.outcome.seconds)
                ProgressStore.chessEarned(c, day, game.id, credited)
                newCredit = newCredit || credited > 0
                totals[game.outcome] = totals.getValue(game.outcome) + 1
                dayTotal++
                editor.putBoolean("game_${game.id}", true)
                val history = JSONObject()
                    .put("platform", account.platform.key)
                    .put("opponent", game.opponent)
                    .put("outcome", game.outcome.key)
                    .put("playedAt", game.playedAt.toEpochMilli())
                    .put("verifiedAt", System.currentTimeMillis())
                    .put("creditedSeconds", credited)
                    .put("url", game.url)
                editor.putString(HISTORY_PREFIX + game.id, history.toString())
            }
            ids.add(game.id)
        }
        if (ids == cached) return false
        val key = cacheKey(account)
        for ((outcome, count) in totals) editor.putInt(outcome.key, count)
        check(editor.putInt("day_$day", dayTotal).putString("verified_date_$key", day.toString())
            .putStringSet("verified_games_$key", ids).commit()) {
            "A sakkellenőrzés eredményét nem sikerült menteni."
        }
        return newCredit
    }

    suspend fun dailyProgress(c: Context): ChessDailyResult = withContext(Dispatchers.IO) {
        progressLock.withLock {
            val zone = ZoneId.systemDefault()
            val now = Instant.now()
            val day = now.atZone(zone).toLocalDate()
            val linked = accounts(c)
            if (linked.isEmpty()) {
                return@withLock ChessDailyResult(0,
                    "Előbb köss össze sakkfiókot a Beállításokban.", false, day)
            }

            val from = day.atStartOfDay(zone).toInstant().toEpochMilli()
            val until = now.toEpochMilli()
            val checked = coroutineScope {
                linked.map { account ->
                    async {
                        val games = try {
                            when (account.platform) {
                                ChessPlatform.LICHESS -> lichessGames(account.username, from, until)
                                ChessPlatform.CHESS_COM -> chessComGames(account.username, from, until)
                            }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (_: Exception) {
                            null
                        }
                        account to games
                    }
                }.awaitAll()
            }
            val current = accounts(c)
            val failed = mutableListOf<String>()
            var successfulChecks = 0
            var newCredit = false
            for ((account, games) in checked) {
                if (account !in current) continue
                if (games == null) {
                    failed.add(account.platform.label)
                    continue
                }
                try {
                    if (record(c, account, day, games)) newCredit = true
                    successfulChecks++
                } catch (_: IllegalStateException) {
                    failed.add(account.platform.label)
                }
            }
            if (newCredit) ProgressStore.updateWidget(c)
            val prefs = scores(c)
            val counts = current.associateWith { cachedIds(prefs, it, day) }
            val count = counts.values.sumOf { it.size }
            val detail = counts.entries.joinToString(" · ") { (account, ids) ->
                "${account.platform.label}: ${ids.size}"
            }
            val message = "$count ellenőrzött mai parti" +
                (if (detail.isBlank()) "." else " ($detail).") +
                (if (failed.isEmpty()) "" else
                    " Később újra ellenőrizzük: ${failed.joinToString()}.")
            ChessDailyResult(count, message, successfulChecks > 0 || count > 0, day)
        }
    }
}
