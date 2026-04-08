# AMSEL — Another Mel Spectrogram Event Locator

Desktop-Anwendung zur Sonogramm-Analyse und Artenerkennung von Voegeln und Fledermaeuse.

**Version:** 0.0.5
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
| Plattform | Windows (MSI + Exe Installer) | — |

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
| Python 3 + birdnetlib | BirdNET Artenerkennung | Optional |
| ffmpeg | FLAC/M4A/MP3 Audio-Export | Optional (WAV geht immer) |

---

## Features

### Sonogramm-Analyse
- Multi-Format Audio-Import: WAV, MP3, FLAC, OGG, M4A/AAC
- Mel-Spektrogramm-Berechnung mit Bird-Preset (100–16.000 Hz) und Bat-Preset (15–125 kHz)
- FFT-basiert (4096-Punkt FFT, 256 Mel-Bins, parallele Berechnung)
- Interaktives Zoomen/Pannen: Uebersichtsleiste + Zeitleiste + Detail-Canvas
- Drag & Drop fuer Audio-Dateien

### Artenerkennung (Klassifikation)
- **BirdNET-Integration** via Python-Bridge:
  - Persistenter HTTP-Daemon auf localhost:5757 (Modell einmal laden)
  - Fallback: Einzelprozess pro Datei
  - Automatische Python-Erkennung (AppData, Anaconda, Scoop, PATH)
- **ONNX-Runtime** fuer EfficientNet-Embeddings (lokale Modelle)
- Standort-bewusst: optionale Lat/Lon-Filterung fuer Artwahrscheinlichkeiten

### Akustischer Vergleich
- **Offline:** MFCC-Feature-Cache (vorberechnete Vergleichsvektoren)
- **Online:** Xeno-Canto API v3 — Suche nach Referenzaufnahmen
- **Metriken:**
  - Cosinus-Aehnlichkeit (MFCC / Embeddings)
  - Dynamic Time Warping (DTW) fuer temporale Ausrichtung
  - Embedding-basierter Neuronaler Vergleich (ONNX)
- Rangierte Ergebnisse mit Konfidenz-Score

### Audio-DSP (Filterkette, 7 Stufen)
1. Volume Fader (Breakpoint-basierte Huellkurve)
2. Bandpass-Filter (Frequenzbereich eingrenzen)
3. Limiter (Clipping-Schutz)
4. Normalisierung (Pegel auf -2 dBFS)
5. Spectral Gating (bandweise Rauschunterdrueckung)
6. Noise Filter (dynamische Schwelle)
7. Expander/Gate (leise Bereiche reduzieren)
8. Median-Filter (Glaettung)

### Referenz-Bibliothek
- Bulk-Download von Xeno-Canto Sonogrammen
- Kuratierter Bestand: `~/Documents/AMSEL/curated/`
- Benutzer-Aufnahmen: `~/Documents/AMSEL/user/`
- `referenzen.csv` Masterdatei (ID, Art, Quelle, Typ, Beschreibung, Qualitaet)
- Regions-Sets: Schweizer Brutvoegel (180), Mitteleuropa (302), Global (~1000+)
- Mehrsprachige Artnamen (species_master.json, IOC v15.1, 23 Sprachen)

### Export
- **PNG:** Sonogramm mit Achsen und Annotationen
- **WAV/FLAC/M4A/MP3:** Audio-Ausschnitt (markierter Bereich), optional mit Filter
- **PDF:** Analysebericht mit Zusammenfassung und Annotationstabelle
- **CSV:** Artenliste mit Zeitstempel und BirdNET-Konfidenz

### Projekt-Management
- Speichern/Laden: `.amsel` Projektdateien (JSON-serialisierter Zustand)
- Annotationen: manuelle Markierungen mit Kandidaten-Panel
- Einstellungen: Analyse-Parameter, Datenbank-Pfade, Export-Optionen

---

## Architektur

### Package-Struktur

