package hu.fokusz

import android.content.Context
import android.os.SystemClock
import java.security.SecureRandom

enum class QuestKind { TIMED, PUSHUPS, EXERCISE, CHESS }

data class Quest(
    val id: Int,
    val title: String,
    val description: String,
    val icon: String,
    val kind: QuestKind,
    val goal: Int,       // másodperc vagy szabályos fekvőtámasz
    val reward: Int,     // bónusz képernyőidő másodpercben
    val mode: String = "pushups"
)

object QuestStore {
    private fun t(id: Int, title: String, detail: String, icon: String, minutes: Int, reward: Int) =
        Quest(id, title, detail, icon, QuestKind.TIMED, minutes * 60, reward * 60)
    private fun p(id: Int, title: String, count: Int, reward: Int) =
        Quest(id, title, "Oldalról, teljes testtel a kamerában. Csak a szabályos, le-fel mozdulat számít.", "💪", QuestKind.PUSHUPS, count, reward * 60)

    private fun e(id: Int, title: String, count: Int, reward: Int, mode: String) =
        Quest(id, title, "Kamerás gyakorlat, testhelyzet-ellenőrzéssel és hangos visszajelzéssel.", "🏋️", QuestKind.EXERCISE, count, reward * 60, mode)

    val all = listOf(
        p(1, "20 szabályos fekvőtámasz", 20, 10),
        t(2, "Takaríts ki egy szobarészt", "Rakj rendet, törölj port vagy pakolj el egy kijelölt részen.", "🧹", 20, 20),
        t(3, "Tanulj koncentráltan", "Tanulj megszakítás nélkül egy tantárgyat, és jegyzetelj.", "📚", 20, 15),
        t(4, "Menj ki kosarazni", "Mozogj és gyakorolj a pályán, az időzítő közben is fut.", "🏀", 25, 20),
        t(5, "Sétálj a szabadban", "Tegyél egy nagyobb sétát a friss levegőn.", "🚶", 25, 15),
        t(6, "Olvass egy könyvet", "Olvass figyelmesen, telefon nélkül.", "📖", 15, 10),
        t(7, "Rakj rendet az asztalodon", "A felesleges dolgok kerüljenek a helyükre.", "🗂️", 10, 9),
        p(8, "10 szabályos fekvőtámasz", 10, 6),
        e(9, "20 guggolás", 20, 8, "squats"),
        e(10, "12 kitörés", 12, 10, "lunges"),
        e(11, "30 másodperc plank", 30, 10, "plank"),
        t(12, "Házi feladat megoldása", "Dolgozz a házi feladatodon, ne csak olvasgasd.", "✏️", 25, 20),
        t(13, "Német vagy angol gyakorlás", "Tanulj szavakat, olvass vagy oldj meg feladatokat.", "🌍", 15, 12),
        t(14, "Szókártyázás", "Ismételj és teszteld magad szókártyákkal.", "🃏", 20, 15),
        t(15, "Matematikai feladatok", "Oldj meg több feladatot, és ellenőrizd a lépéseidet.", "🔢", 20, 15),
        t(16, "Fizikafeladatok", "Számolj, készíts ábrát vagy vezesd le a megoldást.", "⚛️", 20, 15),
        t(17, "Rendezz el egy polcot", "Válogasd át, tisztítsd meg és rendezd el a tárgyakat.", "📦", 15, 12),
        t(18, "Hajtogass ruhákat", "Hajtogass, válogass és pakolj a szekrénybe.", "👕", 15, 10),
        t(19, "Mosogass el", "Tisztítsd meg az edényeket és a konyhapultot.", "🍽️", 15, 10),
        t(20, "Válogasd szét a hulladékot", "Gyűjtsd össze és rendezd a szelektív hulladékot.", "♻️", 15, 10),
        t(21, "Portalaníts", "Törölj át néhány bútort és a nehezen elérhető helyeket.", "✨", 15, 10),
        t(22, "Porszívózz fel", "Porszívózz fel egy vagy több helyiséget.", "🧼", 20, 15),
        t(23, "Pakold be a táskádat", "Készülj elő a holnapi napra, rendezd a felszerelést.", "🎒", 10, 7),
        t(24, "Tisztítsd meg a munkaterületed", "Töröld át az asztalt, egeret és billentyűzetet.", "🖥️", 10, 7),
        t(25, "Rendezd a könyveidet", "Csoportosítsd a tankönyveket és füzeteket.", "📕", 15, 11),
        t(26, "Gyakorold a labdavezetést", "Kosárlabdával gyakorolj többféle labdavezetést.", "🏀", 15, 10),
        t(27, "Kosárlabda: dobások", "Gyakorolj kosárra dobást, különböző helyzetekből.", "⛹️", 15, 12),
        t(28, "Biciklizz egyet", "Tegyél egy biztonságos kerékpáros kört.", "🚲", 20, 12),
        t(29, "Nyújts és lazíts", "Végezz könnyű, fájdalommentes nyújtásokat.", "🧘", 15, 10),
        t(30, "Mozgasd át magad", "Könnyű ízületi és mobilizáló gyakorlatok.", "🤸", 10, 7),
        t(31, "Táncolj vagy tornázz", "Mozogj zenére vagy végezz egy könnyű tornát.", "🎵", 15, 10),
        t(32, "Légzés és relaxáció", "Lazíts, lélegezz nyugodtan, pihenj a képernyőtől.", "🌿", 15, 10),
        t(33, "Séta lépcsőkkel", "Sétálj, és ha biztonságos, használj lépcsőket is.", "👟", 12, 9),
        t(34, "Rajzolj valamit", "Készíts egy részletes rajzot vagy vázlatot.", "🎨", 20, 12),
        t(35, "Írj naplót", "Fogalmazz meg néhány gondolatot vagy mai célt.", "📝", 15, 10),
        t(36, "Tanulj egy új készséget", "Gyakorolj hangszeren, kézműveskedj vagy építs valamit.", "🧩", 20, 12),
        t(37, "Készíts egy uzsonnát", "Készíts egy egyszerű ételt, majd takaríts el.", "🥪", 20, 15),
        t(38, "Gondozd a növényeket", "Öntözz szükség szerint, és rendezd a környezetüket.", "🪴", 15, 10),
        t(39, "Rendezd a digitális fájljaidat", "Rendszerezd a dokumentumokat és a tanulási mappákat.", "📁", 20, 12),
        t(40, "Telefonmentes pihenő", "Nézz ki az ablakon, olvasgass vagy csak pihenj.", "☀️", 10, 8)
    )
    private val byId = all.associateBy { it.id }
    private val random = SecureRandom()
    // v2 elkülönítve: a régi időegyenleg érintetlen marad.
    private fun sp(c: Context) = c.getSharedPreferences("fokusz_quests_v2", Context.MODE_PRIVATE)

