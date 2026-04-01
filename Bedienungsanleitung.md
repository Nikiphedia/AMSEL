# BirdSono — Bedienungsanleitung

## Sonogramm-Vergleichstool fuer Voegel und Fledermaeuse

Version 0.0.4

---

## 1. Ueberblick

BirdSono ist ein Desktop-Programm zur visuellen und akustischen Bestimmung von Vogelarten anhand ihrer Gesaenge und Rufe. Es erstellt Sonogramme (Spektrogramme) aus Audio-Aufnahmen und vergleicht diese mit einer Offline-Datenbank oder direkt mit Xeno-Canto, der weltweit groessten Vogelstimmen-Sammlung.

### Hauptfunktionen
- **Sonogramm-Darstellung** von WAV, MP3, FLAC, OGG und M4A/AAC Dateien
- **Drag & Drop**: Audio-Dateien direkt per Drag & Drop laden
- **Automatische Erkennung** ob Vogel- oder Fledermaus-Aufnahme (Frequenzbereich)
- **Offline-Datenbank** mit herunterladbaren Xeno-Canto Sonogrammen
- **Artvergleich** per MFCC-Aehnlichkeitsanalyse (offline und online)
- **Event-Detection** zur automatischen Erkennung einzelner Rufe
- **Normalisierung** auf -6 dBFS fuer optimalen Dynamikbereich
- **Rauschfilter** mit Noise-Filter, Expander/Gate, Limiter, Bandpass und Median
- **Filter-Presets** zum Speichern und Laden von Filtereinstellungen
- **Export** im wissenschaftlichen Stil (Glutz/Svensson): 600 DPI, lineares STFT, konfigurierbare Achsen

---

## 2. Installation und Erster Start

### Voraussetzungen
- Windows 10/11 (64-bit)
- Java 17+ (wird mit dem Installer mitgeliefert)
- Internetverbindung fuer Xeno-Canto API (optional, fuer Online-Vergleich und Datenbank-Download)

### Installation
Die Datei `BirdSono-0.0.4.exe` ausfuehren und den Anweisungen folgen. Das Programm wird unter `C:\Program Files\BirdSono` installiert und ein Startmenue-Eintrag erstellt.
Modelle installieren
AMSEL benötigt ein Klassifikationsmodell um Tierlaute zu erkennen. Aktuell werden zwei BirdNET-Modelle unterstützt.

Option 1: BirdNET V3.0 ONNX (empfohlen)
Nativ in AMSEL integriert — kein Python nötig. Erkennt 11'560 Arten (Vögel, Insekten, Amphibien, Säugetiere).

Hinweis: BirdNET V3.0 ist eine Developer Preview (Beta). Die Erkennung ist bereits sehr gut, kann sich aber in zukünftigen Versionen noch ändern.

Download

Modell herunterladen von Zenodo:

https://doi.org/10.5281/zenodo.18247420
Datei: BirdNET+_V3.0-preview3_Global_11K_FP32.onnx (516 MB)
Oder kleiner: BirdNET+_V3.0-preview3_Global_11K_FP16.onnx (259 MB, gleiche Genauigkeit)


Labels herunterladen vom selben GitHub-Repo:

https://github.com/birdnet-team/birdnet-V3.0-dev
Datei: BirdNET+_V3.0-preview3_Global_11K_Labels.csv



Installation

Dateien ablegen unter:

   C:\Users\[DEIN-NAME]\Documents\AMSEL\models\
In PowerShell:
powershell   # Ordner erstellen (falls nötig)
   mkdir "$env:USERPROFILE\Documents\AMSEL\models" -Force

   # Dateien verschieben (Beispiel aus Downloads-Ordner)
   Move-Item "$env:USERPROFILE\Downloads\BirdNET+_V3.0-preview3_Global_11K_FP32.onnx" "$env:USERPROFILE\Documents\AMSEL\models\"
   Move-Item "$env:USERPROFILE\Downloads\BirdNET+_V3.0-preview3_Global_11K_Labels.csv" "$env:USERPROFILE\Documents\AMSEL\models\"

