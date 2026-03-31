# REFACTORING_PLAN.md — AMSEL

> Erstellt: 2026-03-30
> Basierend auf: ARCHITECTURE.md (21k LOC, 67 Files, 21 Packages)
> **Kein Code wird geändert. Nur Planung.**

---

## 1. Priorisierte Massnahmen

### P1 — Kritisch (vor v1.0 Release)

| Nr | Kurztitel | Betroffene Files | Risiko | Aufwand |
|----|-----------|-----------------|--------|---------|
| P1-1 | Zirkuläre Abhängigkeit `data` ↔ `ui.compare` auflösen | ProjectFile.kt, CompareViewModel.kt | Mittel | Klein |
| P1-2 | Schichtenverletzung `core.similarity` → `data` entkoppeln | SimilarityEngine.kt | Mittel | Mittel |
| P1-3 | CompareViewModel.kt (2889 LOC) aufteilen | CompareViewModel.kt + neue Files | Hoch | Gross |

### P2 — Wichtig (für Code-Qualität)

| Nr | Kurztitel | Betroffene Files | Risiko | Aufwand |
|----|-----------|-----------------|--------|---------|
| P2-1 | Debug-Code und hartcodierte Pfade entfernen | CompareViewModel.kt, ReferenceSonogramPanel.kt | Niedrig | Klein |
| P2-2 | `FilterConfig` in eigene Datei extrahieren | FilterPipeline.kt → FilterConfig.kt | Niedrig | Klein |
| P2-3 | Image-Loading-Duplikation eliminieren | SonogramGallery.kt, ReferenceSonogramPanel.kt | Niedrig | Klein |
| P2-4 | Frequenz-Formatierungs-Duplikation eliminieren | ExportSettingsDialog.kt, ComparisonSettingsDialog.kt, UnifiedSettingsDialog.kt | Niedrig | Klein |
| P2-5 | Inline-Artenliste externalisieren | DownloadDialog.kt | Niedrig | Klein |
| P2-6 | SonogramCanvas.kt (1363 LOC) aufteilen | SonogramCanvas.kt | Mittel | Mittel |
| P2-7 | CompareScreen.kt (1116 LOC) aufteilen | CompareScreen.kt | Mittel | Mittel |
| P2-8 | UnifiedSettingsDialog.kt (1362 LOC) aufteilen | UnifiedSettingsDialog.kt | Mittel | Mittel |
| P2-9 | SpeciesTranslations Inline-Map externalisieren | SpeciesTranslations.kt | Niedrig | Klein |

### P3 — Nice-to-have (für v1.1+)

| Nr | Kurztitel | Betroffene Files | Risiko | Aufwand |
|----|-----------|-----------------|--------|---------|
| P3-1 | Singleton-Pattern vereinheitlichen | MelSpectrogram.kt, SimilarityEngine.kt, AudioPlayer.kt u.a. | Niedrig | Klein |
| P3-2 | Testbarkeit verbessern (DI vorbereiten) | Diverse Singletons | Mittel | Gross |
| P3-3 | Umbenennung BirdSono → AMSEL (`ch.etasystems.amsel`) | Alle 67 Files + Build-Config | Mittel | Gross |

---

## 2. Detailplan pro Massnahme

---

#### P1-1: Zirkuläre Abhängigkeit `data` ↔ `ui.compare` auflösen

**Betroffene Files:** `data/ProjectFile.kt`, `ui/compare/CompareViewModel.kt`

**Problem:** `ProjectFile.kt` importiert `VolumePoint` und `AuditEntry` aus `ui.compare.CompareViewModel.kt`. Gleichzeitig importiert `CompareViewModel.kt` aus `data/`. Das erzeugt eine zirkuläre Abhängigkeit zwischen Daten- und UI-Schicht.

**Lösung:** `VolumePoint`, `AuditEntry` und die Hilfsfunktion `gainDbToLinear()` in ein neues File `core/model/ProjectModel.kt` verschieben. Beide Seiten importieren dann aus `core.model`.

**Schritte:**
1. Neues Package `com.birdsono.core.model` anlegen
2. Datei `ProjectModel.kt` erstellen mit `VolumePoint`, `AuditEntry`, `gainDbToLinear()` und der Extension `List<VolumePoint>.gainAtTime()`
3. In `ProjectFile.kt`: Imports von `com.birdsono.ui.compare.VolumePoint` und `com.birdsono.ui.compare.AuditEntry` → `com.birdsono.core.model.*`
4. In `CompareViewModel.kt`: Definition der Klassen entfernen, Imports auf `com.birdsono.core.model.*` umstellen
5. `CompareUiState` verweist weiterhin auf die Typen — nur der Import-Pfad ändert
6. Kompilieren und testen