    private const val CHESS = "chess"
    private const val PUSH = "push"
    private const val EXTRA = "extra"
    private const val CUSTOM = "custom"
    private const val NONE = ""

    /** A nap váltásakor új sakk + 10/20 fekvő; az extra sorrend megmarad. */
    @Synchronized private fun daily(c: Context) {
        val s = sp(c)
        val today = java.time.LocalDate.now().toString()
        if (s.getString("day", "") == today) return
        s.edit()
            .putString("day", today)
            .putInt("pushTarget", if (s.contains("pushTarget")) { if (s.getInt("pushTarget", 10) == 10) 20 else 10 } else { if (random.nextBoolean()) 10 else 20 })
            .putBoolean("chessDone", false)
            .putBoolean("pushDone", false)
            .putString("active", NONE)
            .putInt("phase", 0)
            .putInt("reps", 0)
            .putLong("elapsed", 0L)
            .putLong("last", 0L)
            .putInt("skips", 0)
            .putBoolean("customUsed", false)
            .putString("customName", "")
            .putString("replacedStarter", "")
            .putInt("completed", 0)
            .putString("result", "")
            .remove("extraId")
            .commit()
    }

    @Synchronized fun pushTarget(c: Context): Int { daily(c); return sp(c).getInt("pushTarget", 10) }
    @Synchronized fun chessDone(c: Context): Boolean { daily(c); return sp(c).getBoolean("chessDone", false) }
    @Synchronized fun pushDone(c: Context): Boolean { daily(c); return sp(c).getBoolean("pushDone", false) }
    @Synchronized fun skips(c: Context): Int { daily(c); return sp(c).getInt("skips", 0) }
    @Synchronized fun customUsed(c: Context): Boolean { daily(c); return sp(c).getBoolean("customUsed", false) }
    @Synchronized fun completed(c: Context): Int { daily(c); return sp(c).getInt("completed", 0) }
    @Synchronized fun result(c: Context): String { daily(c); return sp(c).getString("result", "") ?: "" }
    @Synchronized fun phase(c: Context): Int { daily(c); return sp(c).getInt("phase", 0) }
    @Synchronized fun reps(c: Context): Int { daily(c); return sp(c).getInt("reps", 0) }
    @Synchronized fun active(c: Context): String { daily(c); return sp(c).getString("active", NONE) ?: NONE }
    @Synchronized fun startersFinished(c: Context) = chessDone(c) && pushDone(c)

