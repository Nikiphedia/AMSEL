# AMSEL — Another Mel Spectrogram Event Locator

> Desktop application for acoustic species identification using spectrogram analysis and AI classification. Built with Kotlin/Compose Desktop.

---

**DE:** Desktop-Anwendung zur akustischen Artenerkennung. AMSEL erstellt Spektrogramme aus Audioaufnahmen, erkennt Rufe und Gesaenge automatisch mittels BirdNET V3.0 (ONNX, nativ) und vergleicht sie mit einer Referenzbibliothek (Xeno-Canto). Unterstuetzt 11'500+ Arten weltweit, Artnamen in 23 Sprachen. Zielgruppe: Ornithologen, Bioakustiker, Naturschutzfachleute.

**EN:** Desktop application for acoustic species identification. AMSEL generates spectrograms from audio recordings, automatically detects calls and songs using BirdNET V3.0 (ONNX, native), and compares them against a reference library (Xeno-Canto). Supports 11,500+ species worldwide, species names in 23 languages. Target audience: ornithologists, bioacousticians, conservation professionals.

## Features

- Spektrogramm-Analyse mit Zoom, Filter, Annotationen
- BirdNET V3.0 ONNX Klassifikation (nativ, kein Python noetig)
- Kandidatenliste mit Top-N Alternativvorschlaegen pro Erkennung
- Akustische Messwerte pro Annotation: Peak-Frequenz, Bandbreite, SNR (aus dem ungefilterten Spektrogramm, neu berechenbar)
- Zweites Modell waehlbar: Perch v2 (ONNX); Herkunft je Kandidat sichtbar
- Mehrkanal-Aufnahmen: Kanalwahl beim Import
- Referenzbibliothek mit Xeno-Canto Integration (Spektrogramme + Audio)
- Artensets / Regionfilter (Schweiz, Mitteleuropa, Global)
- Species Master Table (11'565 Taxa, 23 EU-Sprachen)
- Audio-Import/-Export (WAV/MP3/PNG), Projekt-Speicherung (.amsel.json)
- **Raven Selection Table Import/Export** (Zeitannotationen + GPS-Daten)
- **GPX-Tracklog Import** (automatische GPS-Zuordnung pro Aufnahme)
- **Datei-Fortschrittsanzeige**: Verifikations-Balken + Counter pro Audio-Datei
- **GPS-Metadaten** pro Aufnahme: Breitengrad, Laengengrad, Hoehe
- Konfigurierbare Spektrogramm-Parameter (FFT-Fenster, Hop-Groesse, Mel-Bins)
- Erweiterbar auf Fledermaeusse, Amphibien, Heuschrecken (Platzhalter vorhanden)

## Download und Installation

Fertiger Windows-Installer: **[Releases](https://github.com/Nikiphedia/AMSEL/releases/latest)** → `AMSEL-<Version>.msi` herunterladen.

1. `AMSEL-<Version>.msi` ausfuehren. Der Installer ist nicht mit einem anerkannten Zertifikat signiert; Windows SmartScreen zeigt deshalb eine Warnung ("Weitere Informationen" → "Trotzdem ausfuehren").
2. AMSEL aus dem Startmenue starten. Java muss nicht installiert sein, die Laufzeit ist im Installer enthalten.
3. Modell einrichten: BirdNET V3.0 ONNX und die Label-Datei nach `Dokumente\AMSEL\models\` legen (Anleitung: [Bedienungsanleitung](Bedienungsanleitung.md), Kapitel 2). Das Modell ist nicht im Installer enthalten.

Eine neue Version wird ueber die alte installiert; Einstellungen, Modelle, Referenzen und Projekte unter `Dokumente\AMSEL\` bleiben erhalten.

Die vollstaendige Anleitung steht in der [Bedienungsanleitung](Bedienungsanleitung.md), die Aenderungen je Version im [Changelog](CHANGELOG.md).

## Voraussetzungen

- Windows 10/11 (64-Bit)
- ~500 MB Festplatte (App + Modelle + Referenzen)
- Internetverbindung fuer Xeno-Canto Downloads
- Optional: ffmpeg im PATH (M4A-Import, Export als FLAC/M4A/MP3)
- Nur zum Bauen aus den Quellen: JDK 21

## Schnellstart

### Aus den Quellen bauen
```bash
git clone https://github.com/Nikiphedia/AMSEL.git
cd AMSEL
set "JAVA_HOME=C:\Program Files\Microsoft\jdk-21.0.10.7-hotspot"
.\gradlew.bat run
```

### Installer bauen
```bash
.\gradlew.bat test
.\gradlew.bat packageMsi
# Output: build/compose/binaries/main/msi/AMSEL-<Version>.msi
```

### Release erstellen
1. Version in `build.gradle.kts` an zwei Stellen anheben (`version`, `packageVersion`), ebenso in `Bedienungsanleitung.md` und `AMSEL_Projektbeschrieb.md`
2. `CHANGELOG.md` nachfuehren
3. `.\gradlew.bat test` und `.\gradlew.bat packageMsi`
4. Commit, Tag `v<Version>`, Push
5. GitHub-Release zum Tag anlegen und `AMSEL-<Version>.msi` anhaengen

### Erster Start
1. App starten → BirdNET V3.0 Modell erkennen oder manuell einrichten
2. Einstellungen → Datenbank → Artenset waehlen → Referenzen herunterladen
3. Audio-Datei importieren (WAV/MP3/FLAC/M4A)
4. Analyse starten → BirdNET klassifiziert erkannte Arten

## Datenverzeichnisse
```
~/Documents/AMSEL/
├── models/       — ONNX-Modelle (BirdNET V3.0, Perch v2)
├── references/   — Referenzbibliothek (Xeno-Canto PNGs + MP3s)
├── cache/        — Feature-Vektoren
├── species/      — species_master.json + region_sets.json
├── settings.json — Einstellungen
└── perf.log      — Laufzeiten der Scans
```

## JSON-Daten separat verwenden

Die Artendaten sind als standalone JSON-Dateien nutzbar:

| Datei | Beschreibung | Groesse |
|-------|-------------|--------|
| `species/species_master.json` | 11'565 Taxa, 23 Sprachen, IUCN-Status | ~9.5 MB |
| `species/region_sets.json` | 4 Artensets (CH Brut/Komplett, Mitteleuropa, Alle) | ~21 KB |

Die Dateien werden beim ersten Start nach `~/Documents/AMSEL/species/` kopiert und koennen dort frei editiert werden.

**Download:** Die JSON-Dateien sind auch separat im Release als Assets verfuegbar.

## Technologie

- Kotlin 2.4.21, Compose Multiplatform 1.12.2, Material 3
- ONNX Runtime 1.31.0
- Keine externen Datenbanken (rein dateibasiert)

## Lizenz

GPL v3 — siehe [LICENSE](LICENSE)
Fuer kommerzielle Lizenzoptionen: support@etasystems.ch

## Mitwirkende

ETA Systems — https://etasystems.ch
