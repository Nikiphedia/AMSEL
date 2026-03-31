# Handover: region_sets.json nach User-Verzeichnis

## Geaenderte Dateien

| Datei | Aenderung |
|-------|-----------|
| `src/main/kotlin/ch/etasystems/amsel/data/RegionSetRegistry.kt` | `init {}` Block entfernt. Neue `initialize(amselDataDir: File)` Methode mit JAR-zu-User-Kopie-Logik (identisch zu SpeciesRegistry-Pattern). Neue Hilfsmethoden: `getJarVersion()`, `getUserFileVersion()`, `loadFromFile()`, `loadFromResource()`, `parseSetsJson()`. |
| `src/main/kotlin/ch/etasystems/amsel/Main.kt` | Import `RegionSetRegistry` hinzugefuegt. `RegionSetRegistry.initialize(amselDataDir)` direkt nach `SpeciesRegistry.initialize(amselDataDir)`. |

## Nicht geaendert

- `species_master.json` / `SpeciesRegistry.kt` (bereits korrekt)
- `region_sets.json` Inhalt (hat bereits `"version": 2`)
- `Settings.kt`, `TabDatenbank.kt` etc. (nutzen RegionSetRegistry ueber bestehende API, keine Aenderung noetig)

## Build-Status

```
./gradlew compileKotlin → BUILD SUCCESSFUL
```

## Laufzeitverhalten

### Erster Start (User-Kopie existiert nicht)
1. `RegionSetRegistry.initialize()` erkennt: `~/Documents/AMSEL/species/region_sets.json` fehlt
2. Kopiert aus JAR-Ressource (`/species/region_sets.json`) nach User-Dir
3. Log: `region_sets.json v2 aus JAR nach C:\Users\nleis\Documents\AMSEL\species\region_sets.json kopiert`
4. Laedt aus User-Kopie

### Folgestarts (User-Kopie vorhanden, gleiche Version)
1. Vergleicht JAR-Version (2) mit User-Version (2) → gleich
2. Kein Kopieren, laedt direkt aus User-Kopie
3. Manuelle Aenderungen des Benutzers bleiben erhalten

### JAR-Update (neue Version in JAR)
1. JAR-Version (z.B. 3) > User-Version (2) → ueberschreibt User-Kopie
2. Log: `region_sets.json v3 aus JAR nach ... kopiert`

### Fallback-Kette
1. User-Datei → primaer
2. JAR-Ressource → wenn User-Datei nicht lesbar
3. Fallback "all" Set → wenn auch JAR fehlschlaegt

## Zieldateistruktur

```
~/Documents/AMSEL/species/
  species_master.json     (bereits vorhanden)
  region_sets.json        (NEU — kopiert aus JAR beim ersten Start)
```
