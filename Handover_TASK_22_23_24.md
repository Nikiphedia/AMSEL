# Handover: Tasks 22 + 23 + 24 + Deprecation-Fix

**Datum:** 2026-03-31
**Version vorher:** 0.0.5 (Tasks 20+21+QF)
**Build-Status:** `./gradlew compileKotlin` — BUILD SUCCESSFUL, 0 Warnungen

---

## Was wurde gemacht

### Deprecation-Fix
- `Icons.Default.KeyboardArrowRight` → `Icons.AutoMirrored.Filled.KeyboardArrowRight` in:
  - `TabDatenbank.kt` (Familien-Expand/Collapse)
  - `DownloadDialog.kt` (Kategorie-Expand/Collapse)
- `AnnotationPanel.kt` nutzt nur KeyboardArrowUp/Down — nicht betroffen
- Zusaetzlich: `menuAnchor()` → `menuAnchor(MenuAnchorType.PrimaryNotEditable)` in TabDatenbank

### Task 22: Audio On-Demand Download
**Infrastruktur war bereits vorhanden** (`downloadAudioOnDemand` in ReferenceDownloader, `playReferenceAudio` in PlaybackManager). Erweiterung:

- **PlaybackManager.kt**: Neue State-Felder `playingReferenceId`, `downloadingReferenceId`
  - Download-State wird waehrend `downloadAudioOnDemand()` gesetzt und danach geloescht
  - `playingReferenceId` wird bei Playback-Start gesetzt, bei Stop automatisch geloescht
  - Toggle-Verhalten: Klick auf spielende Referenz stoppt sie
- **SonogramGallery.kt**: 3-State Play-Button pro ThumbnailCard
  - Idle: Play-Pfeil (schwarz halbtransparent)
  - Downloading: CircularProgressIndicator (klein, weiss)
  - Playing: Stop-Icon (gruen hinterlegt)
- **CompareUiState**: `playingReferenceId`, `downloadingReferenceId` Felder hinzugefuegt
- **CompareViewModel**: State-Propagation via combine() erweitert (Playback → 5 Felder)
- **CompareScreen**: Neue Parameter an SonogramGallery durchgereicht

### Task 23: Artensets / Regionfilter

**Neue Dateien:**
- `src/main/resources/species/region_sets.json` — 4 Sets mit Platzhalter-Artenlisten:
  - `all`: Leere species-Liste = keine Einschraenkung
  - `ch_breeding`: Schweizer Brutvoegel (~5 Beispielarten)
  - `ch_all`: Schweiz komplett (~7 Beispielarten)
  - `central_europe`: Mitteleuropa (~9 Beispielarten)
  - **WICHTIG: Artenlisten muessen noch vollstaendig befuellt werden (Task 25)**
- `src/main/kotlin/.../data/RegionSetRegistry.kt` — Singleton-Registry
  - Laedt Sets aus JSON-Resource beim Init
  - `isSpeciesInSet(setId, species)`: Unterstuetzt Unterstrich + Leerzeichen
  - Fallback auf "all" wenn JSON fehlt oder kaputt

**Geaenderte Dateien:**
- **Settings.kt**: `activeRegionSet: String = "all"` in AppSettings
- **TabDatenbank.kt**: ExposedDropdownMenuBox fuer Artenset-Auswahl
  - Zeigt Name + Beschreibung + Artenzahl
  - Zwischen Rescan-Button und Qualitaetsfiltern platziert
- **UnifiedSettingsDialog.kt**: `activeRegionSet` State, wird beim Speichern persistiert
- **ReferenceDownloader.kt**: `startDownload()` filtert speciesList vor Iteration nach aktivem Artenset
- **ClassificationManager.kt**: `searchSimilar()` filtert refRecordings nach aktivem Artenset
  - BirdNET-Scan selbst wird NICHT eingeschraenkt (Post-Filter wie spezifiziert)

### Task 24: Audio Batch-Download

**ReferenceDownloader.kt — Neue Methoden:**
- `batchDownloadAudio(onProgress, cancellationToken)` → `BatchResult`
  - Laedt alle Curated-Referenzen mit XC-ID im aktiven Artenset
  - 500ms Rate-Limiting zwischen Downloads
  - Skip wenn Audio bereits existiert (> 0 Bytes)
  - Cooperative Cancellation via `cancellationToken`
- `startAudioBatchDownload(onProgress, onComplete, onCancel)` — startet als Job
- `cancelAudioBatchDownload()` — bricht Job ab
- `getAudioStats(regionSetId)` → `Pair<existing, total>` — zaehlt Audio-Dateien
- `BatchResult(downloaded, skipped, failed, cancelled)` Data Class

**ReferenceLibrary.kt:**
- `getAllRecordings(): List<ReferenceRecording>` — flat list aller Aufnahmen

