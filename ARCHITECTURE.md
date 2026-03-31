# ARCHITECTURE.md — AMSEL (Another Mel Spectrogram Event Locator)

## 1. Projektübersicht

AMSEL ist ein Desktop-Tool zur akustischen Bestimmung von Vogelarten, Fledermäusen und anderen Tiergruppen anhand von Sonogramm-Vergleichen, BirdNET-Klassifizierung und Embedding-basierter Ähnlichkeitssuche.

### Tech-Stack

| Komponente | Version |
|---|---|
| Kotlin | 2.1.0 |
| Compose Desktop (JetBrains) | 1.7.3 |
| Material 3 | via Compose |
| Ktor (HTTP-Client) | 3.0.3 |
| Exposed (ORM) | 0.57.0 |
| SQLite JDBC | 3.47.1.0 |
| JTransforms (FFT) | 3.2 |
| ONNX Runtime | 1.17.0 |
| Kotlin Coroutines | 1.9.0 |
| SLF4J | 2.0.16 |
| Kamel (Image Loading) | 1.0.3 |

### Externe Dependencies

| Dependency | Zweck |
|---|---|
| `compose.desktop.currentOs` | Native Desktop-Window mit Skia-Rendering |
| `compose.material3` | Material 3 Design-System (Dark Theme) |
| `compose.materialIconsExtended` | Erweiterte Material-Icons für Toolbar/UI |
| `kotlinx-coroutines-core` | Asynchrone Verarbeitung (FFT, Downloads, Playback) |
| `kotlinx-coroutines-swing` | Dispatchers.Main für Swing/AWT-Integration |
| `ktor-client-cio` | HTTP-Client für Xeno-Canto API v3 |
| `ktor-client-content-negotiation` | JSON Content-Negotiation für API-Antworten |
| `ktor-serialization-kotlinx-json` | JSON-Serialisierung der API-Modelle |
| `ktor-client-logging` | HTTP-Request-Logging für Debugging |
| `exposed-core` / `exposed-dao` / `exposed-jdbc` | ORM-Framework für SQLite-Zugriff |
| `sqlite-jdbc` | JDBC-Treiber für lokale SQLite-Datenbank |
| `JTransforms` | FFT-Bibliothek für Spektrogramm-Berechnung (DoubleFFT_1D) |
| `kamel-image` | Compose-kompatibles async Image-Loading |
| `jlayer` | MP3-Decoding (JavaZoom) |
| `jflac-codec` | FLAC-Decoding |
| `mp3spi` | Java Sound SPI-Provider für MP3 |
| `jcodec` | M4A/AAC-Decoding (Fallback) |
| `onnxruntime` | ONNX-Inferenz für EfficientNet-Embeddings und BirdNET-Klassifizierung |
| `slf4j-simple` | Logging-Fassade |

### Kennzahlen

- **67 Kotlin-Files**, **20'904 LOC**
- **21 Packages** unter `ch.etasystems.amsel`
- App-Version: 0.0.4

---

## 2. Modul-Übersicht

### 2.1 `ch.etasystems.amsel` (Root)

**Pfad:** `src/main/kotlin/com/birdsono/`
**Verantwortlichkeit:** Application Entry Point.

| File | LOC | Beschreibung |
|---|---|---|
| `Main.kt` | 36 | Bootstrap: Compose-Window (1400×900dp), AmselTheme, CompareScreen als Root, BirdNET-Shutdown-Hook |

**Abhängigkeiten:** → `ui.compare`, `ui.theme`, `core.classifier`

---

### 2.2 `core.audio` — Audio-Verarbeitung

**Pfad:** `src/main/kotlin/com/birdsono/core/audio/`
**Verantwortlichkeit:** Decoding, Resampling, Playback, Caching und Chunking von Audiodateien (WAV, MP3, FLAC, M4A).

| File | LOC | Beschreibung |
|---|---|---|
| `AudioDecoder.kt` | 393 | Robustes Multi-Format-Decoding (WAV/MP3/FLAC/M4A) mit Streaming-Support und Fallback-Strategien |
| `AudioSegment.kt` | 43 | Data class: Mono-PCM-Container (Float[-1..1]) mit Zeitbereichs-Extraktion |
| `AudioResampler.kt` | 41 | Lineare Interpolation auf beliebige Ziel-Samplerate |
| `AudioPlayer.kt` | 180 | javax.sound.sampled-Playback mit Coroutine-basiertem State (Play/Pause/Stop) |
| `FilteredAudio.kt` | 209 | STFT → Gain-Maske → iSTFT: wendet Spektrogramm-Filter auf Audio-Waveform an |
| `PcmCacheFile.kt` | 157 | Random-Access-Cache für dekodiertes PCM (Binärformat mit Header "BSPC") |
| `ChunkManager.kt` | 127 | Zerlegt lange Audiodateien in Chunks mit Overlap; filtert Annotationen pro Chunk |

**Abhängigkeiten:** → `core.filter` (FilterPipeline), `core.spectrogram` (MelSpectrogram, SpectrogramData), `core.annotation` (Annotation)

---

### 2.3 `core.spectrogram` — Spektrogramm-Berechnung

**Pfad:** `src/main/kotlin/com/birdsono/core/spectrogram/`
**Verantwortlichkeit:** FFT-basierte Spektrogramm-Erzeugung (Mel und Linear), Farbgebung und Chunk-Verarbeitung für grosse Dateien.