**Risiko:** Mittel — Serialisierung (`@Serializable`) muss sauber mitkommen. Bestehende `.amsel.json`-Projektdateien enthalten serialisierte `VolumePoint`/`AuditEntry` — das Format ändert sich nicht, nur der Package-Name der Klassen. Kotlinx.serialization nutzt `@SerialName`, nicht den voll-qualifizierten Klassennamen, daher kein Kompatibilitätsproblem.

**Aufwand:** Klein

---

#### P1-2: Schichtenverletzung `core.similarity` → `data` entkoppeln

**Betroffene Files:** `core/similarity/SimilarityEngine.kt`

**Problem:** `SimilarityEngine` (core-Schicht) importiert direkt `OfflineCache`, `CacheEntry`, `XenoCantoApi` und `XenoCantoRecording` aus der `data`-Schicht. Core-Module sollten nicht von Data abhängen.

**Lösung:** Dependency Inversion — Interfaces in `core` definieren, Implementierung bleibt in `data`.

**Schritte:**
1. In `core/similarity/` ein Interface `FeatureCacheProvider` erstellen:
   ```kotlin
   interface FeatureCacheProvider {
       fun loadAllFeatures(metricKey: String): Map<String, ByteArray>
       fun saveFeatures(metricKey: String, id: String, features: ByteArray)
   }
   ```
2. In `core/similarity/` ein Interface `RecordingProvider` erstellen:
   ```kotlin
   interface RecordingProvider {
       suspend fun searchBySpecies(query: String): List<RecordingInfo>
       suspend fun downloadAudio(recording: RecordingInfo): File
   }
   ```
   Mit `RecordingInfo` als schlankes data class in `core/similarity/`
3. `SimilarityEngine` auf Konstruktor-Parameter umstellen: `class SimilarityEngine(cache: FeatureCacheProvider, api: RecordingProvider)`
4. In `data/`: `OfflineCache` implementiert `FeatureCacheProvider`, Wrapper um `XenoCantoApi` implementiert `RecordingProvider`
5. Instanziierung in `CompareViewModel` anpassen (dort sind sowohl `core` als auch `data` verfügbar)
6. Imports aus `data/` in `SimilarityEngine.kt` entfernen

**Risiko:** Mittel — Die Online-Fallback-Logik in `SimilarityEngine.compare()` muss sauber auf das Interface abstrahiert werden. Rate-Limiting (200ms) bleibt in der `RecordingProvider`-Implementierung.

**Aufwand:** Mittel

---

#### P1-3: CompareViewModel.kt (2889 LOC) aufteilen

**Betroffene Files:** `ui/compare/CompareViewModel.kt` (+ 5–6 neue Files)

**Problem:** `CompareViewModel.kt` enthält 2889 LOC, 48+ StateFlow-Felder und 60+ Methoden. Es vereint Audio-Import, Playback, FFT-Berechnung, Filtering, Annotationen, Similarity-Suche, BirdNET-Klassifizierung, Export, Volume-Envelope und Projekt-Management in einer einzigen Klasse.

**Lösung:** Aufspaltung in Domain-Manager-Klassen (siehe Abschnitt 3).

**Risiko:** Hoch — Das ViewModel ist der zentrale Knoten der gesamten App. Jeder Fehler bei der Aufspaltung kann Regressions verursachen.

**Aufwand:** Gross

→ **Detailierter Plan in Abschnitt 3**

---

#### P2-1: Debug-Code und hartcodierte Pfade entfernen

**Betroffene Files:**
- `CompareViewModel.kt` (Zeilen ~1530, 1567, 1636, 2030, 2117, 2256)
- `ReferenceSonogramPanel.kt` (Zeilen ~41, 52)

**Problem:** Debug-Logging mit `FileWriter` schreibt in hartcodierte Pfade:
- `D:/80002/birdsono/export_debug.log` ← **absoluter Entwickler-Pfad!**
- `$HOME/Documents/AMSEL/compare_debug.log`
- `$HOME/Documents/AMSEL/fullscan_debug.log`
- `$HOME/Documents/AMSEL/export_debug.log`

Diese Logs wachsen unbegrenzt, belasten die Performance und leaken möglicherweise Nutzerdaten.

**Lösung:** Alle `FileWriter`-Debug-Blöcke entfernen. Stattdessen `SLF4J` (bereits als Dependency vorhanden) mit `logger.debug()` verwenden. SLF4J-simple schreibt nach stderr und kann via `simplelogger.properties` konfiguriert werden.

