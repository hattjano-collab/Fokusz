package hu.fokusz

import android.content.Context
import com.google.firebase.firestore.ListenerRegistration

internal object TeamVoiceGate {
    private var listener: ListenerRegistration? = null
    private var watchingTeam = ""
    private var cached: TeamChatItem? = null
    private var ready = false
    private fun prefs(c: Context) = c.getSharedPreferences("fokusz_team_voice_gate", Context.MODE_PRIVATE)

    fun mode(c: Context) = prefs(c).getInt("mode", 0)
    fun setMode(c: Context, mode: Int) = prefs(c).edit().putInt("mode", mode).apply()

    @Synchronized fun watch(c: Context) {
        val team = CloudTeams.teamId(c).takeIf { mode(c) > 0 }.orEmpty()
        if (team == watchingTeam) return
        listener?.remove()
        cached = null
        ready = false
        watchingTeam = team
        listener = if (team.isNotBlank()) TeamChat.listenPrelude(team) { entry ->
            synchronized(this) {
                if (watchingTeam == team) {
                    cached = entry
                    ready = true
                }
            }
        } else null
    }

    @Synchronized fun item(team: String) = cached.takeIf { team == watchingTeam }
    @Synchronized fun loaded(team: String) = team == watchingTeam && ready
}