| File | LOC | Beschreibung |
|---|---|---|
| `MelFilterbank.kt` | 66 | Mel-Filterbank-Matrix (Hz↔Mel); Presets für Vögel (125–7500 Hz) und Fledermäuse (15–125 kHz) |
| `MelSpectrogram.kt` | 140 | Konfigurierbares Mel-Spektrogramm via Hann→FFT→Mel-Filter→Log; Bird-Preset (4096 FFT, 256 Mels) und Bat-Preset (1024 FFT, 80 Mels) |
| `LinearSpectrogram.kt` | 187 | Hochauflösendes lineares Spektrogramm für Druckexport (16384 FFT, 2D-Gauss-Glättung) |
| `SpectrogramData.kt` | 46 | Data class: 2D-Matrix (nMels × nFrames) mit Log-Werten; normalisierte Abfragen |
| `ChunkedSpectrogram.kt` | 193 | Adaptives Spektrogramm für Dateien >2h; Maximum-Pooling-Downsampling; Overview/Region-Berechnung |
| `Colormap.kt` | 163 | Farbpaletten (Magma, Viridis, Inferno, Grayscale, BW_Print) mit interpolierter 256-Einträge-LUT; Gamma-Korrektur |

**Abhängigkeiten:** → `core.audio` (AudioDecoder); extern: JTransforms

---

### 2.4 `core.filter` — Audio-/Spektrogramm-Filter

**Pfad:** `src/main/kotlin/com/birdsono/core/filter/`
**Verantwortlichkeit:** Rauschunterdrückung, Dynamikverarbeitung und spektrale Filterung auf Spektrogramm-Daten.

| File | LOC | Beschreibung |
|---|---|---|
| `FilterPipeline.kt` | 154 | Geordnete Filterkette: Volume→Bandpass→Normalize→SpectralGating→NoiseFilter→Expander→Limiter→Median |
| `BandpassFilter.kt` | 74 | 24 dB/Oktave Butterworth-Rolloff auf Mel-Spektrogrammen |
| `SpectralGating.kt` | 228 | Frequenzbandweise Rauschunterdrückung mit Sigmoid-LUT; manueller oder Auto-Profil-Modus |
| `SpectralSubtraction.kt` | 75 | Kontrastverstärkung via Noise-Floor-Schätzung aus leisesten Frames |
| `ExpanderGate.kt` | 193 | Gate/Expander mit Knee, Hysterese, Attack/Release-Glättung |
| `NoiseFilter.kt` | 47 | Prozentuale dynamische Schwelle (0–95%) |
| `Limiter.kt` | 56 | Hard-Threshold-Limiter (-6 bis -40 dB) |
| `MedianFilter.kt` | 62 | 2D-Medianfilter für Impulsrauschen (Klicks); optimiert für 3×3 Kernel |

**Abhängigkeiten:** → `core.spectrogram` (MelFilterbank, SpectrogramData)

---

### 2.5 `core.detection` — Ereigniserkennung

**Pfad:** `src/main/kotlin/com/birdsono/core/detection/`
**Verantwortlichkeit:** Energiebasierte Ereigniserkennung auf Spektrogrammen.

| File | LOC | Beschreibung |
|---|---|---|
| `EventDetector.kt` | 160 | Sliding-RMS-Baseline; gruppiert aktive Frames zu Events; bestimmt Frequenzband pro Event |

**Abhängigkeiten:** → `core.spectrogram` (SpectrogramData)

---

### 2.6 `core.similarity` — Ähnlichkeitsmetriken

**Pfad:** `src/main/kotlin/com/birdsono/core/similarity/`
**Verantwortlichkeit:** Pluggable Similarity-Metriken (MFCC, DTW, ONNX, Embedding) und die zentrale Vergleichs-Engine.

| File | LOC | Beschreibung |
|---|---|---|
| `SimilarityMetric.kt` | 30 | Interface: `extractFeatures()`, `compare()`, `featureSize` |
| `CosineSimilarityMetric.kt` | 44 | MFCC-Summary (26-dim) + Cosinus-Ähnlichkeit |
| `MfccExtractor.kt` | 301 | MFCC-Extraktion (13-dim): Hann→FFT→Mel→Log→DCT; Deltas, CMVN; Bird/Bat/Enhanced-Presets |
| `DtwDistance.kt` | 77 | Dynamic Time Warping mit Sakoe-Chiba-Band; euklidische Distanz |
| `DtwSimilarityMetric.kt` | 96 | MFCC+DTW: Cosinus-Vorfilter + DTW auf Top-100 |
| `OnnxSimilarityMetric.kt` | 305 | ONNX-Embeddings (BirdNET-Lite, 160-dim) mit statistischem Fallback |
| `EmbeddingSimilarityMetric.kt` | 93 | Embedding-Vektoren via EmbeddingExtractor; L2-Normalisierung + Cosinus |
| `SimilarityEngine.kt` | 375 | Offline-first Vergleichs-Engine; abstraktes Metrik-Interface; Online-Fallback via Xeno-Canto API |

**Abhängigkeiten:** → `core.audio` (AudioDecoder, AudioResampler, AudioSegment), `core.spectrogram` (MelFilterbank, MelSpectrogram), `core.classifier` (EmbeddingExtractor, EmbeddingDatabase), `data` (OfflineCache, CacheEntry), `data.api` (XenoCantoApi, XenoCantoRecording); extern: ONNX Runtime, JTransforms

---

### 2.7 `core.classifier` — Klassifizierung & Embeddings

**Pfad:** `src/main/kotlin/com/birdsono/core/classifier/`
**Verantwortlichkeit:** BirdNET-Integration (Python-Bridge + ONNX), EfficientNet-Embedding-Extraktion und lokale Vektor-Datenbank.