AMSEL starten — das Modell wird automatisch erkannt.
Prüfen: Einstellungen → Tab "Analyse" → BirdNET V3.0 sollte als Option erscheinen. Falls nicht: Einstellungen → "Modelle verwalten..." → prüfen ob die Datei korrekt erkannt wurde.

FP32 vs FP16
VarianteDateigrösseGenauigkeitRAM-BedarfFP32516 MBReferenz~1.5 GBFP16259 MBGleich~800 MB
Empfehlung: FP16 reicht für die meisten Anwendungsfälle. FP32 nur bei Geräten mit viel RAM (>16 GB).

Option 2: BirdNET V2.4 (Python)
Benötigt eine Python-Installation. Erkennt 6'362 Vogelarten.
Voraussetzungen

Python 3.11, 3.12 oder 3.13 (Python 3.14 wird NICHT unterstützt!)

Installation

Python installieren (falls nötig):

powershell   winget install Python.Python.3.12
PowerShell danach neu starten.

BirdNET Python-Paket installieren:

powershell   pip install birdnet
Falls mehrere Python-Versionen installiert sind:
powershell   py -3.12 -m pip install birdnet

Prüfen:

powershell   python -c "import birdnet; print('BirdNET OK')"

AMSEL starten — BirdNET V2.4 (Python) erscheint automatisch in den Einstellungen.

Häufige Fehler
FehlerLösungNo matching distribution found for birdnetPython-Version ist zu neu (3.14). Python 3.12 installieren.python: command not foundPython nicht installiert oder nicht im PATH.ModuleNotFoundError: No module named 'birdnet'pip install birdnet nochmals ausführen.

Modell-Ordner Übersicht
Nach der Installation sollte der Ordner so aussehen:
C:\Users\[NAME]\Documents\AMSEL\models\
├── BirdNET+_V3.0-preview3_Global_11K_FP32.onnx   ← V3.0 Modell
├── BirdNET+_V3.0-preview3_Global_11K_Labels.csv   ← V3.0 Labels
├── models.json                                     ← Wird automatisch erstellt
├── classify.py                                     ← V2.4 Python-Bridge
└── classify_daemon.py                              ← V2.4 Python-Bridge

Tipp: Es können mehrere Modelle gleichzeitig installiert sein. In den Einstellungen (Tab "Analyse" → "Modelle verwalten...") kann zwischen ihnen gewechselt werden.


Eigene Modelle (Custom ONNX)
AMSEL unterstützt auch eigene ONNX-Modelle. In den Einstellungen → "Modelle verwalten..." → "Modell hinzufügen" kann eine beliebige .onnx-Datei mit optionaler Labels-Datei importiert werden.
Voraussetzungen für Custom-Modelle:

ONNX-Format (.onnx)
Audio-Input: 3-Sekunden Chunks, 48 kHz Sample-Rate
Output: Score-Vektor pro Chunk
Labels-Datei: Eine Art pro Zeile (wissenschaftlicher Name + englischer Name)


Lizenz der Modelle

BirdNET V3.0: CC BY-SA 4.0 — kommerziell nutzbar mit Namensnennung
BirdNET V2.4: CC BY-NC-SA 4.0 — nur für nicht-kommerzielle/Forschungszwecke


Zitation: Kahl, S., Wood, C. M., Eibl, M., & Klinck, H. (2021). BirdNET: A deep learning solution for avian diversity monitoring. Ecological Informatics, 61, 101236.

### Erster Start
1. BirdSono starten
2. Xeno-Canto API-Key eingeben (Schluesselsymbol in der Toolbar)
3. Offline-Datenbank herunterladen (Wolkensymbol in der Toolbar)

---

## 3. API-Key einrichten

BirdSono benoetigt einen kostenlosen Xeno-Canto API-Key fuer den Zugriff auf die Vogelstimmen-Datenbank.

