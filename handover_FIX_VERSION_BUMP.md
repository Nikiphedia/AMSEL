# Handover: FIX_VERSION_BUMP

**Datum:** 2026-03-31
**Status:** ERLEDIGT — Version-Bump auf v3, Build gruen

---

## Problem

Die neue `species_master.json` (11'565 Taxa, 23 Sprachen) hatte `"version": 2`. Die User-Kopie im Verzeichnis `~/Documents/AMSEL/species/` hatte ebenfalls `"version": 2` (alte Version mit 192 Taxa). Der Version-Check in `SpeciesRegistry.initialize()` sah "gleich" → ueberschrieb nicht → User sah weiterhin nur 192 Taxa.

## Was gemacht wurde

Beide Dateien von `"version": 2` auf `"version": 3` geaendert:

| Datei | Aenderung |
|-------|-----------|
| `src/main/resources/species/species_master.json` | `"version": 2` → `"version": 3` |
| `src/main/resources/species/region_sets.json` | `"version": 2` → `"version": 3` |

## Build-Status

```
./gradlew compileKotlin → BUILD SUCCESSFUL (772ms)
```

## Was NICHT gemacht wurde / offen

1. **Manueller App-Test** — App starten, pruefen ob im Log `species_master.json v3 aus JAR nach ... kopiert` erscheint
2. **Artenliste in UI** — verifizieren dass deutlich mehr als 179 Arten angezeigt werden
3. **Deutsche Namen** — pruefen ob deutsche Namen fuer mitteleuropaeische Arten vorhanden sind

## Geaenderte Dateien

| Datei | Aktion |
|-------|--------|
| `src/main/resources/species/species_master.json` | Zeile 2: version 2 → 3 |
| `src/main/resources/species/region_sets.json` | Zeile 2: version 2 → 3 |