| File | LOC | Beschreibung |
|---|---|---|
| `BirdNetBridge.kt` | 588 | Python-Bridge: persistenter BirdNET-Daemon auf localhost:5757; robuste Python-Erkennung (Anaconda, Store, PATH); Fallback Single-Process |
| `OnnxClassifier.kt` | 374 | BirdNET-kompatible Artklassifizierung via ONNX; 3-Sekunden-Chunk-Inferenz; Confidence-Aggregation |
| `EmbeddingExtractor.kt` | 487 | 43-dim Pseudo-Embedding (MFCC+Delta+Spectral) oder ONNX-Logits; 48kHz-Normalisierung; Chunk-basiert |
| `EmbeddingDatabase.kt` | 362 | Lokale Vektor-DB (Binärformat "BSED"); arten-indexierte Partitionierung; Brute-Force-Cosinus-Suche |

**Abhängigkeiten:** → `core.audio` (AudioResampler), `core.similarity` (MfccExtractor); extern: ONNX Runtime, JTransforms, kotlinx.serialization

---

### 2.8 `core.reference` — Referenz-Sonogramm-Erzeugung

**Pfad:** `src/main/kotlin/com/birdsono/core/reference/`
**Verantwortlichkeit:** Generierung wissenschaftlicher Referenz-Sonogramme im Feldführer-Stil (Glutz/Svensson).

| File | LOC | Beschreibung |
|---|---|---|
| `ReferenceGenerator.kt` | 465 | Multi-Row-Layout (4 sec/Zeile); 300 DPI; absolute kHz-Achse + relative Sekunden-Achse; Metadata-JSON |

**Abhängigkeiten:** → `core.audio` (AudioDecoder, AudioResampler, AudioSegment), `core.spectrogram` (MelSpectrogram, SpectrogramData, MelFilterbank), `core.filter` (FilterPipeline, FilterConfig), `core.detection` (EventDetector)

---

### 2.9 `core.export` — Bildexport

**Pfad:** `src/main/kotlin/com/birdsono/core/export/`
**Verantwortlichkeit:** Export markierter Sonogramm-Abschnitte in Druckqualität.

| File | LOC | Beschreibung |
|---|---|---|
| `ImageExporter.kt` | 572 | 600 DPI; Area-Averaging-Interpolation; PNG-Metadaten (tEXt + pHYs); Glutz-Layout (3.5 sec/Zeile) |

**Abhängigkeiten:** → `core.spectrogram` (MelSpectrogram, SpectrogramData, Colormap, MelFilterbank, LinearSpectrogram), `core.filter` (FilterPipeline, FilterConfig), `core.detection` (EventDetector), `core.annotation` (Annotation)

---

### 2.10 `core.annotation` — Annotationen

**Pfad:** `src/main/kotlin/com/birdsono/core/annotation/`
**Verantwortlichkeit:** Datenmodell für Zeit-/Frequenz-Annotationen.

| File | LOC | Beschreibung |
|---|---|---|
| `Annotation.kt` | 57 | Serialisierbare Data classes `Annotation` und `MatchResult`; 8-Farben-Palette; unterstützt BirdNET-, manuelle und Vergleichs-Annotationen |

**Abhängigkeiten:** Keine internen (Leaf-Modul)

---

### 2.11 `core.i18n` — Lokalisierung

**Pfad:** `src/main/kotlin/com/birdsono/core/i18n/`
**Verantwortlichkeit:** Deutsche Übersetzungen für Vogelarten-Namen.

| File | LOC | Beschreibung |
|---|---|---|
| `SpeciesTranslations.kt` | 296 | 100+ Arten-Übersetzungen (BirdNET-Labels → Deutsch); Unterarten-Komplexe; wissenschaftliche Namen; Label-Confidence-Parsing |

**Abhängigkeiten:** Keine internen (Leaf-Modul)

---

### 2.12 `data` — Datenschicht

**Pfad:** `src/main/kotlin/com/birdsono/data/`
**Verantwortlichkeit:** Persistenz (Settings, Cache, Projektdateien), Offline-Cache-Verwaltung und Download-Management.

| File | LOC | Beschreibung |
|---|---|---|
| `Settings.kt` | 247 | `AppSettings` (26+ Felder), `FilterPreset`, `ComparisonAlgorithm`-Enum; JSON-Persistenz in `%APPDATA%/AMSEL/settings.json` |
| `OfflineCache.kt` | 232 | Verwaltet `%APPDATA%/AMSEL/cache/`; Verzeichnisstruktur: audio/, sono/, mfcc/, onnx/, embedding/; index.json |
| `ReferenceCollection.kt` | 216 | Verifizierte Referenz-Sammlungen ("xeno", "glutz", "eigene") in `%APPDATA%/AMSEL/references/` |
| `DownloadManager.kt` | 332 | Bulk-Download + Referenz-Generierung; On-Demand-Audio-Download; 200ms Rate-Limiting |
| `ProjectFile.kt` | 89 | `AmselProject`-Serialisierung (.amsel.json): Audio-Referenz, Annotationen, Filter, Volume-Envelope, Audit-Log |

**Abhängigkeiten:** → `core.audio` (AudioDecoder), `core.spectrogram` (MelSpectrogram, SpectrogramData), `core.filter` (FilterConfig), `core.reference` (ReferenceGenerator), `core.annotation` (Annotation), `data.api` (XenoCantoApi); **↑ ui.compare** (VolumePoint, AuditEntry — siehe Auffälligkeiten)

