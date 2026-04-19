# AMSEL — Another Mel Spectrogram Event Locator

Desktop-Anwendung zur Sonogramm-Analyse und Artenerkennung von Voegeln und Fledermaeusen.

**Version:** 0.0.9
**Vendor:** ETA Systems
**Package:** `ch.etasystems.amsel`

---

## Tech-Stack

| Schicht | Technologie | Version |
|---------|-------------|---------|
| Sprache | Kotlin | 2.1.0 |
| UI-Framework | Jetbrains Compose Desktop | 1.7.3 |
| Build | Gradle (Kotlin DSL) | 8.11.1 |
| JDK | Temurin / Microsoft OpenJDK | 21 |
| Async | Kotlinx Coroutines | 1.9.0 |
| HTTP | Ktor Client (CIO) | 3.0.3 |
| Serialisierung | Kotlinx Serialization (JSON) | 2.1.0 |
| DSP/FFT | JTransforms | 3.2 |
| ML-Inferenz | ONNX Runtime | 1.19.0 |
| PDF-Export | Apache PDFBox | 3.0.4 |
| Logging | SLF4J Simple | 2.0.16 |
| Plattform | Windows (MSI + Exe Installer) | -- |

### Audio-Codecs

| Library | Version | Format |
|---------|---------|--------|
| JLayer | 1.0.1 | MP3 Decode |
| jflac-codec | 1.5.2 | FLAC Decode |
| MP3SPI | 1.9.5.4 | MP3 SPI Provider |
| JCodec | 0.2.5 | AAC/M4A Decode |

### Externe Tools (Runtime)

| Tool | Zweck | Pflicht? |
|------|-------|----------|
| Python 3 + birdnetlib | BirdNET V2.4 Artenerkennung | Optional (V3.0 ONNX laeuft nativ) |
| ffmpeg | FLAC/M4A/MP3 Audio-Export | Optional (WAV geht immer) |

---

## Features

### Sonogramm-Analyse
- Multi-Format Audio-Import: WAV, MP3, FLAC, OGG, M4A/AAC
- Mel-Spektrogramm-Berechnung mit Bird-Preset (100-16.000 Hz) und Bat-Preset (15-125 kHz)
- **Konfigurierbare Spektrogramm-Parameter**: FFT-Fenstergroesse, Hop-Groesse, Mel-Bins (live via Einstellungen)
- FFT-basiert (Standard: 4096-Punkt FFT, 256 Mel-Bins, parallele Berechnung)
- Interaktives Zoomen/Pannen: Uebersichtsleiste + Zeitleiste + Detail-Canvas
- Drag & Drop fuer Audio-Dateien

### Artenerkennung (Klassifikation)
- **BirdNET V3.0 ONNX (nativ, empfohlen)**: 11'560 Arten, kein Python noetig
- **BirdNET V2.4 (Python, optional)**: Rueckwaerts-kompatibel via HTTP-Daemon
- Standort-bewusst: optionale Lat/Lon-Filterung fuer Artwahrscheinlichkeiten

### Akustische Messwerte
- **AnnotationMetrics** pro Annotation (automatisch berechnet):
  - Peak-Frequenz (kHz), Bandbreite 3dB (kHz), SNR (dB)
  - Anzeige im Annotations-Panel und in CSV-/PDF-Export

### Akustischer Vergleich
- **Offline:** MFCC-Feature-Cache (vorberechnete Vergleichsvektoren)
- **Online:** Xeno-Canto API v3 -- Suche nach Referenzaufnahmen
- Metriken: Cosinus-Aehnlichkeit, DTW, ONNX-Embeddings

### Audio-DSP (Filterkette, 8 Stufen)
1. Volume Fader (Breakpoint-basierte Huellkurve)
2. Bandpass-Filter
3. Limiter
4. Normalisierung (-2 dBFS)
5. Spectral Gating
6. Noise Filter
7. Expander/Gate
8. Median-Filter

### GPS & Metadaten
- GPS-Koordinaten pro Aufnahme (Breitengrad, Laengengrad, Hoehe)
- AudioMetadataDialog: GPS-Felder immer sichtbar (nicht mehr hinter Pirol-Checkbox)
- **GPX-Tracklog Import**: laedt .gpx, matcht Aufnahmen per Zeitstempel (UTC), setzt GPS batch-weise
- **Raven Selection Table Import**: parst TSV-Format (Raven Pro), extrahiert GPS aus Latitude/Longitude/Altitude-Spalten

### Datei-Fortschrittsanzeige
- **Fortschrittsbalken** (3 dp, LinearProgressIndicator) pro Audio-Datei im AudiofilesPanel
- **Verifikations-Counter** ("X/Y ✓") rechts neben dem Balken
- Farbindikator: gruen (tertiary) wenn verifiziert, neutral wenn offen

### Export
- **PNG:** Sonogramm mit Achsen und Annotationen, 600 DPI
- **WAV/FLAC/M4A/MP3:** Audio-Ausschnitt mit angewendeten Filtern
- **PDF:** Analysebericht mit Annotationstabelle (inkl. Messwerte)
- **CSV:** Artenliste mit Zeitstempel, BirdNET-Konfidenz, Peak-Freq, BW, SNR
- **Raven Selection Table (.txt):** TSV-Export im Raven Pro Format