```
ch.etasystems.amsel/
├── Main.kt                         Einstiegspunkt
│
├── core/                           Algorithmen & Signalverarbeitung
│   ├── annotation/                 Annotations-Datenmodell
│   ├── audio/                      AudioDecoder, AudioPlayer, AudioSegment,
│   │                               FilteredAudio, PcmCacheFile, SilenceDetector
│   ├── classifier/                 BirdNetBridge, OnnxClassifier, EmbeddingExtractor
│   ├── detection/                  EventDetector (RMS + Clustering)
│   ├── export/                     AudioExporter, ImageExporter, ReportExporter,
│   │                               SpeciesCsvExporter
│   ├── filter/                     FilterPipeline, Bandpass, Limiter, SpectralGating,
│   │                               NoiseFilter, ExpanderGate, MedianFilter
│   ├── i18n/                       SpeciesTranslations
│   ├── reference/                  ReferenceGenerator (300 DPI Feldfuehrer-Sonogramme)
│   ├── similarity/                 SimilarityEngine, DTW, Cosine, MFCC, ONNX-Metriken
│   └── spectrogram/                MelSpectrogram, ChunkedSpectrogram, Colormap,
│                                   MelFilterbank, SpectrogramData
│
├── data/                           Konfiguration & Persistenz
│   ├── api/                        XenoCantoApi (v3), Modelle, RecordingProvider
│   ├── reference/                  ReferenceLibrary, ReferenceDownloader
│   ├── ModelRegistry.kt            ML-Modell-Verwaltung
│   ├── ProjectFile.kt              Projekt-Serialisierung
│   ├── RegionSetRegistry.kt        Artenlisten nach Region
│   ├── Settings.kt                 App-Einstellungen
│   └── SpeciesRegistry.kt          Arten-Stammdaten
│
└── ui/                             Jetbrains Compose Desktop UI
    ├── annotation/                 AnnotationPanel, CandidatePanel, ChunkSelector
    ├── compare/                    CompareScreen, CompareViewModel,
    │                               AudioManager, ClassificationManager,
    │                               ExportManager, ProjectManager, DragDropHandler
    ├── layout/                     DraggableSplitter, VerticalSplitter
    ├── reference/                  ReferenceEditorScreen (Batch-Import + Sonogramm)
    ├── results/                    ResultsPanel, ResultCard, SonogramGallery
    ├── settings/                   UnifiedSettingsDialog, ModelManagerDialog,
    │                               ApiKeyDialog, Tabs (Allgemein/Analyse/DB/Export)
    ├── sonogram/                   ZoomedCanvas, OverviewStrip, FilterPanel,
    │                               SpectrogramRenderer, TimelineBar, VolumePanel
    └── theme/                      Material 3 Theming
```

### Design-Patterns
- **MVVM:** CompareViewModel verwaltet UI-State als `StateFlow`
- **Strategy:** SimilarityMetric-Interface (Cosine, DTW, ONNX)
- **Coroutines:** Alle IO/Compute-Operationen asynchron
- **Manager-Architektur:** AudioManager, ClassificationManager, ExportManager, etc.
- **Cache-First:** MFCC-Feature-Cache + Recording-Provider (Offline-Prioritaet)

---

## Ressourcen

| Datei | Inhalt |
|-------|--------|
| `species_master.json` | ~1000+ Arten mit wissenschaftlichen + mehrsprachigen Namen |
| `i18n/species_de.json` | Deutsche Artnamen |
| `region_sets.json` | Artenlisten: CH Brutvoegel, CH komplett, Mitteleuropa, Global |
| `classify.py` | Python BirdNET Bridge (Einzelaufruf) |
| `classify_daemon.py` | Python BirdNET HTTP-Daemon (Port 5757) |

Laufzeit-Daten: `~/Documents/AMSEL/`
- `models/` — ONNX-Modelle + Python-Skripte
- `curated/` — kuratierte Referenz-Sonogramme
- `user/` — Benutzer-Aufnahmen
- `referenzen.csv` — Referenz-Masterdatei
- `reference_index.json` — Auto-generierter Index
- `cache/` — MFCC-Feature-Cache

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

- 105 Kotlin-Dateien
- 13 Haupt-Module
- 6 Ressourcen-Dateien
- 1 externe API (Xeno-Canto v3)
- 1 Python-Abhaengigkeit (BirdNET, optional)