---

### 2.13 `data.api` — Xeno-Canto API

**Pfad:** `src/main/kotlin/com/birdsono/data/api/`
**Verantwortlichkeit:** HTTP-Client und Datenmodelle für die Xeno-Canto REST API v3.

| File | LOC | Beschreibung |
|---|---|---|
| `XenoCantoApi.kt` | 109 | Ktor-HTTP-Client; v3-Query-Syntax (`gen:`, `sp:`, `grp:`); Paginierung |
| `XenoCantoModels.kt` | 43 | Serialisierbare Datenmodelle: `XenoCantoResponse`, `XenoCantoRecording`, `SonogramUrls` |

**Abhängigkeiten:** Keine internen (Leaf-Modul, rein extern: Ktor)

---

### 2.14 `ui.theme` — Design

**Pfad:** `src/main/kotlin/com/birdsono/ui/theme/`
**Verantwortlichkeit:** Dark-Color-Scheme für die gesamte App.

| File | LOC | Beschreibung |
|---|---|---|
| `Theme.kt` | 26 | `AmselTheme()` Composable; Dark-Scheme mit Primary=#90CAF9, Background=#1A1A2E |

**Abhängigkeiten:** Keine internen (Leaf-Modul)

---

### 2.15 `ui.compare` — Haupt-Screen & ViewModel

**Pfad:** `src/main/kotlin/com/birdsono/ui/compare/`
**Verantwortlichkeit:** Zentrales ViewModel (State-Management) und Haupt-Composable der App.

| File | LOC | Beschreibung |
|---|---|---|
| `CompareViewModel.kt` | 2889 | Zentrales ViewModel: Audio-Import, Playback, FFT, Filtering, Annotationen, Ähnlichkeitssuche, Export, BirdNET; 40+ StateFlow-Felder; Audit-Trail |
| `CompareScreen.kt` | 1116 | Root-Composable: Layout mit Toolbar→FilterPanel→Row(Annotation \| Column(Overview→Timeline→Zoom→Results)); Drag&Drop via AWT |

**Abhängigkeiten:** → `core.*` (alle Core-Module), `data.*` (alle Data-Module), `ui.sonogram`, `ui.results`, `ui.reference`, `ui.annotation`, `ui.layout`, `ui.settings`

---

### 2.16 `ui.sonogram` — Sonogramm-Darstellung

**Pfad:** `src/main/kotlin/com/birdsono/ui/sonogram/`
**Verantwortlichkeit:** Interaktive Sonogramm-Anzeige, Toolbar, Filter-Panel, Timeline und Volume-Editor.

| File | LOC | Beschreibung |
|---|---|---|
| `SonogramCanvas.kt` | 1363 | Skia-basiertes Spektrogramm-Rendering; `OverviewStrip` (60dp) + `ZoomedCanvas`; Viewport-Drag, Rubber-Band-Selektion |
| `SonogramToolbar.kt` | 467 | Toolbar: Import, Playback, Zoom, Selektion, Annotation, Filter, Export, Einstellungen |
| `FilterPanel.kt` | 504 | Noise-Filter-Controls mit StepSlider; logarithmische Frequenz-Skala; Preset-Speicherung |
| `TimelineBar.kt` | 317 | Interaktive Timeline mit Zoom-Buttons und draggbaren Start/End-Handles; ms-Präzision |
| `VolumePanel.kt` | 205 | Volume-Automation-Editor: Breakpoints mit Click/Drag; dB-Skala (-60 bis +6) |

**Abhängigkeiten:** → `core.spectrogram` (SpectrogramData, Colormap), `core.filter` (FilterConfig, ExpanderGate), `data` (SettingsStore, FilterPreset), `ui.compare` (VolumePoint)

---

### 2.17 `ui.results` — Ergebnisanzeige

**Pfad:** `src/main/kotlin/com/birdsono/ui/results/`
**Verantwortlichkeit:** Darstellung von Vergleichs- und Klassifizierungsergebnissen.

| File | LOC | Beschreibung |
|---|---|---|
| `ResultsPanel.kt` | 224 | Ergebnisliste gruppiert nach Art; sortiert nach Ähnlichkeit; ausklappbare Varianten |
| `ResultCard.kt` | 200 | Einzelergebnis-Card: Sonogramm-Bild, Art, Qualität (A–E), Ähnlichkeits-Progressbar |
| `ReferenceSonogramPanel.kt` | 281 | Grosses Referenz-Sonogramm mit Metadaten-Header; robustes Image-Loading mit Fallback |
| `SonogramGallery.kt` | 557 | Horizontale Thumbnail-Galerie (200×110dp); Korrupt-Erkennung (all-black); Play-Button-Overlay |

**Abhängigkeiten:** → `core.annotation` (Annotation, MatchResult)

---

### 2.18 `ui.reference` — Referenz-Editor

**Pfad:** `src/main/kotlin/com/birdsono/ui/reference/`
**Verantwortlichkeit:** Batch-Erstellung von Referenz-Sonogrammen aus Audio-Dateien.

| File | LOC | Beschreibung |
|---|---|---|
| `ReferenceEditorScreen.kt` | 709 | Fullscreen-Overlay: Audio-Import-Queue, Clip-Selektion, Filter, Metadaten-Eingabe, Referenz-Generierung |

**Abhängigkeiten:** → `core.audio` (AudioDecoder), `core.reference` (ReferenceGenerator), `data` (ReferenceStore, SettingsStore), `ui.sonogram` (FilterPanel, OverviewStrip, ZoomedCanvas)

---

