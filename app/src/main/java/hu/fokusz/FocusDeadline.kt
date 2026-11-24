package hu.fokusz

internal data class FocusTime(val wallMillis: Long, val elapsedMillis: Long, val boot: Int)

internal data class FocusTimer(
    val secondsLeft: Int,
    val paused: Boolean,
    val pauseSecondsUsed: Int,
    val endsAtMillis: Long
)

internal data class FocusDeadline(
    val origin: FocusTime,
    val deadlineMillis: Long,
    val pausedAtMillis: Long = 0L,
    val pauseSpentMillis: Long = 0L
) {
    private fun now(time: FocusTime): Long =
        if (origin.boot >= 0 && origin.boot == time.boot && time.elapsedMillis >= origin.elapsedMillis) {
            // Az eltelt idő a kijelző kikapcsolása és a mélyalvás alatt is telik.
            origin.wallMillis + (time.elapsedMillis - origin.elapsedMillis)
        } else {
            // Újraindítás után a mentett dátumhoz hasonlítunk.
            time.wallMillis.coerceAtLeast(origin.wallMillis)
        }

    private fun currentPause(now: Long): Long =
        if (pausedAtMillis == 0L) 0L else
            (now - pausedAtMillis).coerceIn(0L, PAUSE_LIMIT_MILLIS - pauseSpentMillis)

    fun view(time: FocusTime): FocusTimer {
        val now = now(time)
        val currentPause = currentPause(now)
        val used = pauseSpentMillis + currentPause
        val remaining = (deadlineMillis + currentPause - now).coerceIn(0L, MAX_FOCUS_MILLIS)
        val paused = pausedAtMillis != 0L && used < PAUSE_LIMIT_MILLIS && remaining > 0L
        val autoResumeDeadline = if (pausedAtMillis == 0L) deadlineMillis
            else deadlineMillis + (PAUSE_LIMIT_MILLIS - pauseSpentMillis)
        return FocusTimer(
            ((remaining + 999L) / 1000L).toInt(),
            paused,
            (used / 1000L).toInt(),
            autoResumeDeadline + (time.wallMillis - now)
        )
    }

    fun togglePause(time: FocusTime): FocusDeadline? {
        val snapshot = view(time)
        if (snapshot.secondsLeft == 0) return null
        val now = now(time)
        if (snapshot.paused) {
            val pausedFor = currentPause(now)
            return copy(deadlineMillis = deadlineMillis + pausedFor,
                pausedAtMillis = 0L, pauseSpentMillis = pauseSpentMillis + pausedFor)
        }
        if (snapshot.pauseSecondsUsed >= PAUSE_LIMIT_MILLIS / 1000L) return null
        return copy(pausedAtMillis = now)
    }

    companion object {
        const val PAUSE_LIMIT_MILLIS = 300_000L
        private const val MAX_FOCUS_MILLIS = 7_200_000L

        fun start(time: FocusTime, remainingMillis: Long, pauseSpentMillis: Long = 0L,
                  paused: Boolean = false): FocusDeadline {
            val remaining = remainingMillis.coerceIn(0L, MAX_FOCUS_MILLIS)
            val spent = pauseSpentMillis.coerceIn(0L, PAUSE_LIMIT_MILLIS)
            val pausedAt = if (paused && remaining > 0L && spent < PAUSE_LIMIT_MILLIS) time.wallMillis else 0L
            return FocusDeadline(time, time.wallMillis + remaining, pausedAt, spent)
        }
    }
}
