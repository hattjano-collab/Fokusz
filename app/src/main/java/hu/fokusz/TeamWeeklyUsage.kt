package hu.fokusz

import android.content.Context
import com.google.firebase.Timestamp
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.tasks.await
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.temporal.TemporalAdjusters
import java.util.Date

internal data class WeeklyMemberUsage(val uid: String, val usage: Int, val shared: Int)

internal object TeamWeeklyUsage {
    private fun prefs(c: Context) = c.getSharedPreferences("fokusz_weekly_usage", Context.MODE_PRIVATE)
    private fun week() = LocalDate.now(ZoneOffset.UTC).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
    private fun stamp(week: LocalDate) = Timestamp(Date.from(week.atStartOfDay(ZoneOffset.UTC).toInstant()))

    fun enabled(c: Context) = prefs(c).getBoolean("shareWeekly", false)
    fun setEnabled(c: Context, enabled: Boolean) = prefs(c).edit().putBoolean("shareWeekly", enabled).apply()

    private fun collection(team: String) = FirebaseFirestore.getInstance()
        .collection("teams").document(team).collection("weeklyUsage")

    suspend fun sync(c: Context, team: String, uid: String) {
        val week = week()
        val ref = collection(team).document(uid)
        if (!enabled(c)) return
        val usage = ProgressStore.recent(c, 8).filter { it.date in week..week.plusDays(6) }.sumOf { it.usage.toLong() }
        ref.set(mapOf("week" to stamp(week), "uid" to uid, "usage" to usage.coerceIn(0L, 604_800L),
            "shared" to CloudTeams.weeklySharedUsage(c, team, week),
            "updatedAt" to FieldValue.serverTimestamp())).await()
    }

    suspend fun withdraw(c: Context, team: String) {
        val uid = CloudTeams.currentUid().takeIf(String::isNotBlank) ?: return
        collection(team).document(uid).delete().await()
    }

    suspend fun ranking(team: String): List<WeeklyMemberUsage> =
        collection(team).whereEqualTo("week", stamp(week())).get().await().documents.mapNotNull { row ->
            val uid = row.getString("uid") ?: return@mapNotNull null
            WeeklyMemberUsage(uid, row.getLong("usage")?.toInt() ?: 0, row.getLong("shared")?.toInt() ?: 0)
        }
}