### 2.19 `ui.annotation` — Annotations-Panel

**Pfad:** `src/main/kotlin/com/birdsono/ui/annotation/`
**Verantwortlichkeit:** Sidebar für Annotations-Verwaltung und Chunk-Navigation.

| File | LOC | Beschreibung |
|---|---|---|
| `AnnotationPanel.kt` | 459 | Annotations-Liste gruppiert nach Art; Konfidenz-Anzeige; Unterarten-Warnungen; Referenz-Zähler |
| `ChunkSelector.kt` | 141 | Chunk-Navigation für grosse Audiodateien (>60s); Annotations-Badge pro Chunk |

**Abhängigkeiten:** → `core.annotation` (Annotation, ANNOTATION_COLORS), `core.i18n` (SpeciesTranslations), `core.audio` (AudioChunk)

---

### 2.20 `ui.settings` — Einstellungsdialoge

**Pfad:** `src/main/kotlin/com/birdsono/ui/settings/`
**Verantwortlichkeit:** Konfigurationsdialoge für API, Export, Vergleich und Downloads.

| File | LOC | Beschreibung |
|---|---|---|
| `UnifiedSettingsDialog.kt` | 1362 | 4-Tab-Dialog: Allgemein, Analyse, Export, Datenbank; ersetzt Einzeldialoge |
| `DownloadDialog.kt` | 584 | Hierarchische Arten-Auswahl (35+ Familien, 200+ Arten); Cache-Status; Download-Fortschritt |
| `ExportSettingsDialog.kt` | 267 | Frequenzbereiche (Bird/Bat/Insect-Presets), Zeitachse, Standort |
| `ComparisonSettingsDialog.kt` | 214 | Algorithmus-Auswahl (5 Metriken); BirdNET-Schwellwert; Modell-Verfügbarkeitsprüfung |
| `ApiKeyDialog.kt` | 69 | Einfacher API-Key-Eingabedialog für Xeno-Canto |

**Abhängigkeiten:** → `core.classifier` (BirdNetBridge, EmbeddingExtractor), `core.similarity` (OnnxSimilarityMetric), `data` (SettingsStore, AppSettings, DownloadManager, OfflineCache)

---

### 2.21 `ui.layout` — Layout-Komponenten

**Pfad:** `src/main/kotlin/com/birdsono/ui/layout/`
**Verantwortlichkeit:** Wiederverwendbare Layout-Splitter.

| File | LOC | Beschreibung |
|---|---|---|
| `DraggableSplitter.kt` | 130 | Vertikaler und horizontaler Resize-Splitter (4dp, grüner Hover-Effekt) |

**Abhängigkeiten:** Keine internen (Leaf-Modul)

---

## 3. Datenfluss

### 3.1 Audio laden → Sonogramm anzeigen

```
Benutzer wählt Datei (Drag&Drop oder FileDialog)
    │
    ▼
CompareScreen.kt ──onImport()──→ CompareViewModel.kt
    │
    ▼  importAudio(file)
AudioDecoder.decode(file)          ← WAV/MP3/FLAC/M4A → Mono Float[-1..1]
    │
    ▼  AudioSegment(samples, sampleRate)
PcmCacheFile.create(segment)       ← Binär-Cache für Random Access
    │
    ▼
ChunkManager.buildChunks()         ← Bei Dateien >60s: Zerlegung in Chunks
    │
    ▼
MelSpectrogram.compute(segment)    ← Hann → FFT (JTransforms) → Mel-Filterbank → Log
    │
    ▼  SpectrogramData(matrix, nMels, nFrames)
FilterPipeline.apply(spectrogram)  ← Bandpass→Normalize→Gate→Noise→Expander→Limiter→Median
    │
    ▼  SpectrogramData (gefiltert)
Colormap.apply(data, palette)      ← Werte → ARGB-Pixel (Magma/Viridis/etc.)
    │
    ▼
SonogramCanvas.kt                  ← createSpectrogramBitmap() → Skia-Rendering
    ├── OverviewStrip (60dp)       ← ChunkedSpectrogram.computeOverview() für Gesamtübersicht
    └── ZoomedCanvas               ← Viewport-Bereich mit Scroll/Zoom
```

### 3.2 BirdNET-Scan → Ergebnisse anzeigen

```
Benutzer klickt "BirdNET starten" (SonogramToolbar)
    │
    ▼
CompareViewModel.runBirdNet()
    │
    ▼
BirdNetBridge.analyze(wavPath, lat, lon, minConf)
    │   ├── Prüft ob Python-Daemon auf localhost:5757 läuft
    │   ├── Falls nein: startet BirdNET-Analyzer als Hintergrund-Daemon
    │   │     └── Python-Discovery: Anaconda → Microsoft Store → PATH
    │   └── Sendet WAV-Pfad via HTTP POST an Daemon
    │
    ▼  List<BridgeResult>(species, confidence, startSec, endSec)
SpeciesTranslations.translate()    ← "Turdus merula" → "Amsel"
    │
    ▼
CompareViewModel                   ← Erstellt Annotation pro Detektion
    ├── Annotation(startSec, endSec, freqLow, freqHigh, label, confidence)
    └── Gruppierung nach Art + Sortierung nach Konfidenz
    │
    ▼
AnnotationPanel.kt                 ← Zeigt gruppierte Annotationen in Sidebar
    ├── GroupHeader (Art, Konfidenz-%, Farbe)
    └── Einzelergebnisse mit Zeitstempel
    │
    ▼
SonogramCanvas.kt                  ← Zeichnet farbige Annotations-Boxen über Spektrogramm
```

