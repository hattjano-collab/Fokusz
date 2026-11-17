package hu.fokusz

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

data class ChessAccountCheck(val success:Boolean,val message:String)
data class ChessDailyResult(
    val count:Int,val message:String,val success:Boolean,
    val wins:Int=0,val losses:Int=0,val draws:Int=0
)
private data class ChessGame(val id:String,val day:LocalDate,val outcome:String)

object ChessVerifier {
    private fun prefs(c:Context)=c.getSharedPreferences("fokusz_chess_v2",0)
    private fun scores(c:Context)=c.getSharedPreferences("fokusz_chess_scores",0)
    fun username(c:Context)=prefs(c).getString("username","") ?: ""
    fun platform(c:Context)=prefs(c).getString("platform","lichess") ?: "lichess"
    fun isLinked(c:Context)=prefs(c).getBoolean("linked",false)&&
        username(c).isNotBlank() && platform(c) in listOf("lichess","chesscom")
    fun unlink(c:Context){prefs(c).edit().clear().apply()}
    fun wins(c:Context)=scores(c).getInt("wins",0)
    fun losses(c:Context)=scores(c).getInt("losses",0)
    fun draws(c:Context)=scores(c).getInt("draws",0)
    fun weekGames(c:Context):Int =
        (0 until LocalDate.now().dayOfWeek.value).sumOf { offset ->
            val day=LocalDate.now().minusDays(offset.toLong()).toString()
            scores(c).getInt("day_"+day,0)
        }

    private fun request(url:String,accept:String="application/json"):String {
        val connection=URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout=9000
        connection.readTimeout=15000
        connection.requestMethod="GET"
        connection.setRequestProperty("User-Agent","FokuszAndroid/2.2 (public chess account)")
        connection.setRequestProperty("Accept",accept)
        try {
            if(connection.responseCode !in 200..299)
                throw IllegalStateException("A sakkoldal HTTP "+connection.responseCode+" hibát küldött.")
            return connection.inputStream.bufferedReader().use { it.readText().take(3_500_000) }
        } finally {connection.disconnect()}
    }

    suspend fun link(c:Context,source:String,name:String):ChessAccountCheck=withContext(Dispatchers.IO) {
        val user=name.trim()
        if(source !in setOf("lichess","chesscom") || !user.matches(Regex("[A-Za-z0-9_-]{2,25}")))
            return@withContext ChessAccountCheck(false,"Érvénytelen felhasználónév (2–25 karakter).")
        try {
            val encoded=URLEncoder.encode(user,"UTF-8")
            val url=if(source=="lichess") "https://lichess.org/api/user/"+encoded
                    else "https://api.chess.com/pub/player/"+encoded
            val profile=JSONObject(request(url))
            val ok=if(source=="lichess") profile.optString("id").isNotBlank()
                else profile.optString("username").isNotBlank()
            if(!ok) ChessAccountCheck(false,"Nem található ilyen nyilvános sakkprofil.")
            else {
                val old=prefs(c)
                if(old.getString("username","")!=user || old.getString("platform","")!=source) {
                    scores(c).edit().clear().commit()
                }
                old.edit().putString("platform",source)
                    .putString("username",user).putBoolean("linked",true).commit()
                ChessAccountCheck(true,"Mentett nyilvános sakkfiók: "+user+". A Fókusz megjegyzi.")
            }
        } catch(e:Exception) {
            ChessAccountCheck(false,"Hálózati hiba: "+(e.localizedMessage ?: "nem sikerült az ellenőrzés"))
        }
    }

    private fun lichessGames(name:String,from:Long,to:Long):List<ChessGame> {
        val url="https://lichess.org/api/games/user/"+URLEncoder.encode(name,"UTF-8")+
            "?since="+from+"&until="+to+"&max=100&finished=true&moves=false"
        val zone=ZoneId.systemDefault()
        return request(url,"application/x-ndjson").lineSequence()
            .mapNotNull { line ->
                if(!line.trimStart().startsWith("{"))return@mapNotNull null
                try {
                    val g=JSONObject(line)
                    val end=g.optLong("lastMoveAt",0)
                    val status=g.optString("status","")
                    if(end !in from..to || status in listOf("started","created","aborted"))return@mapNotNull null
                    val white=g.optJSONObject("players")?.optJSONObject("white")
                    val black=g.optJSONObject("players")?.optJSONObject("black")
                    val ownWhite=(white?.optJSONObject("user")?.optString("id") ?: "").equals(name,true)
                    val ownBlack=(black?.optJSONObject("user")?.optString("id") ?: "").equals(name,true)
                    if(!ownWhite && !ownBlack)return@mapNotNull null
                    val winner=g.optString("winner","")
                    val result=if(winner.isBlank())"draw" else if(
                        (ownWhite && winner=="white")||(ownBlack && winner=="black"))"win" else "loss"
                    val id=g.optString("id","")
                    if(id.isBlank())null else ChessGame("l:"+id,
                        Instant.ofEpochMilli(end).atZone(zone).toLocalDate(),result)
                } catch (_:Exception) {null}
            }.toList()
    }