### So erhalten Sie einen Key:
1. Besuchen Sie https://xeno-canto.org/account
2. Erstellen Sie ein kostenloses Konto (oder melden Sie sich an)
3. Unter "API access" finden Sie Ihren persoenlichen API-Key
4. In BirdSono: Klicken Sie auf das **Schluesselsymbol** (🔑) in der Toolbar
5. Fuegen Sie den Key ein und klicken Sie "Speichern"

Der Key wird lokal in `%APPDATA%\BirdSono\settings.json` gespeichert.

---

## 4. Offline-Datenbank aufbauen

Die Offline-Datenbank ermoeglicht schnelle Vergleiche ohne Internetverbindung.

### Datenbank herunterladen:
1. Klicken Sie auf das **Wolkensymbol** (☁️) in der Toolbar
2. Der Dialog zeigt den aktuellen Cache-Status (Anzahl Aufnahmen, Arten, Speicher)
3. Die **Artenliste** enthaelt 73 haeufige mitteleuropaeische Vogelarten — Sie koennen Arten hinzufuegen oder entfernen (wissenschaftliche Namen, eine pro Zeile)
4. **"Alle verfuegbaren Aufnahmen"** laedt alle Sonogramme pro Art (empfohlen)
5. Alternativ: Limit pro Art setzen (50–2000)
6. Klicken Sie **"Herunterladen"**