    @Synchronized fun current(c: Context): Quest? {
        daily(c)
        val s = sp(c)
        return when (active(c)) {
            CHESS -> Quest(-101, "Játssz le egy sakkmeccset",
                "Játssz végig egy valódi sakkpartit. A győzelem nem feltétel; a teljesítésedet te igazolod.",
                "♟️", QuestKind.CHESS, 1, 300)
            PUSH -> {
                val n = pushTarget(c)
                Quest(-102, n.toString() + " szabályos fekvőtámasz",
                    "Kamerával ellenőrzött, teljes le–fel fekvőtámasz. A napi cél 10 vagy 20 ismétlés.",
                    "💪", QuestKind.PUSHUPS, n, 600)
            }
            CUSTOM -> Quest(-103, s.getString("customName", "Saját feladat") ?: "Saját feladat",
                "15 perc megszakítás nélküli egyéni tevékenység, közben a figyelt közösségi appok zárolva vannak.",
                "✦", QuestKind.TIMED, 900, 600)
            EXTRA -> byId[s.getInt("extraId", -1)]
            else -> null
        }
    }

    @Synchronized fun availableStarters(c: Context): List<String> {
        daily(c)
        if (active(c) != NONE) return emptyList()
        val result = mutableListOf<String>()
        if (!chessDone(c)) result.add(CHESS)
        if (!pushDone(c)) result.add(PUSH)
        return result
    }

    private fun setActive(c: Context, item: String) {
        sp(c).edit().putString("active", item).putInt("phase", 0)
            .putInt("reps", 0).putLong("elapsed", 0L).putLong("last", 0L)
            .putString("result", "").commit()
    }

    @Synchronized fun chooseStarter(c: Context, item: String): Boolean {
        if (item !in availableStarters(c)) return false
        setActive(c, item)
        return true
    }

    /** 40 elemű kevert zsák; egy teljes körön belül nincs ismétlés. */
    @Synchronized private fun drawExtra(c: Context): Quest {
        val s = sp(c)
        val old = s.getInt("extraId", -1)
        val bag = s.getString("bag", "")!!.split(",")
            .mapNotNull { it.toIntOrNull() }.filter { it in byId }.toMutableList()
        if (bag.isEmpty()) {
            val weekend = java.time.LocalDate.now().dayOfWeek.value >= 6
            val daytime = java.time.LocalTime.now().hour in 8..19
            val prioritized = all.map { it to (random.nextInt(100) + when {
                weekend && daytime && it.id in setOf(4, 5, 26, 27, 28, 29, 30, 31, 33) -> 55
                !weekend && it.id in setOf(3, 7, 12, 13, 14, 15, 16, 23, 24, 25) -> 55
                !daytime && it.id in setOf(4, 5, 26, 27, 28, 33) -> -80
                else -> 0
            }) }.sortedByDescending { it.second }.map { it.first.id }
            bag.addAll(prioritized)
            if (bag.size > 1 && bag.first() == old) bag.add(bag.removeAt(0))
        }
        val id = bag.removeAt(0)
        s.edit().putInt("extraId", id).putString("bag", bag.joinToString(",")).commit()
        setActive(c, EXTRA)
        return byId.getValue(id)
    }

    /** A két napi kezdő quest után jöhetnek csak extra feladatok. */
    @Synchronized fun ensureQuest(c: Context): Quest? {
        current(c)?.let { return it }
        return if (startersFinished(c)) drawExtra(c) else null
    }

    @Synchronized fun startChess(c: Context): Boolean {
        if (active(c) != CHESS || phase(c) != 0) return false
        return sp(c).edit().putInt("phase", 1)
            .putLong("chessStartedAt", System.currentTimeMillis()).commit()
    }
    @Synchronized fun chessStartedAt(c: Context): Long {
        daily(c)
        return sp(c).getLong("chessStartedAt", 0L)
    }
    @Synchronized fun verifiedChess(c: Context): Boolean {
        if (active(c) != CHESS || phase(c) != 1) return false
        return finish(c, 300, "Sakkmeccs online ellenőrizve: +5 perc")
    }

    @Synchronized fun completeChess(c: Context): Boolean {
        if (active(c) != CHESS || phase(c) != 1) return false
        return finish(c, 300, "Sakkmeccs teljesítve: +5 perc")
    }

    /** Fekvő és extra fekvő quest: csak a kamerás mozgásfelismerés hívhatja. */
    @Synchronized fun rep(c: Context, id: Int): Boolean {
        val q = current(c) ?: return false
        if (q.id != id || (q.kind != QuestKind.PUSHUPS && q.kind != QuestKind.EXERCISE) || phase(c) != 0) return false
        val n = (reps(c) + 1).coerceAtMost(q.goal)
        sp(c).edit().putInt("reps", n).commit()
        return if (n == q.goal) finish(c, q.reward, "Fekvőtámaszok teljesítve: +" + duration(q.reward)) else false
    }