    private fun chessComGames(name:String,from:Long,to:Long):List<ChessGame> {
        val now=LocalDate.now()
        val month=now.format(DateTimeFormatter.ofPattern("yyyy/MM"))
        val text=request("https://api.chess.com/pub/player/"+URLEncoder.encode(name,"UTF-8")+
            "/games/"+month)
        val games=JSONObject(text).optJSONArray("games")?:return emptyList()
        val zone=ZoneId.systemDefault()
        val out=mutableListOf<ChessGame>()
        val drawCodes=setOf("agreed","stalemate","repetition","insufficient","50move",
            "timevsinsufficient","draw")
        for(i in 0 until games.length()) {
            val game=games.optJSONObject(i) ?: continue
            val end=game.optLong("end_time",0L)*1000L
            if(end !in from..to) continue
            val white=game.optJSONObject("white")
            val black=game.optJSONObject("black")
            val own=when {
                white?.optString("username","").equals(name,true) ->white
                black?.optString("username","").equals(name,true) ->black
                else ->null
            }?:continue
            val code=own.optString("result","")
            if(code.isBlank())continue
            val outcome=when {
                code=="win"->"win"
                code in drawCodes->"draw"
                else->"loss"
            }
            val id=game.optString("url","")
            if(id.isNotBlank())out.add(ChessGame("c:"+id,
                Instant.ofEpochMilli(end).atZone(zone).toLocalDate(),outcome))
        }
        return out
    }

    @Synchronized private fun record(c:Context,games:List<ChessGame>) {
        val prefs=scores(c)
        var keys=prefs.getString("seen","")?.split("\n")?.filter { it.isNotBlank() }?.toMutableList()
            ?:mutableListOf()
        val seen=keys.toHashSet()
        for(g in games.sortedBy { it.id }) {
            if(g.id in seen)continue
            val pay=when(g.outcome) {
                "win"->60
                "loss"->30
                else->45
            }
            val before=Store.bal(c)
            Store.add(c,pay)
            val real=(Store.bal(c)-before).coerceAtLeast(0)
            ProgressStore.earned(c,real)
            val key=when(g.outcome) {"win"->"wins";"loss"->"losses";else->"draws"}
            prefs.edit().putInt(key,prefs.getInt(key,0)+1)
                .putInt("day_"+g.day,prefs.getInt("day_"+g.day,0)+1).commit()
            seen.add(g.id);keys.add(g.id)
        }
        prefs.edit().putString("seen",keys.takeLast(450).joinToString("\n")).commit()
    }

    suspend fun dailyProgress(c:Context):ChessDailyResult=withContext(Dispatchers.IO) {
        if(!isLinked(c))return@withContext ChessDailyResult(0,"Előbb köss össze sakkfiókot a Beállításokban.",false)
        val zone=ZoneId.systemDefault()
        val since=LocalDate.now().atStartOfDay(zone).toInstant().toEpochMilli()
        val until=System.currentTimeMillis()
        try {
            val games=if(platform(c)=="lichess") lichessGames(username(c),since,until)
                else chessComGames(username(c),since,until)
            record(c,games)
            val unique=games.distinctBy { it.id }
            ChessDailyResult(unique.size,unique.size.toString()+"/"+
                QuestStore.chessGoal(c)+" mai parti • Győzelem +1p, vereség +30mp, döntetlen +45mp",
                true,unique.count{it.outcome=="win"},unique.count{it.outcome=="loss"},
                unique.count{it.outcome=="draw"})
        } catch(e:Exception){
            ChessDailyResult(0,"Sakkellenőrzés sikertelen: "+(e.localizedMessage ?: "hálózati hiba"),false)
        }
    }
}
