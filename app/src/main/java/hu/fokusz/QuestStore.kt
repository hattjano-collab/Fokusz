package hu.fokusz

import android.content.Context
import android.os.SystemClock
import java.security.SecureRandom

enum class QuestKind { TIMED, PUSHUPS }

data class Quest(
    val id: Int,
    val title: String,
    val description: String,
    val icon: String,
    val kind: QuestKind,
    val goal: Int,       // másodperc vagy szabályos fekvőtámasz
    val reward: Int      // bónusz képernyőidő másodpercben
)

object QuestStore {
    private fun t(id: Int, title: String, detail: String, icon: String, minutes: Int, reward: Int) =
        Quest(id, title, detail, icon, QuestKind.TIMED, minutes * 60, reward * 60)
    private fun p(id: Int, title: String, count: Int, reward: Int) =
        Quest(id, title, "Oldalról, teljes testtel a kamerában. Csak a szabályos, le-fel mozdulat számít.", "💪", QuestKind.PUSHUPS, count, reward * 60)

    val all = listOf(
        p(1, "20 szabályos fekvőtámasz", 20, 10),
        t(2, "Takaríts ki egy szobarészt", "Rakj rendet, törölj port vagy pakolj el egy kijelölt részen.", "🧹", 20, 20),
        t(3, "Tanulj koncentráltan", "Tanulj megszakítás nélkül egy tantárgyat, és jegyzetelj.", "📚", 20, 15),
        t(4, "Menj ki kosarazni", "Mozogj és gyakorolj a pályán, az időzítő közben is fut.", "🏀", 25, 20),
        t(5, "Sétálj a szabadban", "Tegyél egy nagyobb sétát a friss levegőn.", "🚶", 25, 15),
        t(6, "Olvass egy könyvet", "Olvass figyelmesen, telefon nélkül.", "📖", 15, 10),
        t(7, "Rakj rendet az asztalodon", "A felesleges dolgok kerüljenek a helyükre.", "🗂️", 10, 9),
        p(8, "10 szabályos fekvőtámasz", 10, 6),
        p(9, "15 szabályos fekvőtámasz", 15, 8),
        p(10, "25 szabályos fekvőtámasz", 25, 13),
        p(11, "30 szabályos fekvőtámasz", 30, 16),
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
    private fun sp(c: Context) = c.getSharedPreferences("fokusz_quests_v1", Context.MODE_PRIVATE)

    /** Egyetlen véletlenszerű küldetés; a 40 feladat ismétlés nélkül kerül elő. */
    @Synchronized fun current(c: Context): Quest {
        val state = sp(c)
        val chosen = byId[state.getInt("id", -1)]
        if (chosen != null) return chosen
        return drawNext(c)
    }

    private fun drawNext(c: Context): Quest {
        val state = sp(c)
        val previous = state.getInt("id", -1)
        val left = state.getString("bag", "")!!.split(",")
            .mapNotNull { it.toIntOrNull() }.filter { it in byId }
            .toMutableList()
        if (left.isEmpty()) {
            left.addAll(all.map { it.id })
            left.shuffle(random)
            if (left.size > 1 && left.first() == previous) {
                val x = left.removeAt(0); left.add(x)
            }
        }
        val chosen = left.removeAt(0)
        state.edit()
            .putInt("id", chosen)
            .putInt("phase", 0)
            .putInt("reps", 0)
            .putLong("elapsed", 0)
            .putLong("last", 0)
            .putString("bag", left.joinToString(","))
            .commit()
        return byId.getValue(chosen)
    }

    @Synchronized fun phase(c: Context) = sp(c).getInt("phase", 0)
    @Synchronized fun reps(c: Context) = sp(c).getInt("reps", 0)
    @Synchronized fun completed(c: Context) = sp(c).getInt("completed", 0)

    @Synchronized fun startTimed(c: Context): Boolean {
        val q = current(c)
        if (q.kind != QuestKind.TIMED || phase(c) != 0) return false
        return sp(c).edit().putInt("phase", 1).putLong("elapsed", 0)
            .putLong("last", SystemClock.elapsedRealtime()).commit()
    }

    /** Csak a működő, használati hozzáféréssel rendelkező védelmi szolgáltatás írhatja jóvá az időt. */
    @Synchronized fun tick(c: Context) {
        val q = current(c)
        if (q.kind != QuestKind.TIMED || phase(c) != 1) return
        val state = sp(c)
        val now = SystemClock.elapsedRealtime()
        val last = state.getLong("last", 0)
        val delta = if (last > 0 && now >= last) (now - last).coerceIn(0, 1300) else 0
        val newValue = (state.getLong("elapsed", 0L) + delta).coerceAtMost(q.goal * 1000L)
        state.edit().putLong("elapsed", newValue).putLong("last", now).apply()
    }

    @Synchronized fun resetClock(c: Context) {
        if (phase(c) == 1) sp(c).edit().putLong("last", SystemClock.elapsedRealtime()).apply()
    }

    @Synchronized fun secondsLeft(c: Context): Int {
        val q = current(c)
        if (q.kind != QuestKind.TIMED) return 0
        val elapsed = sp(c).getLong("elapsed", 0L)
        return ((q.goal * 1000L - elapsed).coerceAtLeast(0) + 999L).div(1000L).toInt()
    }

    @Synchronized fun isTimerLocked(c: Context): Boolean =
        current(c).kind == QuestKind.TIMED && phase(c) == 1 && secondsLeft(c) > 0

    /** Visszatérési érték csak akkor true, ha ez az esemény tényleg megszerezte a jutalmat. */
    @Synchronized fun completeTimed(c: Context): Boolean {
        val q = current(c)
        if (q.kind != QuestKind.TIMED || phase(c) != 1 || secondsLeft(c) > 0) return false
        return award(c, q.reward)
    }

    @Synchronized fun rep(c: Context, id: Int): Boolean {
        val q = current(c)
        if (q.id != id || q.kind != QuestKind.PUSHUPS || phase(c) != 0) return false
        val n = (reps(c) + 1).coerceAtMost(q.goal)
        sp(c).edit().putInt("reps", n).commit()
        return if (n == q.goal) award(c, q.reward) else false
    }

    private fun award(c: Context, reward: Int): Boolean {
        if (phase(c) == 2) return false
        sp(c).edit().putInt("phase", 2)
            .putInt("completed", completed(c) + 1).commit()
        Store.add(c, reward)
        return true
    }

    @Synchronized fun next(c: Context): Quest? =
        if (phase(c) == 2) drawNext(c) else null
}
