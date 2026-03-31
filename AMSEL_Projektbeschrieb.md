# AMSEL — Projektbeschrieb

## Was ist AMSEL?

**AMSEL** (Another Mel Spectrogram Event Locator) ist ein Desktop-Tool zur akustischen Bestimmung von Vogelarten, Fledermäusen und anderen Tiergruppen. Es kombiniert Sonogramm-Visualisierung, Audio-Filterung, BirdNET-Klassifizierung und Embedding-basierte Ähnlichkeitssuche in einer einzigen Anwendung.

**Zielgruppe:** Ornithologen, Fledermausforscher, Biologie-Studierende, Naturschützer.

## Tech-Stack

| Komponente | Technologie |
|------------|-------------|
| Sprache | Kotlin 2.1.0 |
| UI | Compose Desktop (JetBrains) 1.7.3, Material 3 |
| Audio | javax.sound.sampled, JTransforms (FFT), MP3/FLAC/M4A-Decoder |
| ML/KI | ONNX Runtime 1.17.0, BirdNET (via Python-Bridge) |
| Datenbank | SQLite via Exposed ORM |
| API | Xeno-Canto v3 (Vogelstimmen-Referenzdatenbank) |
| Build | Gradle, native Installer (MSI/DMG) |

**Kennzahlen:** ~21'000 LOC, 67 Kotlin-Files, 21 Packages.

## Kernfunktionen

### Audio-Import & Spektrogramm
- Multi-Format-Import: WAV, MP3, FLAC, M4A
- Mel-Spektrogramm-Berechnung (FFT-basiert) mit konfigurierbaren Presets (Vögel: 125–7500 Hz, Fledermäuse: 15–125 kHz)
- Grossdatei-Support: Chunked Processing für Aufnahmen >2h mit Random-Access PCM-Cache
- Zoom/Pan-Navigation mit Overview-Strip und Zoomed-Canvas

### Audio-Bearbeitung (Signal Chain)
- **Bandpass** (24 dB/Oktave Butterworth)
- **Limiter** (Hard-Threshold)
- **Normalisierung** (-2 dBFS)
- **Spectral Gating** (frequenzbandweise Rauschunterdrückung)
- **Noise-Filter** (prozentuale dynamische Schwelle)
- **Expander/Gate** (mit Knee, Hysterese, Attack/Release)
- **Median-Filter** (Impulsrauschen)
- **Volume Envelope** (Breakpoint-basierte Lautstärke-Automation)

### Klassifizierung & Vergleich
- **BirdNET-Integration** (via Python-Bridge): Automatische Artbestimmung auf Audio-Segmenten
- **Embedding-basierte Ähnlichkeitssuche**: ONNX EfficientNet-Embeddings + HNSW-Index
- **Xeno-Canto-Anbindung**: Online-Referenzdatenbank mit Offline-Cache
- **Mehrere Algorithmen**: MFCC+Cosinus, MFCC+DTW, ONNX EfficientNet, BirdNET-Embeddings

### Annotationen & Projekt
- Zeitbereichs-Annotationen mit Frequenzband-Markierung
- Event-Detection (energiebasiert, automatische Segmentierung)
- Projekt-Persistenz (.amsel.json) mit Audit-Trail für wissenschaftliche Reproduzierbarkeit
- Auto-Save alle 5 Minuten

### Export
- Sonogramm-PNG mit konfigurierbarer Zeitskala (cm pro 0.5s), 600 DPI Druckqualität
- Audio-Export (WAV/MP3) mit angewendeten Filtern, Volume Envelope und Zeitdehnung
- Schwarz/Weiss-Modus für Publikationen

## Architektur

```
Main.kt → CompareScreen (UI)
             ↓
        CompareViewModel (Orchestrierung, 957 LOC)
             ↓
    ┌────────┼────────────────────────────────┐
    ↓        ↓        ↓        ↓              ↓
 Audio    Playback  Spectro  Annotation  Classification
 Manager  Manager   Manager  Manager     Manager
    ↓        ↓        ↓                       ↓
 Project  Volume   Export                  Similarity
 Manager  Manager  Manager                 Engine
```

**Pattern:** Manager-basierte Architektur. Jeder Manager hat eigenen `StateFlow`. CompareViewModel kombiniert via `combine()` zu `CompareUiState` (48 Felder). Kommunikation über Callbacks (Side-Effects) und Provider-Lambdas (Dependency Injection ohne Framework).

## Package-Struktur

```
ch.etasystems.amsel
├── core/
│   ├── audio/          — Decoding, Resampling, Playback, Caching
│   ├── spectrogram/    — FFT, Mel-Filterbank, Colormap
│   ├── filter/         — FilterPipeline, Bandpass, SpectralGating, Expander, etc.
│   ├── detection/      — Energiebasierte Event-Detection
│   ├── similarity/     — Pluggable Metriken (MFCC, DTW, ONNX, Embedding)
│   ├── classifier/     — BirdNET-Bridge, ONNX-Klassifizierung, Embedding-DB
│   ├── annotation/     — Annotation-Modell
│   └── model/          — VolumePoint, AuditEntry
├── data/
│   ├── api/            — Xeno-Canto API Client
│   ├── Settings, OfflineCache, ProjectFile, DownloadManager
├── ui/
│   ├── compare/        — CompareViewModel + 8 Manager
│   ├── sonogram/       — Canvas, Toolbar, Filter-Panel
│   ├── annotation/     — Annotation-Panel, Chunk-Selector
│   ├── results/        — Ergebnis-Panel, Galerie
│   ├── settings/       — UnifiedSettingsDialog (4 Tabs)
│   └── theme/          — Material 3 Dark Theme
```

## Plattform

- **Desktop-only:** Windows (MSI), macOS (DMG), Linux
- **Offline-fähig:** Xeno-Canto-Referenzen werden lokal gecacht
- **Kein Server:** Alles läuft lokal, BirdNET via lokale Python-Installation