    /** Napi egyszer lecserélheted az aktív questet 15 perc egyéni feladatra. */
    @Synchronized fun useCustom(c: Context, name: String): Boolean {
        if (customUsed(c) || phase(c) != 0 || active(c) == NONE) return false
        if (name.trim().length < 3 || name.length > 80) return false
        val old = active(c)
        val e = sp(c).edit().putBoolean("customUsed", true)
            .putString("customName", name.trim())
            .putString("replacedStarter", old)
        e.commit()
        setActive(c, CUSTOM)
        return true
    }

    /** Naponta 3 skip, skipenként levonunk 5 percet. */
    @Synchronized fun skip(c: Context): Boolean {
        val active = active(c)
        if (active == NONE || phase(c) != 0 || skips(c) >= 3 || Store.bal(c) < 300) return false
        val s = sp(c)
        Store.add(c, -300)
        val e = s.edit().putInt("skips", skips(c) + 1)
            .putInt("phase", 2)
            .putString("result", "Kihagyva –5 percért. Jutalom nem jár.")
        if (active == CHESS) e.putBoolean("chessDone", true)
        if (active == PUSH) e.putBoolean("pushDone", true)
        if (active == CUSTOM) {
            when (s.getString("replacedStarter", "")) {
                CHESS -> e.putBoolean("chessDone", true)
                PUSH -> e.putBoolean("pushDone", true)
            }
        }
        e.commit()
        return true
    }

    @Synchronized fun startTimed(c: Context): Boolean {
        val q = current(c) ?: return false
        if (q.kind != QuestKind.TIMED || phase(c) != 0) return false
        return sp(c).edit().putInt("phase", 1)
            .putLong("elapsed", 0L)
            .putLong("last", SystemClock.elapsedRealtime()).commit()
    }

    /** A visszaszámlálást csak az aktív háttérvédelem működteti. */
    @Synchronized fun tick(c: Context) {
        val q = current(c) ?: return
        if (q.kind != QuestKind.TIMED || phase(c) != 1) return
        val state = sp(c)
        val now = SystemClock.elapsedRealtime()
        val last = state.getLong("last", 0L)
        val delta = if (last > 0 && now >= last) (now - last).coerceIn(0L, 1500L) else 0L
        val updated = (state.getLong("elapsed", 0L) + delta).coerceAtMost(q.goal * 1000L)
        state.edit().putLong("elapsed", updated).putLong("last", now).apply()
    }

    @Synchronized fun resetClock(c: Context) {
        if (phase(c) == 1) sp(c).edit().putLong("last", SystemClock.elapsedRealtime()).apply()
    }

    @Synchronized fun secondsLeft(c: Context): Int {
        val q = current(c) ?: return 0
        if (q.kind != QuestKind.TIMED) return 0
        val elapsed = sp(c).getLong("elapsed", 0L)
        return ((q.goal * 1000L - elapsed).coerceAtLeast(0L) + 999L).div(1000L).toInt()
    }

    @Synchronized fun isCustomTimerLocked(c: Context): Boolean = active(c) == CUSTOM && isTimerLocked(c)

    @Synchronized fun isTimerLocked(c: Context): Boolean =
        current(c)?.kind == QuestKind.TIMED && phase(c) == 1 && secondsLeft(c) > 0

    @Synchronized fun completeTimed(c: Context): Boolean {
        val q = current(c) ?: return false
        if (q.kind != QuestKind.TIMED || phase(c) != 1 || secondsLeft(c) > 0) return false
        return finish(c, q.reward, "Feladat teljesítve: +" + duration(q.reward))
    }

    private fun finish(c: Context, reward: Int, result: String): Boolean {
        if (phase(c) == 2) return false
        val a = active(c)
        val q = current(c)
        val e = sp(c).edit()
            .putInt("phase", 2)
            .putInt("completed", completed(c) + 1)
            .putString("result", result)
        if (a == CHESS) e.putBoolean("chessDone", true)
        if (a == PUSH) e.putBoolean("pushDone", true)
        if (a == CUSTOM) {
            when (sp(c).getString("replacedStarter", "")) {
                CHESS -> e.putBoolean("chessDone", true)
                PUSH -> e.putBoolean("pushDone", true)
            }
        }
        e.commit()
        val before = Store.bal(c)
        Store.add(c, reward)
        val studyIds = setOf(3, 6, 12, 13, 14, 15, 16)
        val statsType = if (q?.kind == QuestKind.TIMED && q.id in studyIds) "STUDY"
            else if (q?.kind == QuestKind.TIMED) "OTHER" else q?.kind?.name ?: "OTHER"
        ProgressStore.taskFinished(c, statsType, q?.goal ?: 0,
            (Store.bal(c) - before).coerceAtLeast(0))
        return true
    }

    /** A következő feladatot csak a jelenlegi quest befejezése/skip után generáljuk. */
    @Synchronized fun next(c: Context): Quest? {
        if (phase(c) != 2) return current(c)
        setActive(c, NONE)
        return ensureQuest(c)
    }
}
