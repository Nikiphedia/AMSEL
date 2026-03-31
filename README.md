# AMSEL — Another Mel Spectrogram Event Locator

> Desktop application for acoustic species identification using spectrogram analysis and AI classification. Built with Kotlin/Compose Desktop.

---

**DE:** Desktop-Anwendung zur akustischen Artenerkennung. AMSEL erstellt Spektrogramme aus Audioaufnahmen, erkennt Rufe und Gesänge automatisch mittels BirdNET V3.0 (ONNX, nativ) und vergleicht sie mit einer Referenzbibliothek (Xeno-Canto). Unterstützt 11'500+ Arten weltweit, Artennamen in 23 Sprachen. Zielgruppe: Ornithologen, Bioakustiker, Naturschutzfachleute.

**EN:** Desktop application for acoustic species identification. AMSEL generates spectrograms from audio recordings, automatically detects calls and songs using BirdNET V3.0 (ONNX, native), and compares them against a reference library (Xeno-Canto). Supports 11,500+ species worldwide, species names in 23 languages. Target audience: ornithologists, bioacousticians, conservation professionals.

## Features

- Spektrogramm-Analyse mit Zoom, Filter, Annotationen
- BirdNET V3.0 ONNX Klassifikation (nativ, kein Python nötig)
- Kandidatenliste mit Top-N Alternativvorschlägen pro Erkennung
- Referenzbibliothek mit Xeno-Canto Integration (Spektrogramme + Audio)
- Artensets / Regionfilter (Schweiz, Mitteleuropa, Global)
- Species Master Table (11'565 Taxa, 23 EU-Sprachen)
- Audio-Import/-Export (WAV/MP3/PNG), Projekt-Speicherung (.amsel.json)
- Erweiterbar auf Fledermäuse, Amphibien, Heuschrecken (Platzhalter vorhanden)

## Voraussetzungen

- Windows 10/11 (64-Bit)
- Java 17+ (JDK)
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
2. Einstellungen → Datenbank → Artenset wählen → Referenzen herunterladen
3. Audio-Datei importieren (WAV/MP3)
4. Analyse starten → BirdNET klassifiziert erkannte Arten

## Datenverzeichnisse
```
~/Documents/AMSEL/
├── models/       — ONNX-Modelle (BirdNET V3.0)
├── references/   — Referenzbibliothek (Xeno-Canto PNGs + MP3s)
├── cache/        — Feature-Vektoren
└── species/      — species_master.json + region_sets.json
```

## JSON-Daten separat verwenden

Die Artendaten sind als standalone JSON-Dateien nutzbar:

| Datei | Beschreibung | Grösse |
|-------|-------------|--------|
| `species/species_master.json` | 11'565 Taxa, 23 Sprachen, IUCN-Status | ~9.5 MB |
| `species/region_sets.json` | 4 Artensets (CH Brut/Komplett, Mitteleuropa, Alle) | ~21 KB |

Die Dateien werden beim ersten Start nach `~/Documents/AMSEL/species/` kopiert und können dort frei editiert werden.

**Download:** Die JSON-Dateien sind auch separat im Release als Assets verfügbar.

## Technologie

- Kotlin 2.1.0, Compose Desktop 1.7.3, Material 3
- ONNX Runtime 1.19.0
- ~21k LOC, ~75 Kotlin-Dateien
- Keine externen Datenbanken (rein dateibasiert)

## Lizenz

GPL v3 — siehe [LICENSE](LICENSE)
Für kommerzielle Lizenzoptionen: support@etasystems.ch

## Mitwirkende

ETA Systems — https://etasystems.ch
