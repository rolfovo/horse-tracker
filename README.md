# Horse Tracker (Android MVP)

MVP aplikace pro záznam jízdy na koni + vizualizaci nad OSM mapou (MapLibre) s barvou trasy podle rychlosti, ukládáním tras (vč. waypointů) a režimem „jet po původní trase“ (včetně opačného směru).

## Co to umí (MVP)
- OSM mapa (raster tiles) přes MapLibre `MapView`
- Záznam polohy na pozadí přes **foreground service** (nespí při zhasnutém displeji)
- Barva čáry trasy se mění podle rychlosti (segmenty se stylují podle `speed_mps`)
- Waypoint během jízdy (tlačítko „Bod“)
- Výběr koně po startu + statistiky per kůň (čas, vzdálenost, průměrná/max rychlost, počet jízd)
- Uložení/načtení trasy v **GPX** (v interním úložišti aplikace)
- „Follow route“: zobrazení vzdálenosti od trasy + progres po trase; možnost otočit trasu (reverse)

## Důležité poznámky (Android baterka)
„Nesmí se to uspávat na pozadí“ na Androidu typicky znamená:
- běžet jako **foreground service** s notifikací (`FOREGROUND_SERVICE_TYPE_LOCATION`)
- případně uživatele navést na vypnutí optimalizace baterie pro aplikaci

V MVP je záznam řešen přes foreground service a volitelný `PARTIAL_WAKE_LOCK` (zapíná se jen při aktivním záznamu).

## Oprávnění (Android 10+)
- Android 10+ vyžaduje pro dlouhodobé trackování i `ACCESS_BACKGROUND_LOCATION` (uživatel ho často musí povolit zvlášť v nastavení aplikace).
- U některých výrobců (Samsung/Xiaomi/…) je potřeba ručně vypnout „battery optimization“ pro spolehlivý záznam.

## Build / run (doporučeno přes Android Studio)
1) Otevři složku `android/horse-tracker/` v Android Studio.
2) Nech IDE stáhnout Gradle a závislosti.
3) Spusť na zařízení (Android 10+ doporučeno).

Pokud MapLibre nebo jiné dependency neprojdou, uprav verze v `android/horse-tracker/build.gradle.kts`.

## OSM tiles
V kódu je pro demo použita `https://tile.openstreetmap.org/{z}/{x}/{y}.png`. Pro produkci si prosím nastav vlastní tile server / komerční provider dle pravidel používání OSM.

## Formát uložené jízdy
Ukládá se `*.gpx` do `context.filesDir/rides/`:
- trackpointy v `<trkpt>` (+ `<extensions><speed_mps>` a `<accuracy_m>`)
- waypointy v `<wpt>`

Pro statistiky se ukládá i sidecar `*.meta.json` (jen interně, bez hesel / bez citlivých dat).
