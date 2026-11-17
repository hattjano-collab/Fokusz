# Fókusz Edző – webes kamerás prototípus

Az önálló index.html weboldal a böngészőben, a felhasználó készülékén elemzi a kameraképet.
A kamera HTTPS-en vagy localhost-on indítható, és a MediaPipe-modell első alkalommal letöltődik.

## Fekvőtámasz-számlálás: ugyanaz a döntési logika

| Paraméter | Android ML Kit | Web MediaPipe |
| --- | --- | --- |
| Szükséges testpontok | Váll, könyök, csukló (egy oldalon) | Váll 11/12, könyök 13/14, csukló 15/16 |
| Minimális láthatóság | 0,38 | 0,38 |
| Leengedés | Könyökszög legfeljebb 120° | Könyökszög legfeljebb 120° |
| Kinyomás | Könyökszög legalább 145° | Könyökszög legalább 145° |
| Működés | fent → lent → fent | fent → lent → fent |
| Védelmi idő | 450 ms | 450 ms |
| Teljes test / csípő / boka | Nem szükséges | Nem szükséges |

A weben nem futtatható közvetlenül az Androidos ML Kit kameraelemző.
A webes prototípus MediaPipe Pose Landmarkert használ, ami ugyanazon három testpont alapján
futtatja ugyanazokat a szögszabályokat. A két modell pontossága eltérhet.

## Android-beépítés később

- A meglévő PushupCamera.kt megőrizheti a CameraX + ML Kit detektálást.
- A webes felület WebView-ba tölthető, a natív ML Kit adatokat pedig
  Android → JavaScript eseményeken keresztül lehet megjeleníteni.
  Így az app ténylegesen ugyanazt a meglévő kameradetektort használhatja.
- A weboldal jelenleg nem ad automatikusan Android-/csapatperceket.

## GitHub Pages közzététel

GitHub tárhelyen: Settings → Pages → Build and deployment → Source = GitHub Actions.
Ezután Actions → Fókusz Edző Weboldal → Run workflow.

A GitHub Pages először a tulajdonos egyszeri engedélyezését igényli.
