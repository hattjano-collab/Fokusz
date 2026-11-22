package hu.fokusz

import android.content.Context

data class CoachAward(val count: Int, val awarded: Int)

object CoachRewards {
    private fun sp(c: Context) =
        c.getSharedPreferences("fokusz_coach_v1",Context.MODE_PRIVATE)

    private fun baseSeconds(mode: String): Int = when (mode) {
        "pushups" -> 12
        "squats", "lunges" -> 6
        "plank" -> 2
        else -> 0
    }

    fun secondsPerRep(c: Context, mode: String): Int {
        val multiplier = if (mode != "lunges" && FocusPrefs.focusLocked(c)) 2 else 1
        return baseSeconds(mode) * multiplier
    }

    @Synchronized fun count(c: Context, mode: String): Int =
        sp(c).getInt("total_$mode",0)

    /** Minden elfogadott ismétlés vagy plank-másodperc azonnal jóváírható. */
    @Synchronized fun recognized(c: Context, mode: String): CoachAward {
        val seconds = secondsPerRep(c, mode)
        if (seconds == 0) return CoachAward(0, 0)
        val s = sp(c)
        val next = (s.getInt("total_$mode", 0).toLong() + 1).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        s.edit().putInt("total_$mode", next).commit()
        ProgressStore.coachReps(c, if (mode == "plank") 0 else 1, mode)
        val actual = Store.credit(c, seconds, "Kamerás edzés")
        ProgressStore.earned(c, actual)
        return CoachAward(next, actual)
    }
}
