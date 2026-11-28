# Fókusz (Android)
A korábbi, AccessibilityService-t használó megoldás helyett az alkalmazás a használati adatok és az alkalmazások feletti megjelenítés engedélyével figyeli a kiválasztott appokat, és az időkeret lejárta után tájékoztató képernyővel fedi le őket.

A működéshez az alkalmazás megnyitása után két külön engedélyt kell megadni, majd a "Védelem elindítása" gombot megnyomni. A Xiaomi energiatakarékos funkciói a háttérfigyelést leállíthatják, ezért a korlátozás nélküli akkumulátorhasználat javasolt.

A blokk nem rendszerszintű tiltás; a felhasználó a telefon beállításaiban leállíthatja a szolgáltatást. Az időmérés és a játék könyvelése helyben fut. A heti elszámolás a saját keretet, a banki egyenleget és a választást a Firebase-szerverrel szinkronizálja. A szerver telepítését és tesztjeit a [heti elszámolás leírása](functions/README.md) tartalmazza.

A ZIP-ből APK-t a GitHub Actions build készít, a Debug APK telepíthető.
