# AMSEL — Another Mel Spectrogram Event Locator

> Desktop application for acoustic species identification using spectrogram analysis and AI classification (BirdNET V3.0 ONNX). Built with Kotlin/Compose Desktop.

## Überblick

AMSEL ist eine Desktop-Applikation zur akustischen Artenerkennung. Die App analysiert Audioaufnahmen mittels Spektrogramm-Visualisierung und KI-gestützter Klassifikation (BirdNET V3.0 ONNX), vergleicht Ergebnisse mit einer lokalen Referenzbibliothek (Xeno-Canto) und unterstützt den gesamten Workflow von Import über Analyse bis Export. Zielgruppe: Ornithologen, Bioakustiker, Naturschutzfachleute, ambitionierte Vogelbeobachter.

## Features

- **Spektrogramm-Analyse** — Zoom, Filter, Annotationen, Übersichtsstreifen + gezoomte Detailansicht
- **BirdNET V3.0 ONNX Klassifikation** — Nativ, kein Python nötig. Full-File-Scan mit konfigurierbarer Konfidenz-Schwelle
- **Referenzbibliothek** — Xeno-Canto Integration mit Spektrogramm-PNGs und Audio on-demand (MP3)
- **Artensets / Regionfilter** — Artenlisten nach Region einschränken (Schweiz, Mitteleuropa, Global)
- **Audio-Import & Export** — WAV/MP3/FLAC Import, Export als WAV/MP3/PNG, Projekt-Speicherung (.amsel.json)
- **Species Master Table** — 179+ Arten (Vögel, erweiterbar auf Fledermäuse, Amphibien), mehrsprachig (DE/EN/FR)
- **Professionelle Signal Chain** — Bandpass, Spectral Gating, Noise Filter, Gate, Limiter, Median, Normalisierung

## Screenshots

*Screenshots folgen.*

## Voraussetzungen

- Windows 10/11 (64-Bit)
- Java 17+ (JDK, nicht nur JRE)
- ~500 MB Festplatte (App + Modelle + Referenzen)
- Internetverbindung für Xeno-Canto Downloads

## Schnellstart

### Aus den Quellen bauen

```bash
git clone https://github.com/Nikiphedia/AMSEL.git
cd AMSEL
./gradlew run
```

### Installer bauen

```bash
./gradlew createDistributable
# Output: build/compose/binaries/main/app/
```

### Erster Start

1. App starten → BirdNET V3.0 Modell wird automatisch erkannt
2. Einstellungen → Tab "Datenbank" → Referenzen herunterladen
3. Audio-Datei importieren (WAV/MP3)
4. Analyse starten → BirdNET klassifiziert erkannte Arten

## Projektstruktur

```
src/main/kotlin/ch/etasystems/amsel/
├── Main.kt
├── core/           — FFT, Similarity, Audio-Processing, Filter, Classifier
│   ├── audio/      — AudioDecoder, Player, PCM-Cache
│   ├── classifier/ — BirdNET V3 ONNX, V2 Bridge
│   ├── filter/     — Signal Chain (Bandpass, Gate, Limiter, Median)
│   ├── similarity/ — MFCC, Fingerprint, Embedding-Vergleich
│   └── spectrogram/— Mel-Spektrogramm Berechnung + Rendering
├── data/           — Settings, References, API-Clients
│   ├── api/        — Xeno-Canto API
│   └── reference/  — Referenzbibliothek-Verwaltung
└── ui/             — Compose Desktop UI
    ├── compare/    — Hauptansicht (Spektrogramm + Vergleich)
    ├── settings/   — Unified Settings Dialog (4 Tabs)
    ├── sonogram/   — OverviewStrip, ZoomedCanvas, Renderer
    └── results/    — Klassifikationsergebnisse

src/main/resources/
├── i18n/           — Übersetzungen
├── models/         — ONNX-Modelldefinitionen
└── species/        — Artendaten (species_master.json, region_sets.json)
```

~98 Kotlin-Dateien, ~21k LOC.

## Datenverzeichnisse

```
~/Documents/AMSEL/
├── models/       — ONNX-Modelle (BirdNET V3.0 etc.)
├── references/   — Referenzbibliothek (Xeno-Canto PNGs + MP3s)
│   ├── curated/  — Heruntergeladene Referenzen
│   └── user/     — Eigene Aufnahmen
├── cache/        — Feature-Vektoren (.bin)
└── species/      — species_master.json (Artentabelle)
```

## Technische Details

- Kotlin 2.1.0, Compose Desktop 1.7.3, Material 3
- ONNX Runtime 1.19.0
- Keine externen Datenbanken (rein dateibasiert)

## Lizenz

GPL v3 — siehe [LICENSE](LICENSE)

Für kommerzielle Lizenzoptionen: support@etasystems.ch

## Mitwirkende

ETA Systems GmbH
