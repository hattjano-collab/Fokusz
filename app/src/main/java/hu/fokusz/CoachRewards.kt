package hu.fokusz

import android.content.Context

data class CoachAward(val count: Int, val awarded: Int)

object CoachRewards {
    private fun sp(c: Context) =
        c.getSharedPreferences("fokusz_coach_v1",Context.MODE_PRIVATE)

    fun target(mode: String): Int = when(mode) {
        "pushups" -> 10
        "squats" -> 20
        "lunges" -> 20
        "plank" -> 60
        else -> Int.MAX_VALUE
    }
    fun name(mode:String): String = when(mode) {
        "pushups" -> "fekvőtámasz"
        "squats" -> "guggolás"
        "lunges" -> "kitörés"
        "plank" -> "másodperc plank"
        else -> "ismétlés"
    }

    @Synchronized fun count(c: Context, mode: String): Int =
        sp(c).getInt("total_$mode",0)

    /** Az újonnan felismert mozdulatot egyszer számolja. Minden küszöbnél +120 mp. */
    @Synchronized fun recognized(c: Context, mode: String): CoachAward {
        val goal = target(mode)
        if(goal == Int.MAX_VALUE) return CoachAward(0,0)
        val s = sp(c)
        val previous = s.getInt("total_$mode",0)
        val next = previous + 1
        s.edit().putInt("total_$mode",next).commit()
        ProgressStore.coachReps(c, if(mode=="plank") 0 else 1, mode)
        if(next % goal == 0) {
            val before = Store.bal(c)
            val bonus=if(FocusPrefs.focusLocked(c) &&
                mode in setOf("pushups","squats","plank")) 240 else 120
            val after = Store.add(c,bonus)
            val actual = (after-before).coerceAtLeast(0)
            ProgressStore.earned(c,actual)
            return CoachAward(next,actual)
        }
        return CoachAward(next,0)
    }
}