### Projekt-Management
- Speichern/Laden: `.amsel.json` Projektdateien
- Stabile File-IDs: UUID wird aus Projektdatei beibehalten (kein Duplikat-Bug beim Reload)
- Auto-Save, Dirty-State, Audit-Trail

---

## Architektur

### Package-Struktur

```
ch.etasystems.amsel/
+-- Main.kt                         Einstiegspunkt
|
+-- core/                           Algorithmen & Signalverarbeitung
|   +-- annotation/                 Annotations-Datenmodell, AnnotationMetrics,
|   |                               AnnotationMetricsAnalyzer
|   +-- audio/                      AudioDecoder, AudioPlayer, AudioSegment,
|   |                               FilteredAudio, PcmCacheFile, SilenceDetector
|   +-- classifier/                 OnnxBirdNetV3, BirdNetBridge, OnnxClassifier,
|   |                               EmbeddingExtractor
|   +-- detection/                  EventDetector (RMS + Clustering)
|   +-- export/                     AudioExporter, ImageExporter, ReportExporter,
|   |                               SpeciesCsvExporter, RavenSelectionExporter,
|   |                               RavenSelectionImporter, GpxImporter
|   +-- filter/                     FilterPipeline, Bandpass, Limiter, SpectralGating,
|   |                               NoiseFilter, ExpanderGate, MedianFilter
|   +-- i18n/                       SpeciesTranslations
|   +-- reference/                  ReferenceGenerator
|   +-- similarity/                 SimilarityEngine, DTW, Cosine, MFCC, ONNX-Metriken
|   +-- spectrogram/                MelSpectrogram, ChunkedSpectrogram, Colormap,
|                                   MelFilterbank, SpectrogramData
|
+-- data/                           Konfiguration & Persistenz
|   +-- api/                        XenoCantoApi (v3), Modelle, RecordingProvider
|   +-- reference/                  ReferenceLibrary, ReferenceDownloader
|   +-- ModelRegistry.kt
|   +-- ProjectFile.kt              Projekt-Serialisierung + AudioReference
|   +-- RecordingMetadata.kt        GPS + Zeitstempel pro Aufnahme
|   +-- RegionSetRegistry.kt
|   +-- Settings.kt
|   +-- SpeciesRegistry.kt
|
+-- ui/                             Jetbrains Compose Desktop UI
    +-- annotation/                 AnnotationPanel (mit Messwert-Widget),
    |                               CandidatePanel, ChunkSelector,
    |                               AudiofilesPanel (mit Fortschrittsbalken)
    +-- compare/                    CompareScreen, CompareViewModel,
    |                               AudioManager, AnnotationManager,
    |                               ClassificationManager, ExportManager,
    |                               ProjectManager, PlaybackManager,
    |                               SpectrogramManager, VolumeManager
    +-- layout/                     DraggableSplitter, VerticalSplitter,
    |                               UndockablePanel, UndockPanelState
    +-- reference/                  ReferenceEditorScreen
    +-- results/                    ResultsPanel, ResultCard, SonogramGallery
    +-- settings/                   UnifiedSettingsDialog, ModelManagerDialog,
    |                               AudioMetadataDialog, SetupDialog
    +-- sonogram/                   ZoomedCanvas, OverviewStrip, FilterPanel,
    |                               SonogramToolbar (mit Raven/GPX-Import-Buttons),
    |                               TimelineBar, VolumePanel
    +-- theme/                      Material 3 Theming
```

### Design-Patterns
- **MVVM:** CompareViewModel verwaltet UI-State als `StateFlow`
- **Strategy:** SimilarityMetric-Interface (Cosine, DTW, ONNX)
- **Coroutines:** Alle IO/Compute-Operationen asynchron
- **Manager-Architektur:** 8 Manager unter CompareViewModel
- **Cache-First:** MFCC-Feature-Cache + Recording-Provider (Offline-Prioritaet)

---

## Ressourcen

| Datei | Inhalt |
|-------|--------|
| `species_master.json` | 11'565 Arten mit wissenschaftlichen + mehrsprachigen Namen |
| `region_sets.json` | Artenlisten: CH Brutvoegel, CH komplett, Mitteleuropa, Global |
| `classify.py` | Python BirdNET V2.4 Bridge (optional) |
| `classify_daemon.py` | Python BirdNET V2.4 HTTP-Daemon (Port 5757, optional) |

Laufzeit-Daten: `~/Documents/AMSEL/`
- `models/` -- ONNX-Modelle + Python-Skripte
- `references/` -- kuratierte Referenz-Sonogramme
- `species/` -- species_master.json, region_sets.json
- `cache/` -- MFCC-Feature-Cache

---

## Build & Start

```bash
# JDK 21 setzen
set "JAVA_HOME=C:\Program Files\Microsoft\jdk-21.0.10.7-hotspot"

# Kompilieren
cd D:\80002\AMSEL
.\gradlew.bat compileKotlin

# Starten
.\gradlew.bat run

# Windows Installer bauen
.\gradlew.bat packageMsi
```

---

## Statistik

- ~105 Kotlin-Dateien
- 22 Haupt-Module
- 6 Ressourcen-Dateien
- 1 externe API (Xeno-Canto v3)
- 1 Python-Abhaengigkeit (BirdNET V2.4, optional)
- BirdNET V3.0 ONNX nativ (kein Python)
