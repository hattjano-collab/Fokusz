package hu.fokusz

internal object SocialUnlockJokes {
    private val general = listOf(
        "A hüvelykujjad indulna. A naptárad hány percet enged?",
        "Gyors benézés? Írjuk le, mennyi az a gyors.",
        "A görgetés végtelen. Adjunk neki egy befejezést."
    )
    private val instagram = listOf(
        "Zuckerberg már teríti az asztalt. Ma hány percet viszel neki?",
        "Csak az üzeneteid? Ezt a Reels is hallotta.",
        "Az algoritmus szerint most pont ráérsz. Szerinted is?"
    )
    private val tiktok = listOf(
        "Egy videó belefér. A következő negyvenkettőnek mit mondjunk?",
        "A For You oldal ma is egész nap ráér. Te meddig?",
        "Ha megint megtanulod, hogyan kell alpakát nyírni, legalább szólj."
    )
    private val facebook = listOf(
        "Mark már húzza is elő a széket. Meddig maradsz?",
        "A helyi csoportban valaki megint hallott egy nagy durranást. Ez hány percet ér?"
    )
    private val apps = mapOf(
        "com.instagram.android" to instagram, "com.instagram.lite" to instagram,
        "com.zhiliaoapp.musically" to tiktok, "com.ss.android.ugc.trill" to tiktok,
        "com.zhiliaoapp.musically.go" to tiktok, "com.ss.android.ugc.tiktok.lite" to tiktok,
        "com.facebook.katana" to facebook, "com.facebook.lite" to facebook,
        "com.google.android.youtube" to listOf(
            "Egy oktatóvideó. Aztán valahogy hajnali egykor már egy traktort restaurálnak.",
            "A videó 4 perces. A kommentmezőnek nincs stáblistája."
        ),
        "com.reddit.frontpage" to listOf(
            "Egy gyors válaszért jöttél. A 2014-es vitát is végigolvasod?",
            "Valaki téved az interneten. Hány percet adsz az ügynek?"
        ),
        "com.snapchat.android" to listOf(
            "A lángocska vár. Mennyit adsz neki az órádból?",
            "Egy plafonfotóhoz mennyi időt írhatunk be?"
        ),
        "com.twitter.android" to listOf(
            "A világ összes véleménye egy helyen. Mennyi fér bele?",
            "Mielőtt megnyitod: biztos szeretnéd tudni, min veszekszenek ma?"
        )
    )
    private val last = mutableMapOf<String, String>()

    fun next(pkg: String, expiredMinutes: Int?): String {
        val key = if (expiredMinutes == null) pkg else "expired"
        val options = if (expiredMinutes == null) apps[pkg] ?: general else listOf(
            "Lejárt a $expiredMinutes perc. A hosszabbításról már te döntesz.",
            "Az algoritmus kérne hosszabbítást. Te is?"
        )
        return options.filter { it != last[key] }.random().also { last[key] = it }
    }
}