**Schritte:**
1. In `CompareViewModel.kt`: Alle `FileWriter("...debug.log")` Blöcke finden und durch `logger.debug()` ersetzen oder ganz entfernen
2. In `ReferenceSonogramPanel.kt`: `FileWriter`-Logging entfernen, relevante Fehler via `logger.warn()` ausgeben
3. Im Projekt-Root die Datei `export_debug.log` aus `.gitignore` entfernen (falls vorhanden) oder löschen
4. Sicherstellen, dass keine weiteren `FileWriter`-Debug-Stellen existieren (grep)

**Risiko:** Niedrig — Es wird nur Debug-Code entfernt, keine Funktionalität.

**Aufwand:** Klein

---

#### P2-2: `FilterConfig` in eigene Datei extrahieren

**Betroffene Files:** `core/filter/FilterPipeline.kt` → neues `core/filter/FilterConfig.kt`

**Problem:** `FilterConfig` (data class) ist in `FilterPipeline.kt` definiert, wird aber in 10+ Files importiert (UI-Komponenten, Data-Module, ViewModel). Die Klasse gehört konzeptionell nicht in die Pipeline-Implementierung.

**Lösung:** `FilterConfig` in eigene Datei `core/filter/FilterConfig.kt` verschieben.

**Schritte:**
1. `FilterConfig` data class aus `FilterPipeline.kt` ausschneiden
2. Neue Datei `core/filter/FilterConfig.kt` mit gleichem Package und `@Serializable` Annotation
3. Imports in allen Konsumenten prüfen — da das Package gleich bleibt (`com.birdsono.core.filter`), müssen die meisten Imports nicht geändert werden
4. Kompilieren

**Risiko:** Niedrig

**Aufwand:** Klein

---

#### P2-3: Image-Loading-Duplikation eliminieren

**Betroffene Files:** `ui/results/SonogramGallery.kt`, `ui/results/ReferenceSonogramPanel.kt`

