# Heti időelszámolás

A szerver számítja a vasárnap 23:59:59-es budapesti határidőt. A hétfő elején induló Cloud Scheduler-feladat lezárja a korábbi heteket. A tranzakcióban rögzített heti titok és a felhasználói azonosító egyetlen, torzítatlan sorsolást határoz meg. A szerver az egyedi heti eredményt visszaadja az alkalmazásnak.

A szerver rangsorának és záróegyenlegének alapja a határidőig sikeresen szinkronizált saját keret és saját bank összege. Az alkalmazás meglévő helyi időmotorja offline is működik. A még fel nem töltött módosítások a következő egyeztetéskor jelentkeznek. Az időegyenleg helyi eredete miatt a szerver az első kézből szinkronizált egyenleg hitelességét nem tudja függetlenül igazolni. A szerveren lezárt választás, rangsor és sorsolás utólag zárolt. A hétfői kezdőegyenleg a meglévő napi alapkeretet és bankkamatot is tartalmazza.

Teszteléshez telepítsd a függőségeket a `functions` mappából az `npm ci` paranccsal. A valószínűség- és időzónateszteket az `npm test` futtatja. A tranzakciós próbát a projekt gyökeréből indíthatod a `firebase emulators:exec --only auth,firestore,functions --project demo-fokusz "node functions/weekly.emulator.mjs"` paranccsal. A próba kizárólag a helyi emulátorokkal dolgozik.

Az éles telepítéshez Firebase-projektjogosultság és Cloud Functionst valamint Cloud Schedulert támogató csomag szükséges. A függvények a projekt gyökeréből telepíthetők az `firebase deploy --project fokusz-24aa1 --only functions:weekly` paranccsal.