### 3.3 Artvergleich (offline) — Von der Annotation bis zum Referenz-Sonogramm

```
Benutzer markiert Bereich im Sonogramm (Rubber-Band-Selektion)
    │
    ▼
CompareViewModel.startComparison(annotation)
    │
    ▼  Lädt gewählte SimilarityMetric (aus Settings)
SimilarityEngine.compare(segment, metric)
    │
    ├── (1) Offline-Pfad:
    │   OfflineCache.loadAllFeatures(metricKey)    ← Cached Feature-Vektoren laden
    │   metric.extractFeatures(segment)            ← Features aus Benutzer-Audio extrahieren
    │   metric.compare(userFeatures, cachedFeatures) ← Ähnlichkeit berechnen
    │       ├── CosineSimilarityMetric: MFCC-Summary (26d) → Cosinus
    │       ├── DtwSimilarityMetric: Cosinus-Vorfilter → DTW auf Top-100
    │       ├── OnnxSimilarityMetric: ONNX-Embedding (160d) → Cosinus
    │       └── EmbeddingSimilarityMetric: Pseudo-Embedding (43d) → Cosinus
    │
    ├── (2) Online-Fallback (wenn Cache leer):
    │   XenoCantoApi.searchBySpecies()             ← API v3 Abfrage
    │   DownloadManager.downloadAudioOnDemand()    ← MP3 herunterladen
    │   metric.extractFeatures() + compare()       ← Vergleich wie oben
    │   OfflineCache.saveFeatures()                ← Ergebnis cachen
    │
    ▼  List<MatchResult>(recordingId, similarity, species)
ResultsPanel.kt                    ← Gruppiert nach Art, sortiert nach Ähnlichkeit
    ├── ResultCard.kt              ← Thumbnail, Art, Qualität, Ähnlichkeits-%
    └── SonogramGallery.kt         ← Horizontale Galerie; Klick → ReferenceSonogramPanel
         │
         ▼
    ReferenceSonogramPanel.kt      ← Grossansicht mit Metadaten
```

---

## 4. State-Management

### 4.1 Zentrales ViewModel-Pattern

Die gesamte App verwendet ein **einzelnes ViewModel** (`CompareViewModel`) das als Compose-`remember`-State in `CompareScreen` instanziiert wird:

```kotlin
// CompareScreen.kt
val viewModel = remember { CompareViewModel() }
val state by viewModel.uiState.collectAsState()
```

`CompareUiState` ist eine Data class mit **40+ Feldern**, darunter:
- Audio-State: `audioSegment`, `sampleRate`, `totalDurationSec`, `playbackPosition`
- Spektrogramm: `spectrogramData`, `filteredSpectrogram`, `overviewBitmap`
- Viewport: `viewStartSec`, `viewEndSec`, `selectionStart`, `selectionEnd`
- Annotationen: `annotations`, `activeAnnotationIndex`, `birdnetResults`
- Ergebnisse: `matchResults`, `selectedMatch`, `isComparing`
- Filter: `filterConfig`, `isFiltered`, `filterPresets`
- Volume: `volumePoints` (Breakpoint-basierte Automation)
- UI-Flags: `showFilterPanel`, `showSettings`, `isLoading`, `errorMessage`
- Audit: `auditEntries` (Protokoll aller Aktionen für Reproduzierbarkeit)

### 4.2 State-Propagation

```
CompareViewModel (MutableStateFlow<CompareUiState>)
    │
    │  .collectAsState()
    ▼
CompareScreen ──props──→ SonogramToolbar(state, onAction)
              ──props──→ SonogramCanvas(spectrogramData, viewport, annotations)
              ──props──→ FilterPanel(filterConfig, onFilterChange)
              ──props──→ AnnotationPanel(annotations, onSelect)
              ──props──→ ResultsPanel(matchResults, onSelect)
              ──props──→ TimelineBar(viewStart, viewEnd, onDrag)
              ──props──→ VolumePanel(volumePoints, onUpdate)
```

Alle UI-Komponenten sind **stateless Composables** die ihren State via Props vom ViewModel erhalten und User-Aktionen via Callbacks (`onXxx`) zurückmelden.

### 4.3 Debouncing

- **Zoom-Änderungen:** 400ms Debounce bevor Spektrogramm neu berechnet wird
- **Filter-Änderungen:** 500ms Debounce bevor FilterPipeline re-applied wird

### 4.4 Globale Singletons / Shared State

| Singleton | Typ | Beschreibung |
|---|---|---|
| `SettingsStore` | Object | Lädt/speichert `AppSettings` aus `%APPDATA%/AMSEL/settings.json` |
| `BirdNetBridge` | Object | Verwaltet Python-Daemon-Lifecycle; Shutdown-Hook in Main.kt |
| `AudioDecoder` | Object | Stateless Decoder-Funktionen |
| `AudioResampler` | Object | Stateless Resampling-Funktionen |
| `MelFilterbank` | Object | Stateless Filterbank-Builder |
| `ChunkedSpectrogram` | Object | Stateless Chunked-Berechnung |
| `Colormap` | Object | Stateless Farb-LUT |
| `EventDetector` | Object | Stateless Event-Detektion |
| `FilterPipeline` | Object | Stateless Filter-Anwendung |
| `ImageExporter` | Object | Stateless Export |
| `ReferenceGenerator` | Object | Stateless Referenz-Generierung |
| `LinearSpectrogram` | Object | Stateless Linear-FFT |
| `FilteredAudio` | Object | Stateless Audio-Filterung |
| `SpeciesTranslations` | Object | Stateless Übersetzungen |
| `DtwDistance` | Object | Stateless DTW-Berechnung |