### Hinweise:
- Es werden **nur Sonogramm-Bilder** heruntergeladen (kein Audio) — ca. 50–150 KB pro Aufnahme
- Bereits vorhandene Aufnahmen werden uebersprungen
- Der Download kann jederzeit abgebrochen und spaeter fortgesetzt werden
- Typisch: 500–5000 Aufnahmen pro Art, je nach Haeufigkeit
- Speicherort: `%APPDATA%\BirdSono\cache\`

---

## 5. Audio importieren

### Unterstuetzte Formate:
- WAV (alle Sampleraten)
- MP3
- FLAC
- OGG
- M4A / AAC

### So geht's:
1. **Importieren-Button** (Ordnersymbol) in der Toolbar klicken, oder
2. **Drag & Drop**: Audio-Datei direkt vom Explorer ins Programmfenster ziehen
3. BirdSono berechnet automatisch das Uebersichts-Spektrogramm
4. Der Frequenzbereich wird automatisch erkannt:
   - **Vogel-Modus**: bis 16 kHz
   - **Fledermaus-Modus**: bis 125 kHz

### Bildschirmaufbau nach dem Import:
```
┌─────────────────────────────────────────────────┐
│ Toolbar                                          │
├──────────┬──────────────────────────────────────┤
│          │ Uebersicht (gesamte Aufnahme)         │
│          ├──────────────────────────────────────┤
│ Anmerk-  │ Zeitleiste                            │
│ ungen    ├──────────────────────────────────────┤
│          │ Zoom-Ansicht (Sonogramm-Detail)       │
│          ├──────────────────────────────────────┤
│          │ Referenz-Sonogramm (DB-Treffer)       │
│          ├──────────────────────────────────────┤
│          │ Ergebnisse (gruppiert nach Art)        │
└──────────┴──────────────────────────────────────┘
```

---

## 6. Navigation im Sonogramm

### Uebersichtsleiste (oben):
- Zeigt die gesamte Aufnahme als kleines Spektrogramm
- Der **blaue Bereich** markiert den aktuell sichtbaren Ausschnitt
- **Klicken und Ziehen** im blauen Bereich verschiebt den Ausschnitt
- **Klicken ausserhalb** zentriert den Ausschnitt auf diese Stelle
- Annotationen werden als farbige Markierungen angezeigt

### Zeitleiste:
- Zeigt Zeitmarkierungen in Sekunden
- **Handles links/rechts**: Anfang und Ende des Ausschnitts aendern
- **Bereich ziehen**: Gesamten Ausschnitt verschieben
- **Klick ausserhalb**: Viewport dorthin zentrieren

### Zoom-Steuerung (Toolbar):
- **🔍+**: Hineinzoomen (Ausschnitt halbieren)
- **🔍-**: Herauszoomen (Ausschnitt verdoppeln)
- **↺**: Zoom zuruecksetzen (gesamte Aufnahme)
- **F+/F-**: Frequenz-Zoom (vertikale Vergroesserung)
- **Full View**: Frequenzachse nimmt gesamte Fensterhoehe ein

### Praezise Navigation (Pfeile):
- **Linke Pfeile ◀▶**: Verschieben den **Startpunkt** des Ausschnitts
- **Rechte Pfeile ◀▶**: Verschieben den **Endpunkt** des Ausschnitts
- So kann der sichtbare Bereich praezise angepasst werden ohne den fummeligen Slider

### Performance-Hinweis:
Die Navigation ist fluessig — beim Ziehen werden nur die Koordinaten aktualisiert. Das detaillierte Spektrogramm wird erst nach 400ms Stillstand neu berechnet.

---

## 7. Wiedergabe

- **▶ / ⏸**: Abspielen / Pausieren
- **⏹**: Stoppen
- Wenn eine **Annotation aktiv** ist, wird nur deren Zeitbereich abgespielt
- Sonst wird der **sichtbare Ausschnitt** abgespielt
- Die **rote Linie** zeigt die aktuelle Abspielposition in beiden Ansichten

---

## 8. Markierungen (Annotationen)

Annotationen markieren interessante Bereiche im Sonogramm (einzelne Rufe, Gesangsphrasen).

### Manuell markieren:
1. Klicken Sie auf **"Markieren"** (✏️) in der Toolbar → Auswahlmodus aktiviert
2. **Ziehen Sie ein Rechteck** ueber den gewuenschten Bereich im Zoom-Sonogramm
3. Klicken Sie auf **"Markierung erstellen"** (➕)
4. Die Annotation erscheint in der Seitenleiste links

### Auto-Erkennung:
1. Klicken Sie auf **"Event erkennen"** (⚡) in der Toolbar
2. BirdSono analysiert den sichtbaren Bereich und erkennt einzelne Lautaeusserungen
3. Erkannte Events werden automatisch kategorisiert:
   - **Singvogel** (1-10 kHz)
   - **Grossvogel/Amphibie** (< 1.5 kHz)
   - **Insekt** (10-15 kHz)
   - **Fledermaus** (> 15 kHz)
4. Breitband-Stoerungen (Rauschen, menschliche Geraeusche) werden automatisch gefiltert
5. Events werden mit 0.5 sec Gap-Toleranz zusammengefuegt (Silben desselben Rufs)
6. Tipp: Noise Gate vorher aktivieren fuer bessere Ergebnisse

### Gummiband-Editing:
1. Annotation auswaehlen (Klick in Seitenleiste)
2. **"Bearbeiten"** Chip aktivieren (im Toolbar-Bereich)
3. **Raender ziehen**: Links/Rechts aendert Zeitbereich, Oben/Unten aendert Frequenzbereich
4. **Mitte ziehen**: Verschiebt die gesamte Annotation
5. Kleine Quadrate an den Raendern zeigen die Griffpunkte

### Annotation bearbeiten:
- **Klicken** auf eine Annotation in der Seitenleiste: auswaehlen (wird hervorgehoben)
- **Doppelklick** auf den Namen: Label aendern (z.B. "Amsel Gesang")
- **Loeschen**: Muelleimer-Symbol neben der Annotation
- **Sync**: Gruener Sync-Button neben dem Referenz-Sonogramm richtet den Viewport auf die Annotation aus

---

## 9. BirdNET Arterkennung (neu in v0.0.4)

BirdSono integriert BirdNET V2.4 (Cornell Lab), ein neuronales Netz das ueber 6000 Vogelarten erkennt.

### Voraussetzungen:
- Python 3.12 mit `birdnetlib` installiert
- BirdNET-Modell wird automatisch aus birdnetlib geladen

### Full-File-Scan:
1. Audio importieren
2. Klicken Sie auf das **Musiknoten-Symbol** (🎵) in der Toolbar
3. BirdNET analysiert die gesamte Aufnahme in 3-Sekunden-Chunks
4. Erkannte Arten erscheinen als **farbige Annotationen** im Sonogramm
5. In der Seitenleiste: Arten gruppiert, sortiert nach hoechster Konfidenz
6. Klick auf einen Event → Viewport zoomt auf den Bereich (mit Vor-/Nachlauf)

### Standort-Filter:
In den Einstellungen (⚙) → Tab "Allgemein" koennen Sie Ihren Standort eingeben. BirdNET filtert dann auf regional vorkommende Arten (z.B. nur europaeische Arten bei Standort Schweiz).

### Vergleichs-Algorithmen:
In den Einstellungen → Tab "Analyse" koennen Sie den Algorithmus waehlen:
- **BirdNET V2.4**: Neuronales Netz, 6000+ Arten (empfohlen)
- **MFCC Cosinus**: Klassischer Vergleich auf Mel-Cepstral-Koeffizienten
- **Fingerprint**: Akustischer Fingerabdruck
- **Embedding Vektor-Suche**: 43-dim MFCC-Pseudo-Embeddings

### Referenz-Sonogramme:
Nach einer BirdNET-Erkennung koennen Sie Referenz-Sonogramme laden:
1. Event in der Seitenleiste anklicken
2. **Sonogramm-Vergleich-Button** (🔍) in der Toolbar klicken
3. Oder: **Import-Dialog** → Tab "Art suchen" → Artname eingeben
4. Referenz-Sonogramme aus der Offline-Datenbank werden angezeigt

---

## 10. Artvergleich (manuell)

### So funktioniert der Vergleich:
1. **Annotation auswaehlen** (oder keine — dann wird der gesamte Zoom-Bereich verwendet)
2. Klicken Sie auf das **Sonogramm-Vergleich-Symbol** (🔍) in der Toolbar
3. BirdSono vergleicht den markierten Bereich mit der Datenbank

### Zwei Vergleichs-Modi:

**Offline (bevorzugt):**
- Vergleicht gegen die lokale Sonogramm-Datenbank
- Schnell, kein Internet noetig
- Voraussetzung: Offline-Datenbank muss heruntergeladen sein

**Online (Fallback):**
- Laedt Kandidaten direkt von Xeno-Canto
- Langsamer, braucht Internet + API-Key
- Wird automatisch verwendet wenn keine lokale Datenbank vorhanden

### Ergebnisse:
- Treffer werden **nach Art gruppiert** angezeigt
- Pro Gruppe: bester Treffer als Hauptkarte
- **Aufklappbar**: Alle Varianten einer Art anzeigen (verschiedene Ruftypen)
- **Aehnlichkeit** als Prozentwert und Farbbalken
- **Qualitaet** (A–E), Ruftyp (song, call, ...), Herkunftsland

### Referenz-Sonogramm:
- Klicken Sie auf einen **Treffer** → das Referenz-Sonogramm der Datenbank wird oberhalb der Ergebnisliste angezeigt
- So koennen Sie Ihr Sonogramm visuell mit dem Referenzbild vergleichen
- Der beste Treffer wird automatisch nach der Suche angezeigt

---

## 10. Rauschfilter

Oeffnen Sie das Filter-Panel mit dem **Filter-Symbol** (🎛️) in der Toolbar.

### Filter-Kette (Reihenfolge):
1. **Noise-Filter** → 2. **Expander/Gate** → 3. **Limiter** → 4. **Bandpass** → 5. **Median**

Filter werden **live** angewendet (300ms Verzoegerung) und wirken sich direkt auf das angezeigte Sonogramm und die Uebersicht aus.

---

### 10.1 Noise-Filter

Entfernt leise Anteile unter einem einstellbaren Schwellenwert. Ideal als erster Reinigungsschritt.

| Parameter | Bereich | Beschreibung |
|-----------|---------|-------------|
| Schwelle | 0 – 95 dB | In 0.5 dB Schritten. Hoeher = mehr wird entfernt |

- **10 dB**: Mild, nur leises Grundrauschen
- **30 dB**: Standard, gute Balance
- **60+ dB**: Aggressiv, nur starke Signale bleiben

---

### 10.2 Expander / Gate

Unterdrueckt leise Bereiche (Hintergrundrauschen). Zwei Modi:

**Weich (Expander):** Leise Bereiche werden abgesenkt, aber nicht komplett entfernt.
**Gate:** Leise Bereiche werden komplett stummgeschaltet.

#### Basis-Parameter:

| Parameter | Bereich | Beschreibung |
|-----------|---------|-------------|
| Schwelle | -30 bis +10 dB | Relativ zum Median. Negativer = mehr wird unterdrueckt |
| Ratio | 1:1.5 bis 1:8 | Nur Expander: Wie stark die Absenkung (1:4 = moderat) |

#### Erweiterte Parameter (aufklappbar mit "Erweitert"):

| Parameter | Bereich | Beschreibung |
|-----------|---------|-------------|
| Range | -120 bis 0 dB | Maximale Absenkung. -80dB = fast Stille, -20dB = dezent |
| Knee | 0 – 12 dB | Uebergangsbreite. 0 = hart, 6 = weich, 12 = sehr weich |
| Hysterese | 0 – 6 dB | Verhindert Flattern: Gate oeffnet frueher als es schliesst |
| Attack | 0 – 20 Frames | Wie schnell das Gate oeffnet (0 = sofort) |
| Release | 0 – 50 Frames | Wie schnell das Gate schliesst (0 = sofort) |
| Hold | 0 – 30 Frames | Mindest-Haltezeit in offener Position |

**Typische Einstellungen:**

| Szenario | Schwelle | Ratio | Range | Knee | Hysterese |
|----------|----------|-------|-------|------|-----------|
| Leichtes Entrauschen | -10 dB | 1:2 | -40 dB | 6 dB | 2 dB |
| Moderates Gate | -5 dB | — | -60 dB | 3 dB | 1 dB |
| Aggressives Gate | 0 dB | — | -80 dB | 0 dB | 0 dB |
| Feinarbeit Vogelgesang | -15 dB | 1:3 | -50 dB | 8 dB | 3 dB |

---

### 10.3 Bandpass

Begrenzt den angezeigten Frequenzbereich. Nuetzlich um irrelevante Frequenzen auszublenden.

| Parameter | Bereich | Beschreibung |
|-----------|---------|-------------|
| Tief (Hochpass) | 100 Hz – 16 kHz | Untere Grenzfrequenz |
| Hoch (Tiefpass) | 100 Hz – 16 kHz | Obere Grenzfrequenz |

- Die Slider sind **logarithmisch** skaliert (musikalisch korrekt)
- Neben dem Frequenzwert wird die naechste **musikalische Note** angezeigt (z.B. "440 Hz (A4)")
- Im Fledermaus-Modus: 10 kHz – 125 kHz

**Typische Vogelgesang-Bereiche:**
- Amsel: 1.5 – 8 kHz
- Blaumeise: 3 – 10 kHz
- Zaunkoenig: 2 – 12 kHz
- Goldhaehnchen: 5 – 10 kHz

---

### 10.4 Limiter

Entfernt alles ueber einer einstellbaren Schwelle — unsichtbar, aber effektiv gegen laute Stoergeraeusche.

| Parameter | Bereich | Beschreibung |
|-----------|---------|-------------|
| Ceiling | -40 bis 0 dB | Obergrenze |

---

### 10.5 Median-Filter

Glaettet das Sonogramm und reduziert Einzelpunkt-Stoerungen (Clicks, Pops).
Kernel einstellbar von 1x1 (aus) bis 10x10 (sehr aggressiv).

| Kernel | Wirkung |
|--------|---------|
| 1x1 | Kein Effekt (deaktiviert) |
| 2-3 | Fein, entfernt einzelne Stoerpixel |
| 4-5 | Moderat, glaettet leichte Stoerungen |
| 6-10 | Aggressiv, starke Glaettung (Detailverlust moeglich) |

---

## 11. Filter-Presets

Speichern Sie Ihre Filtereinstellungen als benannte Presets fuer wiederholte Verwendung.

### Preset speichern:
1. Stellen Sie die gewuenschten Filter ein
2. Klicken Sie auf das **Speichern-Symbol** (💾) im Filter-Panel
3. Geben Sie einen Namen ein (z.B. "Wald leises Rauschen", "Gewitter aggressiv")
4. Klicken Sie **"Speichern"**

### Preset laden:
1. Klicken Sie auf **"Preset..."** im Filter-Panel
2. Waehlen Sie ein gespeichertes Preset aus dem Dropdown
3. Alle Filter werden sofort auf die gespeicherten Werte gesetzt

### Preset loeschen:
- Im Dropdown: Klicken Sie auf das **Muelleimer-Symbol** neben dem Preset-Namen

Presets werden dauerhaft in `%APPDATA%\BirdSono\settings.json` gespeichert.

---

## 12. Export

Exportieren Sie annotierte Sonogramm-Ausschnitte als hochaufloesende Bilddatei im wissenschaftlichen Stil (Glutz/Svensson).

### Grundlagen:
1. **Annotation auswaehlen** (oder Zoom-Bereich nutzen)
2. **S/W oder Farbe** umschalten (Toolbar-Button)
3. **"Exportieren"** klicken
4. Speicherort und Dateinamen waehlen
5. Format: PNG mit 600 DPI und eingebetteten Metadaten

### Export-Qualitaet:
- **Lineares STFT** mit 16384 FFT (~2.93 Hz Frequenzaufloesung)
- **600 DPI** Druckqualitaet
- **Bicubic-Skalierung** fuer glatte Darstellung
- **Gamma-Korrektur** fuer optimale Graustufen-Uebergaenge
- **Filter werden korrekt gerendert** (Noise Gate → weisser Hintergrund in S/W)

### Export-Einstellungen (Zahnrad-Symbol):
Ueber den **Export-Einstellungen-Dialog** koennen Sie die Achsen konfigurieren:

| Einstellung | Standard | Beschreibung |
|-------------|----------|-------------|
| Untere Grenzfrequenz | 0 Hz | Beginn der Y-Achse |
| Obere Grenzfrequenz | 16000 Hz | Ende der Y-Achse |
| Schrittweite | 2000 Hz | Abstand Hauptstriche (= 1 cm im Druck) |

**Presets:**
- **Voegel**: 0 – 16 kHz, 2 kHz Schritte
- **Fledermaeuse**: 15 – 125 kHz, 10 kHz Schritte
- **Insekten**: 0 – 50 kHz, 5 kHz Schritte

### Achsen-Layout:
- **Frequenz (Y)**: Linear, Hauptstriche beschriftet, Zwischenstriche bei halber Schrittweite
- **Zeit (X)**: Relativ ab 0, Hauptstriche alle 0.5 sec beschriftet, Zwischenstriche alle 0.25 sec
- **Neue Zeile** alle 4 Sekunden
- **1 cm Rand** um das gesamte Bild
- **Markierungsname** zentriert am oberen Rand

### Metadaten im PNG:
Unsichtbar in tEXt-Chunks eingebettet: Frequenzbereich, Dynamikbereich, Filter, Darstellungsmodus, Zeitbereich, erkannte Events.

---

## 13. Normalisierung

Die Normalisierung setzt den Dynamikbereich optimal fuer Analyse und Export.

1. **Markierung auswaehlen** (oder Zoom-Bereich nutzen)
2. **Normalisieren-Button** in der Toolbar klicken
3. BirdSono findet den lautesten Peak in der Markierung
4. Das **gesamte Audio** wird so verstaerkt, dass dieser Peak bei **-6 dBFS** liegt
5. Alle Spektrogramme werden automatisch neu berechnet

**Hinweis:** Die Normalisierung aendert die internen Audio-Daten (nicht die Originaldatei). Nach Normalisierung zeigt das Sonogramm mehr Dynamik und Details.

---

## 14. Tastaturkuerzel

| Taste | Funktion |
|-------|----------|
| Leertaste | Abspielen / Pausieren |
| Escape | Auswahlmodus beenden |

---

## 15. Daten und Speicherorte

| Pfad | Inhalt |
|------|--------|
| `%APPDATA%\BirdSono\settings.json` | API-Key, Filter-Presets |
| `%APPDATA%\BirdSono\cache\index.json` | Datenbank-Index |
| `%APPDATA%\BirdSono\cache\sono\` | Sonogramm-Bilder (JPG) |

### Cache leeren:
Den Ordner `%APPDATA%\BirdSono\cache\` loeschen. Beim naechsten Start wird er leer neu erstellt.

---

## 16. Fehlerbehebung

### "API-Key benoetigt"
→ Schluesselsymbol klicken und Xeno-Canto Key eingeben (kostenlos auf xeno-canto.org)

### "Keine Treffer"
→ Versuchen Sie einen laengeren Ausschnitt oder laden Sie die Offline-Datenbank herunter

### Sonogramm-Bilder werden nicht angezeigt
→ Pruefen Sie ob der Cache-Ordner existiert und Dateien enthaelt
→ Stellen Sie sicher dass die Internetverbindung funktioniert (fuer Remote-Bilder)

### Programm reagiert langsam
→ Zoomen Sie nicht zu weit hinein (max. ~30 Sekunden Ausschnitt)
→ Deaktivieren Sie nicht benoetigte Filter

### Download bricht ab
→ Einfach erneut starten — bereits vorhandene Aufnahmen werden uebersprungen

---

## 17. Artenliste (Standard-Download)

Die vorinstallierte Artenliste umfasst 73 haeufige mitteleuropaeische Vogelarten:

**Drosseln:** Amsel, Singdrossel, Misteldrossel
**Meisen:** Kohlmeise, Blaumeise, Tannenmeise, Haubenmeise, Sumpfmeise
**Finken:** Buchfink, Stieglitz, Gruenling, Girlitz, Gimpel, Kernbeisser
**Laubsaenger:** Zilpzalp, Fitis, Waldlaubsaenger
**Grasmuecken:** Moenchsgrasmuecke, Gartengrasmucke, Dorngrasmucke
**Spechte:** Buntspecht, Kleinspecht, Schwarzspecht, Gruenspecht, Wendehals
**Eulen:** Waldkauz, Steinkauz, Waldohreule, Schleiereule
**Greifvoegel:** Maeusebussard, Sperber, Turmfalke
**Und weitere:** Rotkehlchen, Kleiber, Baumlaufer, Zaunkoenig, Goldhaehnchen, Star, Heckenbraunelle, Nachtigall, Eisvogel, Wasseramsel, Schwalben, Mauersegler u.v.m.

Sie koennen jederzeit weitere Arten hinzufuegen — einfach den wissenschaftlichen Namen in die Download-Liste eintragen.

---

## 18. Technische Details

- **Frequenzanalyse:** FFT-basiertes Mel-Spektrogramm (Anzeige), lineares STFT fuer Export
- **Export-FFT:** 16384 Punkte bei 48 kHz → ~2.93 Hz Aufloesung
- **Vergleichsmethode:** MFCC (Mel-Frequency Cepstral Coefficients) + Cosinus-Aehnlichkeit
- **Voegel:** Frequenzbereich bis 16 kHz, 160 Mel-Baender (konfigurierbar)
- **Fledermaeuse:** Frequenzbereich bis 125 kHz (automatische Erkennung)
- **M4A/AAC Decoder:** JCodec/JAAD (pure Java, kein FFmpeg noetig)
- **Datenquelle:** Xeno-Canto API v3 (https://xeno-canto.org)
- **Framework:** Kotlin 2.1.0 + Jetpack Compose Desktop 1.7.3 + Material 3

---

*BirdSono — Weil jeder Vogel eine Stimme hat.*
