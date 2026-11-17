package hu.fokusz

import android.content.Context
import java.time.LocalDate

object Store {
    const val DAILY = 1800 // 30 perc másodpercben
    // Gyakorlati, egész számokkal biztonságosan kezelhető műszaki plafon (~63 év),
    // nincs napi vagy játékalkalmakra vonatkozó korlátozás.
    const val MAX_SAFE_SECONDS = 2_000_000_000
    val BLOCKED = setOf(
        "com.zhiliaoapp.musically", "com.ss.android.ugc.trill", "com.instagram.android",
        "com.google.android.youtube", "com.snapchat.android", "com.twitter.android",
        "com.facebook.katana", "com.reddit.frontpage"
    )
    // További közösségi appok, melyek az aktív quest alatt blokkoltak.
    val QUEST_SOCIAL = setOf(
        "com.discord", "com.whatsapp", "org.telegram.messenger",
        "com.facebook.orca", "com.instagram.barcelona", "com.pinterest",
        "com.linkedin.android", "com.bereal.ft", "com.tumblr",
        "xyz.blueskyweb.app", "com.viber.voip", "com.skype.raider"
    )
    // Webes közösségi oldalak miatt az egyéni quest alatt böngészők sem használhatók.
    val BROWSERS = setOf(
        "com.android.chrome", "org.mozilla.firefox", "com.brave.browser",
        "com.microsoft.emmx", "com.opera.browser", "com.opera.mini.native",
        "com.sec.android.app.sbrowser", "com.duckduckgo.mobile.android",
        "com.mi.globalbrowser", "com.UCMobile.intl", "com.kiwibrowser.browser"
    )
    private fun sp(c: Context) = c.getSharedPreferences("f", 0)

    @Synchronized fun bal(c: Context): Int {
        val s = sp(c); val today = LocalDate.now().toString()
        if (s.getString("d", "") != today) s.edit().putString("d", today).putInt("b", DAILY).apply()
        return s.getInt("b", DAILY)
    }
    @Synchronized fun add(c:Context,sec:Int):Int {
        val old=bal(c).toLong()
        // Régebben az összes nagyobb egyenleget is visszavágta a 4-6 órás
        // felhasználói limitre, akár egy tét levonásakor is.
        val ceiling=if(sec>0)maxOf(old,FocusPrefs.maxBalance(c).toLong())
            else MAX_SAFE_SECONDS.toLong()
        val n=(old+sec.toLong()).coerceIn(0L,
            minOf(ceiling,MAX_SAFE_SECONDS.toLong())).toInt()
        sp(c).edit().putInt("b",n).commit()
        if(sec>0 || n%15==0)ProgressStore.updateWidget(c)
        return n
    }

    /** Gambling-nyeremény és időbank-kivét: nincs 4/6 órás megtakarítási plafon.
     * Long számításokkal a bet*2 túlcsordulását is elkerüljük.
     */
    @Synchronized fun addUncapped(c:Context,seconds:Long):Int {
        val old=bal(c).toLong()
        val n=(old+seconds).coerceIn(0L,MAX_SAFE_SECONDS.toLong()).toInt()
        sp(c).edit().putInt("b",n).commit()
        ProgressStore.updateWidget(c)
        return n
    }
}
