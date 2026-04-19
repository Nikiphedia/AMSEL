# AMSEL — Projektbeschrieb

## Was ist AMSEL?

**AMSEL** (Another Mel Spectrogram Event Locator) ist ein Desktop-Tool zur akustischen Bestimmung von Vogelarten, Fledermaeusen und anderen Tiergruppen. Es kombiniert Sonogramm-Visualisierung, Audio-Filterung, BirdNET-Klassifizierung und Embedding-basierte Aehnlichkeitssuche in einer einzigen Anwendung.

**Version:** 0.0.9
**Zielgruppe:** Ornithologen, Fledermausforscher, Biologie-Studierende, Naturschuetzer.

## Tech-Stack

| Komponente | Technologie |
|------------|-------------|
| Sprache | Kotlin 2.1.0 |
| UI | Compose Desktop (JetBrains) 1.7.3, Material 3 |
| Audio | javax.sound.sampled, JTransforms (FFT), MP3/FLAC/M4A-Decoder |
| ML/KI | ONNX Runtime 1.19.0, BirdNET V3.0 ONNX (nativ, kein Python) |
| Persistenz | kotlinx.serialization JSON (dateibasiert, kein SQLite) |
| API | Xeno-Canto v3 (Vogelstimmen-Referenzdatenbank) |
| Build | Gradle 8.11.1 (Kotlin DSL), MSI-Installer |

**Kennzahlen:** ~26'000 LOC, ~105 Kotlin-Dateien, 22 Packages.

## Kernfunktionen

### Audio-Import & Spektrogramm
- Multi-Format-Import: WAV, MP3, FLAC, M4A
- Mel-Spektrogramm-Berechnung (FFT-basiert) mit konfigurierbaren Presets (Voegel: 125-7500 Hz, Fledermaeusse: 15-125 kHz)
- **Konfigurierbare Spektrogramm-Parameter**: FFT-Fenstergroesse, Hop-Groesse, Mel-Bins (live anpassbar ueber Einstellungen)
- Grossdatei-Support: Chunked Processing fuer Aufnahmen >2h mit Random-Access PCM-Cache
- Zoom/Pan-Navigation mit Overview-Strip und Zoomed-Canvas

### Audio-Bearbeitung (Signal Chain)
- **Bandpass** (24 dB/Oktave Butterworth)
- **Limiter** (Hard-Threshold)
- **Normalisierung** (-2 dBFS)
- **Spectral Gating** (frequenzbandweise Rauschunterdrueckung)
- **Noise-Filter** (prozentuale dynamische Schwelle)
- **Expander/Gate** (mit Knee, Hysterese, Attack/Release)
- **Median-Filter** (Impulsrauschen)
- **Volume Envelope** (Breakpoint-basierte Lautstaerke-Automation)

### Klassifizierung & Vergleich
- **BirdNET V3.0 ONNX (nativ)**: Automatische Artbestimmung auf Audio-Segmenten, 11'560 Arten, kein Python erforderlich
- **BirdNET V2.4 (Python, optional)**: Rueckwaerts-kompatibel via Python-Bridge
- **Embedding-basierte Aehnlichkeitssuche**: ONNX EfficientNet-Embeddings + HNSW-Index
- **Xeno-Canto-Anbindung**: Online-Referenzdatenbank mit Offline-Cache

### Annotationen & Akustische Messwerte
- Zeitbereichs-Annotationen mit Frequenzband-Markierung
- **Automatisch berechnete Messwerte** pro Annotation: Peak-Frequenz (kHz), Bandbreite 3dB (kHz), SNR (dB)
- Anzeige der Messwerte direkt im Annotations-Panel
- Event-Detection (energiebasiert, automatische Segmentierung)
- Verifikations-Workflow: jede Annotation kann bestaetigt oder abgelehnt werden

### Datei-Management & Fortschrittsanzeige
- Multi-File-Support: mehrere Audio-Dateien gleichzeitig geladen
- **Fortschrittsbalken pro Datei**: zeigt Anteil verifizierter Annotationen
- **Verifikations-Counter** ("X/Y verif."): numerische Anzeige pro Datei
- Farbindikator: gruen = verifiziert, neutral = offen

