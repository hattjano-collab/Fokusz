package hu.fokusz

import android.content.Context

/**
 * A 20 választható napi alapfeladatból pontosan két különböző aktív.
 * A sakk továbbra is a mentett nyilvános fiókból, a kamerás gyakorlatok
 * kizárólag élő kameraképpel teljesíthetők.
 */
object BaseQuests {
    data class Choice(
        val id: Int,
        val name: String,
        val icon: String,
        val kind: QuestKind,
        val goal: Int,
        val rewardMinutes: Int,
        val details: String,
        val mode: String = ""
    )
    val choices = listOf(
        Choice(0,"Napi sakkmeccsek","♟️",QuestKind.CHESS,1,5,
            "Napi 1–5 ellenőrzött online sakkparti; eredménytől függetlenül +5 perc bónusz."),
        Choice(1,"10 vagy 20 fekvőtámasz","💪",QuestKind.PUSHUPS,10,10,
            "Élő kamerával számolt, szabályos fekvőtámasz.","pushups"),
        Choice(2,"20 guggolás","🏋️",QuestKind.EXERCISE,20,8,
            "Kamerával számolt guggolások.","squats"),
        Choice(3,"12 kitörés","👟",QuestKind.EXERCISE,12,8,
            "Kamerával számolt kitörések.","lunges"),
        Choice(4,"30 másodperc plank","🧘",QuestKind.EXERCISE,30,7,
            "Kamerával ellenőrzött plank, másodpercenként számlálva.","plank"),
        Choice(5,"20 perc tanulás","📚",QuestKind.TIMED,1200,15,
            "Tanulj zavartalanul 20 percig!"),
        Choice(6,"15 perc olvasás","📖",QuestKind.TIMED,900,10,
            "Olvass egy könyvet 15 percig!"),
        Choice(7,"20 perc szobatakarítás","🧹",QuestKind.TIMED,1200,15,
            "Takaríts ki egy részt a szobádban!"),
        Choice(8,"15 perc matematika","🔢",QuestKind.TIMED,900,12,
            "Oldj meg matekfeladatokat!"),
        Choice(9,"15 perc fizika","⚛️",QuestKind.TIMED,900,12,
            "Gyakorolj fizikai feladatokat!"),
        Choice(10,"15 perc német","🌍",QuestKind.TIMED,900,12,
            "Tanulj német szavakat vagy nyelvtant!"),
        Choice(11,"10 perc asztalrend","🗂️",QuestKind.TIMED,600,7,
            "Rakj rendet az íróasztalodon!"),
        Choice(12,"15 perc ruharendezés","👕",QuestKind.TIMED,900,10,
            "Hajtogass vagy pakolj el ruhákat!"),
        Choice(13,"15 perc mosogatás","🍽️",QuestKind.TIMED,900,10,
            "Mosogass, és rakj rendet a konyhában!"),
        Choice(14,"20 perc séta","🚶",QuestKind.TIMED,1200,12,
            "Menj ki a szabadba sétálni!"),
        Choice(15,"20 perc kosárlabda","🏀",QuestKind.TIMED,1200,15,
            "Gyakorolj kosárlabdával!"),
        Choice(16,"20 perc bicikli","🚲",QuestKind.TIMED,1200,12,
            "Biciklizz biztonságos környezetben!"),
        Choice(17,"15 perc nyújtás","🤸",QuestKind.TIMED,900,10,
            "Nyújts fájdalommentesen, tudatosan!"),
        Choice(18,"10 perc táskarendezés","🎒",QuestKind.TIMED,600,7,
            "Pakold be a táskád, és készülj a következő napra!"),
        Choice(19,"20 perc rajzolás","🎨",QuestKind.TIMED,1200,12,
            "Rajzolj, vázlatolj vagy készíts kreatív alkotást!")
    )
    private val byId=choices.associateBy { it.id }
    private fun sp(c: Context)=c.getSharedPreferences("fokusz_daily_starters_v1",Context.MODE_PRIVATE)
    fun configured(c: Context): Pair<Int,Int> {
        val s=sp(c)
        val a=s.getInt("choice0",0)
        val b=s.getInt("choice1",1)
        return if(a in byId && b in byId && a!=b) a to b else 0 to 1
    }
    fun choice(id:Int):Choice=byId[id] ?: choices[0]

    fun actual(c: Context,slot:Int,id:Int):Quest {
        val item=choice(id)
        val goal=when(id) {
            0 -> QuestStore.chessGoal(c)
            1 -> QuestStore.pushTarget(c)
            else -> item.goal
        }
        val name=when(id) {
            0 -> "Játssz le $goal sakkmeccset"
            1 -> "$goal szabályos fekvőtámasz"
            else -> item.name
        }
        return Quest(-101-slot,name,item.details,item.icon,item.kind,goal,
            item.rewardMinutes*60,if(item.mode.isBlank())"pushups" else item.mode)
    }

    /** Mentett választás: ha a nap már elkezdődött, a következő napon lép életbe. */
    @Synchronized fun save(c: Context,a:Int,b:Int):Boolean {
        if(a==b || a !in byId || b !in byId)return false
        sp(c).edit().putInt("choice0",a).putInt("choice1",b).commit()
        return QuestStore.applyConfiguredIfIdle(c)
    }
}