**Problem:** Robustes Image-Loading mit Fallback-Strategien (file:// → Pfad → HTTP → Toolkit → Downsample → Korrupt-Erkennung) ist in beiden Files unabhängig implementiert.

**Lösung:** Gemeinsame Utility-Klasse `ui/results/ImageLoader.kt` erstellen.

**Schritte:**
1. Neue Datei `ui/results/ImageLoader.kt` erstellen
2. Funktionen extrahieren: `loadBufferedImage(url: String): BufferedImage?`, `isImageBlack(img: BufferedImage): Boolean`, `toComposeImageBitmap(img: BufferedImage): ImageBitmap`
3. Fallback-Kette konsolidieren: file → path → http → toolkit → downsample
4. In `SonogramGallery.kt` und `ReferenceSonogramPanel.kt` eigene Implementierungen durch Aufrufe an `ImageLoader` ersetzen
5. Debug-Logging in `ImageLoader` via SLF4J (nicht FileWriter)

**Risiko:** Niedrig — Die Logik ist bereits getestet, wird nur zusammengeführt.

**Aufwand:** Klein

---

#### P2-4: Frequenz-Formatierungs-Duplikation eliminieren

**Betroffene Files:** `ui/settings/ExportSettingsDialog.kt`, `ui/settings/ComparisonSettingsDialog.kt`, `ui/settings/UnifiedSettingsDialog.kt`

**Problem:** `formatFreq()`, `formatKHz()`, `parseKHzToHz()` Funktionen existieren in mehreren Settings-Dialogen.

**Lösung:** Utility-Funktionen in eine Datei `ui/util/FrequencyFormat.kt` extrahieren.

**Schritte:**
1. Neue Datei `ui/util/FrequencyFormat.kt` erstellen
2. Alle Varianten von `formatFreq`, `formatKHz`, `parseKHzToHz` zusammenführen
3. In den Settings-Dialogen durch Imports ersetzen
4. Kompilieren

**Risiko:** Niedrig

**Aufwand:** Klein

---

#### P2-5: Inline-Artenliste externalisieren

**Betroffene Files:** `ui/settings/DownloadDialog.kt`

**Problem:** Die Funktion `buildCategories()` enthält ~250 Zeilen hartcodierte Artenlisten (32 Familien, 180+ Arten mit wissenschaftlichen und deutschen Namen). Neue Arten erfordern Code-Änderungen.

**Lösung:** Artenliste in eine JSON-Datei `resources/species/download_categories.json` auslagern.

**Schritte:**
1. JSON-Schema definieren: `[{ "family": "Drosseln (Turdidae)", "species": [{ "scientific": "Turdus merula", "german": "Amsel" }, ...] }]`
2. JSON-Datei `resources/species/download_categories.json` erstellen
3. In `DownloadDialog.kt`: `buildCategories()` durch JSON-Laden ersetzen (kotlinx.serialization)
4. `buildCategories()` Funktion entfernen
5. Testen: Dialog öffnen, Arten prüfen

**Risiko:** Niedrig

**Aufwand:** Klein

---

#### P2-6: SonogramCanvas.kt (1363 LOC) aufteilen

**Betroffene Files:** `ui/sonogram/SonogramCanvas.kt`

**Problem:** Enthält `OverviewStrip`, `ZoomedCanvas`, `createSpectrogramBitmap()` und mehrere Helper-Funktionen in einer Datei. Drei logisch getrennte Composables + Rendering-Logik.

**Lösung:** In 3 Files aufteilen.

**Schritte:**
1. `OverviewStrip` Composable → `ui/sonogram/OverviewStrip.kt`
2. `ZoomedCanvas` Composable → `ui/sonogram/ZoomedCanvas.kt`
3. `createSpectrogramBitmap()` + Bitmap-Helper → `ui/sonogram/SpectrogramRenderer.kt`
4. `SonogramCanvas.kt` wird zum dünnen Wrapper, der die Teile zusammenführt (oder wird eliminiert, falls `CompareScreen` die Teile direkt referenziert)
5. Imports in `CompareScreen.kt` anpassen

**Risiko:** Mittel — Gesture-Handling und Viewport-State werden zwischen Overview und Zoom geteilt; die Schnittstelle muss sauber definiert werden.

**Aufwand:** Mittel

---

#### P2-7: CompareScreen.kt (1116 LOC) aufteilen

**Betroffene Files:** `ui/compare/CompareScreen.kt`

**Problem:** Root-Layout, Drag&Drop-Setup, Import-Dialog und alle Panel-Integrationen in einem File.

**Lösung:** Teilkomponenten extrahieren.

**Schritte:**
1. `CompareImportDialog` → `ui/compare/ImportDialog.kt`
2. Drag&Drop-AWT-Integration → `ui/compare/DragDropHandler.kt`
3. Toolbar-Integration (falls inline) in `SonogramToolbar` belassen
4. `CompareScreen.kt` bleibt als Orchestrator (~300–400 LOC)

**Risiko:** Mittel — AWT-Drag&Drop ist eng mit dem Compose-Window verknüpft.

**Aufwand:** Mittel

---

#### P2-8: UnifiedSettingsDialog.kt (1362 LOC) aufteilen

**Betroffene Files:** `ui/settings/UnifiedSettingsDialog.kt`

**Problem:** 4-Tab-Dialog mit je eigener Composable-Logik. Jeder Tab hat ~300 LOC.

**Lösung:** Jeden Tab in eine eigene Datei extrahieren.

**Schritte:**
1. `GeneralSettingsTab` → `ui/settings/GeneralSettingsTab.kt`
2. `AnalysisSettingsTab` → `ui/settings/AnalysisSettingsTab.kt`
3. `ExportSettingsTab` → `ui/settings/ExportSettingsTab.kt`
4. `DatabaseSettingsTab` → `ui/settings/DatabaseSettingsTab.kt`
5. `UnifiedSettingsDialog.kt` wird zum Tab-Container (~100–150 LOC)
6. Geteilte Helper-Composables (z.B. `StepSlider`, `SectionHeader`) in `ui/settings/SettingsComponents.kt`

**Risiko:** Mittel — State-Sharing zwischen Tabs muss sauber über Parameter laufen.

**Aufwand:** Mittel

---

#### P2-9: SpeciesTranslations Inline-Map externalisieren

**Betroffene Files:** `core/i18n/SpeciesTranslations.kt`

**Problem:** ~100 Übersetzungseinträge als Map-Literal im Code. Neue Übersetzungen erfordern Code-Änderungen.

**Lösung:** JSON-Datei `resources/i18n/species_de.json` erstellen.

**Schritte:**
1. JSON-Datei erstellen: `{ "Turdus merula": "Amsel", ... }`
2. `SpeciesTranslations` auf Lazy-Loading aus JSON umstellen
3. Map-Literal entfernen
4. Kompilieren und testen

**Risiko:** Niedrig — Nur Datenquelle ändert sich.

**Aufwand:** Klein

---

#### P3-1: Singleton-Pattern vereinheitlichen

**Betroffene Files:** `MelSpectrogram.kt`, `SimilarityEngine.kt`, `AudioPlayer.kt` u.a.

**Problem:** Manche stateless Klassen sind `object` (z.B. `AudioDecoder`, `Colormap`), andere werden als `class` instanziiert aber nur einmal verwendet (z.B. `MelSpectrogram`, `AudioPlayer`). Inkonsistentes Pattern.

**Lösung:** Klassen, die keinen internen State halten und nur als Namespace für Funktionen dienen, zu `object` konvertieren. Klassen mit echtem State (z.B. `AudioPlayer` mit Playback-Position) bleiben `class`.

**Schritte:**
1. Jede Klasse prüfen: Hat sie mutable State? → bleibt `class`. Nur stateless Funktionen? → wird `object`
2. `MelSpectrogram` → `object` (compute-Funktionen sind stateless)
3. `AudioPlayer` → bleibt `class` (hat Playback-State)
4. `SimilarityEngine` → bleibt `class` nach P1-2 (bekommt DI-Parameter)

**Risiko:** Niedrig

**Aufwand:** Klein

---

#### P3-2: Testbarkeit verbessern (DI vorbereiten)

**Betroffene Files:** Diverse Singletons, CompareViewModel

**Problem:** Die Verwendung von `object`-Singletons (z.B. `SettingsStore`, `BirdNetBridge`) macht Unit-Tests schwierig, da globaler State nicht isoliert werden kann.

**Lösung:** Schrittweise Dependency Injection vorbereiten. Keine vollständige DI-Framework-Integration (wäre Overengineering für Desktop-App), sondern Konstruktor-Injection.

**Schritte:**
1. `SettingsStore` von `object` auf `class` mit Interface umstellen
2. `BirdNetBridge` von `object` auf `class` mit Interface umstellen
3. `CompareViewModel` erhält beide via Konstruktor (mit Default-Werten für Prod)
4. Tests können Mock-Implementierungen injizieren
5. [UNKLAR: Ob und welche Tests aktuell existieren — dies bestimmt die Dringlichkeit]

**Risiko:** Mittel — Alle Stellen, die direkt `SettingsStore.load()` aufrufen, müssen angepasst werden.

**Aufwand:** Gross

---

#### P3-3: Umbenennung BirdSono → AMSEL (`ch.etasystems.amsel`)

**Betroffene Files:** Alle 67 Files + Build-Config + Ordnerstruktur

→ **Detaillierter Plan in Abschnitt 4**

---

## 3. Spezial-Fokus: CompareViewModel aufteilen

### 3.1 Aktuelle Verantwortlichkeiten

Das ViewModel enthält folgende logische Bereiche (anhand der 60+ Methoden):

| Bereich | Methoden | StateFlow-Felder | LOC (geschätzt) |
|---------|----------|-----------------|-----------------|
| Audio-Import & File-Mgmt | `importAudio`, `closeFile`, `importCompareFile`, `clearCompareFile` | `audioFile`, `audioSegment`, `pcmCache`, `compareFile`, `isLargeFile`, `audioDurationSec`, `audioSampleRate`, `audioOffsetSec` | ~350 |
| Playback | `togglePlayPause`, `stopPlayback`, `playReferenceAudio` | `isPlaying`, `isPaused`, `playbackPositionSec` | ~150 |
| Viewport & Navigation | `updateViewRange`, `commitViewRange`, `zoomIn/Out/Reset`, `zoomToRange`, `freqZoomIn/Out`, `toggleLogFreqAxis`, `toggleFullView` | `viewStartSec`, `viewEndSec`, `totalDurationSec`, `displayFreqZoom`, `useLogFreqAxis`, `fullView` | ~250 |
| Spektrogramm-Berechnung | interne FFT-Compute-Logik, `refreshAfterPaletteChange` | `overviewSpectrogramData`, `originalOverviewData`, `zoomedSpectrogramData`, `originalZoomedData`, `compareSpectrogramData`, `compareOriginalData`, `isComputingZoom`, `paletteVersion` | ~400 |
| Filtering & Display | `applyFilterDebounced`, `toggleFilterPanel`, `toggleFilterBypass`, `setDisplayDbRange`, `setDisplayGamma`, `resetDisplaySettings` | `filterConfig`, `showFilterPanel`, `isFiltered`, `displayDbRange`, `displayGamma`, `isNormalized`, `normGainDb`, `normReferenceMaxDb` | ~200 |
| Normalisierung & Volume | `toggleNormalization`, `addVolumePoint`, `moveVolumePoint`, `removeVolumePoint`, `clearVolumeEnvelope`, `selectVolumePoint`, `toggleVolumeEnvelope` | `volumeEnvelope`, `volumeEnvelopeActive`, `selectedVolumeIndex` | ~150 |
| Annotationen | `createAnnotationFromSelection`, `selectAnnotation`, `deleteAnnotation`, `updateAnnotationLabel`, `startEditingLabel`, `stopEditingLabel`, `zoomToEvent`, `updateAnnotationBounds`, `toggleEditMode` | `annotations`, `activeAnnotationId`, `editingLabelId`, `selection`, `selectionMode`, `editMode` | ~250 |
| BirdNET & Klassifizierung | `fullScanBirdNet`, `scanBirdNetRegion`, interne Helper | `isProcessing`, `statusText`, `sidebarStatus`, `detectedMode` | ~350 |
| Similarity-Suche | `searchSimilar`, `detectEvents` | `isSearching`, `searchProgress`, `selectedMatchResult` | ~300 |
| Export | `exportAnnotation`, `exportAudio`, `toggleExportBlackAndWhite` | `lastExportFile`, `exportBlackAndWhite` | ~250 |
| Projekt-Management | `loadProject`, `saveProjectManual`, `autoSaveProject` | `projectFile`, `projectDirty`, `auditLog` | ~150 |
| Settings & API | `reloadSettings`, `showApiKeyDialog`, `saveApiKey`, `showDownloadDialog`, `startDownload`, `cancelDownload` | `showApiKeyDialog`, `hasApiKey`, `showDownloadDialog`, `downloadProgress`, `cachedSpeciesCount`, `cachedRecordingCount`, `cacheSizeMB` | ~100 |
| Chunk-Navigation | `selectChunk`, `nextChunk`, `previousChunk` | `chunkManager`, `activeChunkIndex` | ~100 |

### 3.2 Zielstruktur

```
CompareViewModel (schlank, ~400 LOC — orchestriert, hält CompareUiState)
    │
    ├── AudioManager (Import, Decoding, Chunking, Datei-Management)
    │     Felder: audioFile, audioSegment, pcmCache, compareFile,
    │             isLargeFile, audioDurationSec, audioSampleRate, audioOffsetSec,
    │             chunkManager, activeChunkIndex
    │
    ├── PlaybackManager (Play/Pause/Stop, Positions-Tracking)
    │     Felder: isPlaying, isPaused, playbackPositionSec
    │     Dependency: AudioManager (für AudioSegment/PcmCache)
    │
    ├── SpectrogramManager (FFT-Berechnung, Viewport, Zoom, Filter, Display)
    │     Felder: overviewSpectrogramData, originalOverviewData,
    │             zoomedSpectrogramData, originalZoomedData,
    │             compareSpectrogramData, compareOriginalData,
    │             viewStartSec, viewEndSec, totalDurationSec,
    │             displayFreqZoom, useLogFreqAxis, fullView,
    │             filterConfig, showFilterPanel, isFiltered,
    │             displayDbRange, displayGamma, isNormalized,
    │             normGainDb, normReferenceMaxDb, isComputingZoom, paletteVersion
    │     Dependency: AudioManager (für Samples)
    │
    ├── AnnotationManager (CRUD, Selektion, Labels, BirdNET-Results)
    │     Felder: annotations, activeAnnotationId, editingLabelId,
    │             selection, selectionMode, editMode
    │     Dependency: SpectrogramManager (für Viewport-Kontext)
    │
    ├── ClassificationManager (BirdNET-Scan, Similarity-Suche, Event-Detection)
    │     Felder: isProcessing, isSearching, searchProgress,
    │             statusText, sidebarStatus, detectedMode,
    │             selectedMatchResult, showApiKeyDialog, hasApiKey,
    │             showDownloadDialog, downloadProgress,
    │             cachedSpeciesCount, cachedRecordingCount, cacheSizeMB
    │     Dependency: AudioManager, AnnotationManager
    │
    ├── VolumeManager (Envelope-Automation, Normalisierung)
    │     Felder: volumeEnvelope, volumeEnvelopeActive, selectedVolumeIndex
    │
    └── ProjectManager (Load/Save, Audit-Trail, Export)
          Felder: projectFile, projectDirty, auditLog,
                  lastExportFile, exportBlackAndWhite
          Dependency: AudioManager, AnnotationManager, SpectrogramManager
```

### 3.3 Kommunikation zwischen den Managern

Die Manager-Klassen kommunizieren **nicht direkt untereinander**, sondern über das zentrale `CompareViewModel`:

```kotlin
class CompareViewModel {
    // Sub-Manager
    private val audioManager = AudioManager()
    private val playbackManager = PlaybackManager()
    private val spectrogramManager = SpectrogramManager()
    private val annotationManager = AnnotationManager()
    private val classificationManager = ClassificationManager()
    private val volumeManager = VolumeManager()
    private val projectManager = ProjectManager()

    // Zentraler State — kombiniert die Einzel-States
    private val _uiState = MutableStateFlow(CompareUiState())
    val uiState: StateFlow<CompareUiState> = _uiState

    // Orchestrierung: ViewModel delegiert und kombiniert
    fun importAudio(file: File) {
        viewModelScope.launch {
            val result = audioManager.import(file)
            spectrogramManager.computeInitial(result.segment)
            projectManager.createNew(file)
            updateState { /* merge sub-states */ }
        }
    }
}
```

**Pattern:** Jeder Manager hält seinen eigenen internen State als `MutableStateFlow<XxxState>`. Das ViewModel beobachtet alle Manager-States und kombiniert sie zu `CompareUiState` via `combine()`.

```kotlin
// Beispiel: State-Kombination
init {
    viewModelScope.launch {
        combine(
            audioManager.state,
            playbackManager.state,
            spectrogramManager.state,
            annotationManager.state,
            classificationManager.state,
            volumeManager.state,
            projectManager.state
        ) { audio, playback, spectro, annotation, classification, volume, project ->
            CompareUiState(
                audioFile = audio.audioFile,
                isPlaying = playback.isPlaying,
                // ... alle 48+ Felder mappen
            )
        }.collect { _uiState.value = it }
    }
}
```

### 3.4 Reihenfolge der Extraktion

Die Aufspaltung muss schrittweise erfolgen, jeweils mit Kompilier- und Funktionstest:

| Schritt | Was extrahieren | Warum zuerst | Neue Datei |
|---------|----------------|--------------|------------|
| 1 | `VolumeManager` | Kleinstes, isoliertestes Modul (keine Abhängigkeiten). Guter Testlauf für das Pattern. | `ui/compare/VolumeManager.kt` |
| 2 | `AudioManager` | Grundlage für alle anderen — Playback, Spektrogramm, Klassifizierung brauchen Audio-Daten | `ui/compare/AudioManager.kt` |
| 3 | `PlaybackManager` | Hängt nur von AudioManager ab | `ui/compare/PlaybackManager.kt` |
| 4 | `SpectrogramManager` | FFT + Viewport + Filter — grosses Paket, aber klar abgegrenzter Zuständigkeitsbereich | `ui/compare/SpectrogramManager.kt` |
| 5 | `AnnotationManager` | Braucht SpectrogramManager für Viewport-Kontext | `ui/compare/AnnotationManager.kt` |
| 6 | `ClassificationManager` | Braucht AudioManager + AnnotationManager | `ui/compare/ClassificationManager.kt` |
| 7 | `ProjectManager` | Letztes Modul — greift auf alle anderen zu (Save/Load) | `ui/compare/ProjectManager.kt` |
| 8 | Cleanup | `CompareViewModel.kt` auf ~400 LOC reduzieren, `CompareUiState` auf `combine()` umstellen | — |

### 3.5 CompareUiState bleibt

`CompareUiState` bleibt als zentrale State-Klasse erhalten (die UI braucht eine einzige `collectAsState()`-Quelle). Die Felder werden nicht aufgeteilt — nur die **Logik** wird in Manager-Klassen verlagert.

---

## 4. Umbenennung BirdSono → AMSEL (`ch.etasystems.amsel`)

### 4.1 Package-Umbenennung

**Aktuell:** `com.birdsono.*` (21 Packages)
**Neu:** `ch.etasystems.amsel.*`

**Betroffene Verzeichnisstruktur:**
```
src/main/kotlin/com/birdsono/         → src/main/kotlin/ch/etasystems/amsel/
                ├── core/             →                 ├── core/
                ├── data/             →                 ├── data/
                └── ui/               →                 └── ui/
```

**Alle 67 Kotlin-Files** haben eine `package com.birdsono.*` Deklaration, die geändert werden muss.

### 4.2 Klassen- und Funktionsnamen

| Aktuell | Neu | Datei |
|---------|-----|-------|
| `BirdSonoTheme` (Composable) | `AmselTheme` | `ui/theme/Theme.kt` |
| `BirdSonoTheme` (Aufruf in Main) | `AmselTheme` | `Main.kt` |

### 4.3 String-Literals

| Datei | Aktuell | Neu |
|-------|---------|-----|
| `Main.kt` | `"AMSEL — Another Mel Spectrogram Event Locator"` | Bleibt (ist bereits korrekt) |
| `BirdNetBridge.kt` | Temp-File-Prefix `"birdsono_classify_"` | `"amsel_classify_"` |
| `SimilarityEngine.kt` | Temp-Dir `"birdsono_cache"` | `"amsel_cache"` |

### 4.4 Build-Konfiguration (`build.gradle.kts`)

| Feld | Aktuell | Neu |
|------|---------|-----|
| `group` | `"com.birdsono"` | `"ch.etasystems.amsel"` |
| `mainClass` | `"com.birdsono.MainKt"` | `"ch.etasystems.amsel.MainKt"` |
| `packageName` | `"BirdSono"` | `"AMSEL"` |
| `vendor` | `"BirdSono"` | `"ETA Systems"` |
| `menuGroup` | `"BirdSono"` | `"AMSEL"` |
| `upgradeUuid` | bleibt | bleibt (damit Windows-Updater funktioniert) |
| `description` | bleibt | bleibt (ist bereits korrekt) |

### 4.5 Datei-/Ordnerpfade

| Was | Aktuell | Neu |
|-----|---------|-----|
| Source-Verzeichnis | `src/main/kotlin/com/birdsono/` | `src/main/kotlin/ch/etasystems/amsel/` |
| AppData-Verzeichnis | `%APPDATA%/AMSEL/` | Bleibt (ist bereits korrekt) |
| Projektdateien | `.amsel.json` | Bleibt |
| PCM-Cache Magic | `"BSPC"` | Bleibt (binäres Format, Rückwärtskompatibilität) |
| Embedding-DB Magic | `"BSED"` | Bleibt (binäres Format) |

### 4.6 Kommentare und Dokumentation

- `ARCHITECTURE.md`: Alle `com.birdsono` Referenzen → `ch.etasystems.amsel`
- KDoc-Kommentare in Source-Files (falls vorhanden)
- `README.md` (falls vorhanden)

### 4.7 Migration

Die Umbenennung erfordert einen Git-Move der gesamten Source-Struktur:
```bash
git mv src/main/kotlin/com/birdsono src/main/kotlin/ch/etasystems/amsel
```
Anschliessend alle `package`-Deklarationen und `import`-Statements per Suchen/Ersetzen anpassen:
- `com.birdsono` → `ch.etasystems.amsel` (in allen .kt Files)

---

## 5. Empfohlene Reihenfolge

| # | Massnahme | Prio | Aufwand | Risiko | Voraussetzung |
|---|-----------|------|---------|--------|---------------|
| 1 | P2-1: Debug-Code entfernen | P2 | Klein | Niedrig | — |
| 2 | P2-2: FilterConfig extrahieren | P2 | Klein | Niedrig | — |
| 3 | P2-4: Frequenz-Formatierung deduplizieren | P2 | Klein | Niedrig | — |
| 4 | P2-9: SpeciesTranslations externalisieren | P2 | Klein | Niedrig | — |
| 5 | P2-5: Artenliste externalisieren | P2 | Klein | Niedrig | — |
| 6 | P1-1: Zirkuläre Abhängigkeit auflösen | P1 | Klein | Mittel | — |
| 7 | P2-3: Image-Loading deduplizieren | P2 | Klein | Niedrig | — |
| 8 | P1-2: core.similarity entkoppeln | P1 | Mittel | Mittel | #6 (VolumePoint/AuditEntry extrahiert) |
| 9 | P2-6: SonogramCanvas aufteilen | P2 | Mittel | Mittel | — |
| 10 | P2-7: CompareScreen aufteilen | P2 | Mittel | Mittel | — |
| 11 | P2-8: UnifiedSettingsDialog aufteilen | P2 | Mittel | Mittel | — |
| 12 | P3-1: Singleton-Pattern vereinheitlichen | P3 | Klein | Niedrig | — |
| 13 | P1-3: CompareViewModel aufteilen (8 Teilschritte) | P1 | Gross | Hoch | #6, #8 |
| 14 | P3-2: Testbarkeit/DI vorbereiten | P3 | Gross | Mittel | #13 |
| 15 | P3-3: Umbenennung BirdSono → AMSEL | P3 | Gross | Mittel | Alle vorherigen (letzter Schritt) |

**Logik der Reihenfolge:**
- **#1–5:** Quick Wins — klein, niedriges Risiko, sofort umsetzbar, keine Abhängigkeiten
- **#6–8:** Architektur-Bereinigung — löst zirkuläre Deps, bevor der grosse Umbau beginnt
- **#9–12:** Mittelgrosse Refactorings — File-Splits, die den Code lesbarer machen
- **#13:** Der grosse ViewModel-Split — erst wenn der Rest stabil ist
- **#14:** DI-Vorbereitung — erst sinnvoll nach dem ViewModel-Split
- **#15:** Rename als allerletzter Schritt — betrifft alle Files und verursacht maximale Merge-Konflikte. Sollte in einem eigenen Branch als einzelner Commit erfolgen.