Die meisten Singletons sind **stateless** (reine Funktionen als Object). Echter Shared State existiert nur in `SettingsStore` und `BirdNetBridge`.

### 4.5 Persistenz

| Was | Wo | Format |
|---|---|---|
| App-Einstellungen | `%APPDATA%/AMSEL/settings.json` | JSON (kotlinx.serialization, prettyPrint) |
| Offline-Cache-Index | `%APPDATA%/AMSEL/cache/index.json` | JSON |
| Cache-Audio | `%APPDATA%/AMSEL/cache/audio/xc_{id}.mp3` | MP3 |
| Cache-Sonogramme | `%APPDATA%/AMSEL/cache/sono/xc_{id}.jpg` | JPEG |
| Cache-Features (MFCC) | `%APPDATA%/AMSEL/cache/mfcc/xc_{id}.bin` | Binär (Little-Endian Float) |
| Cache-Features (ONNX) | `%APPDATA%/AMSEL/cache/onnx/xc_{id}.bin` | Binär |
| Cache-Features (Embedding) | `%APPDATA%/AMSEL/cache/embedding/xc_{id}.bin` | Binär |
| Referenz-Sammlungen | `%APPDATA%/AMSEL/references/{collection}/` | JSON-Index + PNG/WAV |
| Embedding-Datenbank | `%APPDATA%/AMSEL/embeddings.bsed` | Binär (Magic "BSED") |
| PCM-Cache | Temp-Verzeichnis | Binär (Magic "BSPC") |
| Projektdateien | Neben Audio-Datei | `.amsel.json` |

---

## 5. Abhängigkeits-Diagramm

```
                        ┌─────────────┐
                        │   Main.kt   │
                        └──────┬──────┘
                               │
                               ▼
                     ┌─────────────────┐
                     │   ui/compare/   │◄──────────────────────────────┐
                     │  CompareScreen  │                               │
                     │ CompareViewModel│                               │
                     └────┬──┬──┬──┬──┘                               │
                          │  │  │  │                                   │
          ┌───────────────┘  │  │  └──────────────┐                   │
          ▼                  │  │                  ▼                   │
  ┌───────────────┐          │  │        ┌─────────────────┐          │
  │  ui/sonogram/ │          │  │        │  ui/settings/   │          │
  │ Canvas,Filter │          │  │        │ UnifiedSettings │          │
  │ Toolbar,Time  │          │  │        │ Download,Export │          │
  └───────┬───────┘          │  │        └────────┬────────┘          │
          │                  │  │                  │                   │
          │      ┌───────────┘  └─────────┐       │                   │
          │      ▼                        ▼       │                   │
          │  ┌──────────┐       ┌──────────────┐  │                   │
          │  │ui/results│       │ui/annotation/│  │                   │
          │  │ Panel,   │       │AnnotPanel,   │  │                   │
          │  │ Gallery  │       │ChunkSelector │  │                   │
          │  └────┬─────┘       └──────┬───────┘  │                   │
          │       │                    │           │                   │
          │  ┌────┘    ┌───────────────┘           │                   │
          │  │         │                           │                   │
          ▼  ▼         ▼                           ▼                   │
  ┌──────────────────────────────────────────────────────┐            │
  │                      data/                           │            │
  │  Settings · OfflineCache · ReferenceCollection       │────────────┘
  │  DownloadManager · ProjectFile                       │  (importiert
  └──────────┬─────────────────────────┬─────────────────┘  VolumePoint,
             │                         │                    AuditEntry)
             ▼                         ▼
     ┌──────────────┐       ┌───────────────────┐
     │  data/api/   │       │     core/         │
     │ XenoCantoApi │       │                   │
     │ XC-Models    │       │  ┌─────────────┐  │
     └──────────────┘       │  │  classifier/ │  │
                            │  │ BirdNetBridge│  │
                            │  │ OnnxClassif. │  │
                            │  │ EmbeddingDB  │  │
                            │  │ EmbeddExtr.  │  │
                            │  └──────┬───────┘  │
                            │         │          │
                            │  ┌──────▼───────┐  │
                            │  │  similarity/ │  │
                            │  │  Engine,DTW  │  │
                            │  │  MFCC,ONNX   │──┼──→ data/ (OfflineCache)
                            │  │  Embedding   │──┼──→ data/api/ (XenoCantoApi)
                            │  └──────┬───────┘  │
                            │         │          │
                            │  ┌──────▼───────┐  │
                            │  │  reference/  │  │
                            │  │  export/     │  │
                            │  └──────┬───────┘  │
                            │         │          │
                            │  ┌──────▼───────┐  │
                            │  │  detection/  │  │
                            │  └──────┬───────┘  │
                            │         │          │
                            │  ┌──────▼───────┐  │
                            │  │   filter/    │  │
                            │  └──────┬───────┘  │
                            │         │          │
                            │  ┌──────▼───────┐  │
                            │  │ spectrogram/ │  │
                            │  └──────┬───────┘  │
                            │         │          │
                            │  ┌──────▼───────┐  │
                            │  │    audio/    │  │
                            │  └──────┬───────┘  │
                            │         │          │
                            │  ┌──────▼───────┐  │
                            │  │ annotation/  │  │
                            │  │    i18n/     │  │
                            │  └─────────────┘  │
                            │   (Leaf-Module)   │
                            └───────────────────┘

Legende:
  ──→  importiert
  ──▼  importiert (vertikal)
```