**TabDatenbank.kt — Audio-Batch UI:**
- Status-Anzeige: "X / Y Audio-Dateien vorhanden"
- Fortschrittsbalken mit aktuellem Download-Text
- Download / Abbrechen Buttons
- Ergebnis-Anzeige nach Abschluss

**UnifiedSettingsDialog.kt:**
- Audio-Batch State-Management (isDownloadingAudio, progress, result)
- LaunchedEffect fuer Audio-Stats bei Artenset-Wechsel
- Neue Callback-Parameter an UnifiedSettingsDialog-Signatur

**CompareViewModel.kt:**
- `startAudioBatchDownload()`, `cancelAudioBatchDownload()`, `getAudioStats()` Delegation

**CompareScreen.kt:**
- Neue Callbacks an UnifiedSettingsDialog durchgereicht

---

## Datei-Uebersicht

| Datei | Aenderung |
|-------|-----------|
| `resources/species/region_sets.json` | **NEU** — Artenset-Definitionen |
| `data/RegionSetRegistry.kt` | **NEU** — Artenset-Registry |
| `data/Settings.kt` | +activeRegionSet Feld |
| `data/reference/ReferenceDownloader.kt` | +Artenset-Filter, +batchDownloadAudio, +getAudioStats |
| `data/reference/ReferenceLibrary.kt` | +getAllRecordings() |
| `ui/compare/ClassificationManager.kt` | +Artenset-Filter in searchSimilar |
| `ui/compare/CompareViewModel.kt` | +playingReferenceId/downloadingReferenceId, +batch-Methoden |
| `ui/compare/CompareScreen.kt` | +Wiring fuer neue Features |
| `ui/compare/PlaybackManager.kt` | +Referenz-Audio State-Tracking |
| `ui/results/SonogramGallery.kt` | +3-State Play-Button |
| `ui/settings/TabDatenbank.kt` | +Icon-Fix, +Artenset-Dropdown, +Audio-Batch UI, +menuAnchor Fix |
| `ui/settings/DownloadDialog.kt` | +Icon-Fix |
| `ui/settings/UnifiedSettingsDialog.kt` | +Artenset-State, +Audio-Batch State/Wiring |

---

## Offene Punkte / Naechste Schritte

1. **region_sets.json befuellen** (Task 25: Species Master Table)
   - Aktuell nur 5-9 Beispielarten pro Set
   - ch_breeding braucht ~210, ch_all ~420, central_europe ~550 Arten
   - Format: Unterstrich statt Leerzeichen ("Parus_major")

2. **BirdNET Post-Filter UI** (optional)
   - BirdNET-Scan erkennt alle Arten (kein Pre-Filter)
   - Ergebnisse koennten optional auf aktives Artenset eingeschraenkt werden
   - Aktuell: kein Post-Filter auf BirdNET-Ergebnisse (nur auf Referenz-Suche)

3. **Offline-Test Audio-Download**
   - downloadAudioOnDemand nutzt XC-API → braucht Internet
   - Fehlerbehandlung bei Timeout/Netzwerkfehler vorhanden (return null, logger.warn)

4. **download_categories.json bleibt parallel**
   - Wird weiterhin fuer die Artenliste im Download-Bereich genutzt
   - region_sets.json ergaenzt (filtert), ersetzt nicht

---

## Testfaelle

### Task 22
- [ ] Play-Button auf Referenz → MP3 wird heruntergeladen und abgespielt
- [ ] Zweiter Klick auf gleiche Referenz → Wiedergabe stoppt
- [ ] Play auf andere Referenz → vorherige Wiedergabe stoppt
- [ ] Download-Fehler → Button geht zurueck auf Idle, kein Crash
- [ ] Waehrend Download: Spinner statt Play-Icon sichtbar

### Task 23
- [ ] Artenset "Alle" → keine Einschraenkung bei Download und Referenzsuche
- [ ] Artenset "CH Brutvoegel" → nur diese Arten in Downloads und Ergebnissen
- [ ] Artenset-Wechsel → wird in Settings persistiert
- [ ] BirdNET-Scan erkennt auch Arten ausserhalb des Sets
- [ ] region_sets.json fehlt → Fallback auf "all" (kein Crash)

### Task 24
- [ ] Batch-Download laed nur fehlende Audio-Dateien
- [ ] Fortschrittsanzeige zeigt korrekte Zahlen
- [ ] Abbrechen stoppt den Download sauber
- [ ] Bereits vorhandene Dateien werden gezaehlt und uebersprungen
- [ ] Status-Zaehler aktualisiert sich nach Abschluss

### Deprecation-Fix
- [x] Keine Compiler-Warnungen bezueglich deprecated Icons
- [x] Keine Compiler-Warnungen bezueglich menuAnchor