### GPS & Metadaten
- **GPS-Koordinaten pro Aufnahme**: Breitengrad, Laengengrad, Hoehe (immer editierbar)
- **GPX-Tracklog Import**: automatische GPS-Zuordnung aller Aufnahmen ueber Zeitstempel-Matching
- **Raven Selection Table Import**: importiert Zeitannotationen inkl. GPS-Spalten aus Raven Pro
- Aufnahme-Zeitstempel: Datum + Uhrzeit pro Audio-Datei (Pirol-Geraete automatisch erkannt)

### Projekt-Persistenz
- Projekt-Persistenz (.amsel.json) mit Audit-Trail fuer wissenschaftliche Reproduzierbarkeit
- Stabile File-IDs: Projektdatei speichert UUID pro Audio-Referenz, wird beim Laden beibehalten
- Auto-Save alle 5 Minuten

### Export
- **PNG**: Sonogramm mit Achsen und Annotationen, 600 DPI Druckqualitaet
- **Audio-Export** (WAV/MP3) mit angewendeten Filtern, Volume Envelope und Zeitdehnung
- **PDF-Analysebericht**: Zusammenfassung, Annotationstabelle mit akustischen Messwerten
- **CSV-Artenliste**: Zeitstempel, BirdNET-Konfidenz, Messwerte (Peak-Freq, BW, SNR)
- **Raven Selection Table (.txt)**: Export im Raven Pro TSV-Format
- Schwarz/Weiss-Modus fuer Publikationen

## Architektur

```
Main.kt -> CompareScreen (UI)
             |
        CompareViewModel (Orchestrierung)
             |
    +--------+--------+--------+--------+--------+
    |        |        |        |        |        |
 Audio    Playback  Spectro  Annot.  Classif. Export
 Manager  Manager   Manager  Manager  Manager  Manager
    |                                     |
 Project  Volume
 Manager  Manager
```

**Pattern:** Manager-basierte Architektur. Jeder Manager hat eigenen `StateFlow`. CompareViewModel kombiniert via `combine()` zu `CompareUiState` (50+ Felder). Kommunikation ueber Callbacks (Side-Effects) und Provider-Lambdas (Dependency Injection ohne Framework).

## Package-Struktur

```
ch.etasystems.amsel
+-- core/
|   +-- audio/          -- Decoding, Resampling, Playback, Caching
|   +-- spectrogram/    -- FFT, Mel-Filterbank, Colormap, AnnotationMetrics
|   +-- filter/         -- FilterPipeline, Bandpass, SpectralGating, Expander, etc.
|   +-- detection/      -- Energiebasierte Event-Detection
|   +-- similarity/     -- Pluggable Metriken (MFCC, DTW, ONNX, Embedding)
|   +-- classifier/     -- OnnxBirdNetV3, OnnxClassifier, Embedding-DB, BirdNetBridge
|   +-- annotation/     -- Annotation-Modell, AnnotationMetrics, AnnotationMetricsAnalyzer
|   +-- export/         -- AudioExporter, ImageExporter, ReportExporter,
|   |                      SpeciesCsvExporter, RavenSelectionExporter,
|   |                      RavenSelectionImporter, GpxImporter
|   +-- model/          -- VolumePoint, AuditEntry
+-- data/
|   +-- api/            -- Xeno-Canto API Client
|   +-- reference/      -- ReferenceLibrary, ReferenceDownloader
|   +-- Settings.kt, OfflineCache.kt, ProjectFile.kt, DownloadManager.kt
|   +-- RecordingMetadata.kt  -- GPS + Zeitstempel pro Aufnahme
+-- ui/
    +-- compare/        -- CompareViewModel + 8 Manager
    +-- sonogram/       -- Canvas, Toolbar (mit Raven/GPX-Import-Buttons), Filter-Panel
    +-- annotation/     -- Annotation-Panel (mit Messwert-Widget), Chunk-Selector,
    |                      AudiofilesPanel (mit Fortschrittsbalken)
    +-- results/        -- Ergebnis-Panel, Galerie
    +-- settings/       -- UnifiedSettingsDialog (4 Tabs), AudioMetadataDialog (GPS immer sichtbar)
    +-- theme/          -- Material 3 Dark Theme
```

## Plattform

- **Desktop-only:** Windows (MSI), macOS (DMG), Linux
- **Offline-faehig:** Xeno-Canto-Referenzen werden lokal gecacht
- **Kein Server:** Alles laeuft lokal, BirdNET V3.0 nativ via ONNX Runtime