---

## 6. Auffälligkeiten

### 6.1 Sehr grosse Files (>500 LOC)

| File | LOC | Grund |
|---|---|---|
| `CompareViewModel.kt` | 2889 | Zentrales ViewModel mit **aller** Geschäftslogik: Audio-Import, Playback, FFT, Filter, Annotationen, Vergleich, Export, BirdNET, Audit-Trail |
| `SonogramCanvas.kt` | 1363 | Enthält OverviewStrip + ZoomedCanvas + Bitmap-Erzeugung + Gesture-Handling + Annotations-Overlay — mehrere Composables in einer Datei |
| `UnifiedSettingsDialog.kt` | 1362 | 4-Tab-Dialog mit je eigener Composable-Logik; fasst frühere Einzeldialoge zusammen |
| `CompareScreen.kt` | 1116 | Root-Layout + CompareImportDialog + Drag&Drop-Setup + alle Panel-Integrationen |
| `ReferenceEditorScreen.kt` | 709 | Eigenständiger Fullscreen-Workflow (Import, Clip, Filter, Metadaten, Generierung) in einer Datei |
| `BirdNetBridge.kt` | 588 | Python-Prozess-Management + HTTP-Kommunikation + WAV-Erzeugung + Daemon-Lifecycle |
| `DownloadDialog.kt` | 584 | Enthält 200+ Artenlisten-Daten inline als `buildCategories()` Funktion |
| `ImageExporter.kt` | 572 | Druckexport mit komplexer Pixel-Interpolation und PNG-Metadaten-Erzeugung |
| `SonogramGallery.kt` | 557 | Thumbnail-Gallery + robustes Image-Loading mit mehreren Fallback-Strategien |
| `FilterPanel.kt` | 504 | 8 Filter-Sektionen mit je eigenen Slider-Konfigurationen |

### 6.2 Zirkuläre Abhängigkeit

Es besteht eine **zirkuläre Abhängigkeit** zwischen `data/` und `ui/compare/`:

- `data/ProjectFile.kt` importiert `ch.etasystems.amsel.ui.compare.VolumePoint` und `ch.etasystems.amsel.ui.compare.AuditEntry`
- `ui/compare/CompareViewModel.kt` importiert aus `data/` (SettingsStore, OfflineCache, etc.)

Die Typen `VolumePoint` und `AuditEntry` sind in `CompareViewModel.kt` definiert, gehören aber zum Datenmodell und werden für die Projekt-Serialisierung in `ProjectFile.kt` benötigt.

### 6.3 Architektur-Eigenheit: `core.similarity` → `data`

Das Package `core.similarity` (speziell `SimilarityEngine.kt`) importiert direkt aus `data/` (OfflineCache) und `data/api/` (XenoCantoApi). Damit durchbricht die Similarity-Engine die übliche Schichtung (core sollte nicht von data abhängen).

### 6.4 Code-Duplikation

- **Image-Loading-Logik:** Robustes BufferedImage-Laden mit Fallback-Strategien existiert sowohl in `SonogramGallery.kt` (`loadBufferedImage`, `readImageRobust`, `isImageBlack`) als auch in `ReferenceSonogramPanel.kt` (eigene Fallback-Kette mit ImageIO → Toolkit).
- **Frequenz-Formatierung:** `formatFreq()`/`formatKHz()`/`parseKHzToHz()` erscheinen in mehreren Settings-Dialogen.

### 6.5 Inkonsistenzen in Naming-Conventions

- **Projekt-Name:** Code verwendet durchgehend `ch.etasystems.amsel` als Package-Name, `AMSEL` als Window-Titel und MSI-Package-Name. `OfflineCache` verwendet `AMSEL` als Verzeichnisname (`%APPDATA%/AMSEL/`).
- **Singleton-Style:** Manche Klassen sind `object` (echte Singletons: `AudioDecoder`, `Colormap`, `FilterPipeline`), andere werden als `class` instanziiert aber effektiv nur einmal verwendet (`MelSpectrogram`, `SimilarityEngine`, `AudioPlayer`).
- **Dateinamen vs. Klassenamen:** Generell konsistent (1 Hauptklasse pro File), ausser `SonogramCanvas.kt` das `OverviewStrip`, `ZoomedCanvas`, `createSpectrogramBitmap` und mehrere Helper-Funktionen enthält.

### 6.6 Files die nicht in ihr Package passen

- **`VolumePoint` und `AuditEntry`** sind in `ui/compare/CompareViewModel.kt` definiert, werden aber als Datenmodell in `data/ProjectFile.kt` für Serialisierung verwendet. Sie gehören konzeptionell eher in `data/` oder `core/`.
- **`FilterConfig`** (Data class) ist in `core/filter/FilterPipeline.kt` definiert, wird aber in zahlreichen UI- und Data-Files importiert. Eine eigene Datei wäre üblicher.

### 6.7 Auskommentierte Blöcke / Debug-Code

- `ReferenceSonogramPanel.kt` enthält umfangreiche Debug-Logging-Anweisungen an eine Datei `compare_debug.log`
- Im Projekt-Root existiert eine Datei `export_debug.log`, was auf Debug-Logging im Export-Pfad hindeutet

### 6.8 Inline-Daten

- `DownloadDialog.kt` enthält eine ~200-Zeilen-Funktion `buildCategories()` mit hart kodierten Artenlisten (35+ Familien, 200+ Arten mit wissenschaftlichen und deutschen Namen)
- `SpeciesTranslations.kt` enthält ~100 Übersetzungen als Map-Literal
