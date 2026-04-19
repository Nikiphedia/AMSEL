# AMSEL — Bedienungsanleitung

## Sonogramm-Analyse und Artenerkennung

Version 0.0.9

---

## 1. Ueberblick

AMSEL (Another Mel Spectrogram Event Locator) ist eine Desktop-Anwendung zur akustischen Bestimmung von Vogelarten, Fledermaeusen und anderen Tiergruppen. Sie erstellt Mel-Spektrogramme aus Audioaufnahmen, erkennt Lautaeusserungen automatisch mit BirdNET V3.0 (ONNX, nativ — kein Python noetig) und unterstuetzt den wissenschaftlichen Workflow von der Aufnahme bis zum Exportbericht.

### Hauptfunktionen
- **Spektrogramm-Darstellung** von WAV, MP3, FLAC und M4A/AAC Dateien
- **Drag & Drop**: Audio-Dateien direkt per Drag & Drop laden
- **BirdNET V3.0 ONNX** (nativ): Automatische Artbestimmung, 11'560 Arten, kein Python noetig
- **Referenzbibliothek** mit herunterladbaren Xeno-Canto Spektrogrammen
- **Annotationen** mit automatischer Berechnung akustischer Messwerte (Peak-Freq, BW, SNR)
- **Datei-Fortschrittsanzeige**: Verifikations-Balken + Counter pro Audio-Datei
- **GPS-Metadaten** pro Aufnahme (manuell, Raven-Import oder GPX-Tracklog)
- **Raven Selection Table Import/Export** (Raven Pro TSV-Format)
- **GPX-Tracklog Import** (automatische GPS-Zuordnung per Zeitstempel-Matching)
- **Export** im wissenschaftlichen Stil: PNG (600 DPI), PDF-Bericht, CSV, Raven-Tabelle
- **Projekt-Verwaltung**: mehrere Audio-Dateien pro Projekt, stabile IDs

---

## 2. Installation und Erster Start

### Voraussetzungen
- Windows 10/11 (64-bit)
- Java 21+ (wird mit dem Installer mitgeliefert)
- Internetverbindung fuer Xeno-Canto API (optional, fuer Online-Vergleich und Datenbank-Download)

### Installation
Installer `AMSEL-0.0.9.msi` ausfuehren und den Anweisungen folgen. Das Programm wird unter `C:\Program Files\AMSEL` installiert und ein Startmenue-Eintrag erstellt.

### Modelle einrichten

AMSEL benoetigt ein Klassifikationsmodell. Empfohlen wird BirdNET V3.0 ONNX.

**Option 1: BirdNET V3.0 ONNX (empfohlen)**

Nativ in AMSEL integriert — kein Python noetig. Erkennt 11'560 Arten.

1. Modell herunterladen: https://doi.org/10.5281/zenodo.18247420
   - `BirdNET+_V3.0-preview3_Global_11K_FP32.onnx` (516 MB) oder
   - `BirdNET+_V3.0-preview3_Global_11K_FP16.onnx` (259 MB, gleiche Genauigkeit)
2. Labels herunterladen: https://github.com/birdnet-team/birdnet-V3.0-dev
   - `BirdNET+_V3.0-preview3_Global_11K_Labels.csv`
3. Dateien ablegen unter `C:\Users\[NAME]\Documents\AMSEL\models\`
4. AMSEL starten — Modell wird automatisch erkannt

**Option 2: BirdNET V2.4 (Python, optional)**

Benoetigt Python 3.12: `pip install birdnet`

---

## 3. Bildschirmaufbau

```
+--------------------------------------------------+
| Toolbar (Datei-Aktionen, Zoom, Klassifikation)   |
+----------+---------------------------------------+
|          | Uebersicht (gesamte Aufnahme)         |
|          +---------------------------------------+
| Datei-   | Zeitleiste                            |
| liste    +---------------------------------------+
| (links)  | Zoom-Ansicht (Spektrogramm-Detail)    |
|          +---------------------------------------+
|          | Referenz-Sonogramm (DB-Treffer)       |
+----------+---------------------------------------+
| Annot-   | Ergebnisse (gruppiert nach Art)       |
| ationen  |                                       |
+----------+---------------------------------------+
```

- **Links oben**: Datei-Liste mit Fortschrittsbalken und Verifikations-Counter
- **Links unten**: Annotations-Panel (Markierungen mit Messwerten)
- **Mitte**: Sonogramm (Uebersicht + Zoom + Zeitleiste)
- **Rechts**: Ergebnis-Panel (Kandidaten, Referenzen)

---

## 4. Audio importieren

### Unterstuetzte Formate
- WAV (alle Sampleraten)
- MP3
- FLAC
- M4A / AAC

### So geht's
1. **Import-Button** (Ordnersymbol) in der Toolbar klicken, oder
2. **Drag & Drop**: Audio-Datei direkt vom Explorer ins Programmfenster ziehen
3. AMSEL berechnet automatisch das Uebersichts-Spektrogramm
4. Der Frequenzbereich wird automatisch erkannt:
   - **Vogel-Modus**: bis 16 kHz
   - **Fledermaus-Modus**: bis 125 kHz

---

## 5. Navigation im Sonogramm

### Uebersichtsleiste (oben)
- Zeigt die gesamte Aufnahme als kleines Spektrogramm
- Der **blaue Bereich** markiert den aktuell sichtbaren Ausschnitt
- **Klicken und Ziehen** im blauen Bereich verschiebt den Ausschnitt
- Annotationen werden als farbige Markierungen angezeigt

### Zoom-Steuerung (Toolbar)
- **+/-**: Hinein-/Herauszoomen
- **Reset**: Zoom zuruecksetzen (gesamte Aufnahme)
- **F+/F-**: Frequenz-Zoom (vertikale Vergroesserung)

### Spektrogramm-Parameter anpassen
Unter **Einstellungen → Analyse** koennen die Spektrogramm-Parameter live angepasst werden:
- **FFT-Fenstergroesse** (z.B. 1024, 2048, 4096): groesser = mehr Frequenzaufloesung
- **Hop-Groesse**: Ueberlappung der FFT-Fenster (kleiner = mehr Zeitaufloesung)
- **Mel-Bins**: Anzahl Mel-Filterbank-Kanaele
Aenderungen werden sofort neu berechnet — das aktuelle Spektrogramm wird aktualisiert.

---

## 6. Wiedergabe

- **Abspielen/Pausieren**: Play-Button in der Toolbar oder Leertaste
- Wenn eine **Annotation aktiv** ist, wird nur deren Zeitbereich abgespielt
- Die **rote Linie** zeigt die aktuelle Abspielposition

---

## 7. Markierungen (Annotationen)

### Manuell markieren
1. **Auswahlmodus** (Stift-Symbol) in der Toolbar aktivieren
2. **Rechteck ziehen** ueber den gewuenschten Bereich im Zoom-Sonogramm
3. **"Markierung erstellen"** klicken
4. Die Annotation erscheint in der Seitenleiste links

### Auto-Erkennung (Event Detection)
1. Klicken Sie auf das **Blitz-Symbol** in der Toolbar
2. AMSEL erkennt einzelne Lautaeusserungen energiebasiert
3. Erkannte Events werden automatisch als Annotationen angelegt

### Gummiband-Editing
1. Annotation auswaehlen (Klick in Seitenleiste)
2. **Editier-Modus** aktivieren
3. **Raender ziehen**: Links/Rechts = Zeitbereich, Oben/Unten = Frequenzbereich
4. **Mitte ziehen**: gesamte Annotation verschieben

### Akustische Messwerte (Metriken-Widget)
Zu jeder Annotation werden automatisch berechnet:
- **Peak-Frequenz** (kHz): Frequenz mit hoechster Energie
- **Bandbreite 3dB** (kHz): Frequenzbereich auf halber Maximalenergie
- **SNR** (dB): Signal-Rausch-Abstand (nur wenn > 0 dB)

Die Werte erscheinen direkt unter dem Frequenzbereich-Text in der Annotationsliste (in Teal-Farbe).

### Verifikation
- **Verifizieren**: Haken-Button neben einer Annotation → Annotation wird als korrekt markiert
- **Ablehnen**: X-Button → Annotation wird als falsch markiert
- Der **Fortschrittsbalken** in der Dateiliste aktualisiert sich automatisch

---

## 8. Datei-Fortschrittsanzeige

Im **Dateipanel links** wird pro Audio-Datei angezeigt:

- **Fortschrittsbalken** (3 dp hoher Balken, gruen): Anteil verifizierter Annotationen
- **"X/Y ✓"**: Numerischer Counter (X verifiziert von Y gesamt)
- **Farbindikator** (kleiner Kreis links neben Dateiname):
  - Gruen: mindestens eine verifizierte Annotation
  - Neutral: Annotationen vorhanden, keine verifiziert
  - Unsichtbar: keine Annotationen

---

## 9. BirdNET Artenerkennung

### Full-File-Scan
1. Audio importieren
2. Klicken Sie auf das **Musiknoten-Symbol** in der Toolbar
3. BirdNET analysiert die gesamte Aufnahme in 3-Sekunden-Chunks
4. Erkannte Arten erscheinen als farbige Annotationen im Sonogramm
5. In der Seitenleiste: Arten gruppiert nach hoechster Konfidenz

### Einzelsegment-Analyse
1. Annotation auswaehlen
2. Klassifikations-Button klicken
3. BirdNET klassifiziert nur diesen Zeitbereich

### Standort-Filter
In den Einstellungen (Zahnrad) → Tab "Allgemein" koennen Sie Ihren Standort eingeben. BirdNET filtert dann auf regional vorkommende Arten.

---

## 10. GPS-Metadaten

GPS-Koordinaten koennen auf drei Wegen pro Aufnahme gesetzt werden:

### A) Manuell im AudioMetadataDialog
1. Im Dateipanel rechtsklicken auf eine Datei → "Metadaten bearbeiten"
2. GPS-Felder ausfuellen: Breitengrad, Laengengrad, Hoehe
3. Bestaetigen mit "OK"

### B) Raven Selection Table Import (mit GPS-Spalten)
Wenn die importierte Raven-Tabelle die Spalten `Latitude`, `Longitude` und `Altitude (m)` enthaelt, werden die GPS-Daten automatisch in die Aufnahme-Metadaten uebernommen (erste gueltige Zeile).
Siehe Abschnitt 11.

### C) GPX-Tracklog Import
Laedt eine GPX-Datei und matcht alle geladenen Audio-Dateien per Zeitstempel.
Siehe Abschnitt 12.

---

## 11. Raven Selection Table Import/Export

AMSEL unterstuetzt das Raven Pro TSV-Format fuer Annotationstabellen.

### Export (Raven Selection Table)
1. Klicken Sie auf das **Export-Symbol** in der Toolbar
2. "Raven Selection Table" waehlen
3. Speicherort waehlen → .txt Datei wird erstellt
- Format: Tab-getrennt, Spalten: `Selection`, `Begin Time (s)`, `End Time (s)`, `Low Freq (Hz)`, `High Freq (Hz)`, `Species`, `Confidence` usw.

### Import (Raven Selection Table)
1. Klicken Sie auf das **Raven-Import-Symbol** in der Toolbar (Tabellen-Icon)
   - Der Button ist nur aktiv wenn eine Audio-Datei geladen ist
2. .txt Datei waehlen (Raven Pro TSV-Format)
3. AMSEL importiert alle gueltigen Zeilen als Annotationen
4. Falls die Tabelle GPS-Spalten enthaelt (`Latitude`, `Longitude`, `Altitude (m)`), werden diese als Aufnahme-Metadaten gesetzt

**Pflicht-Spalten:**
- `Begin Time (s)`, `End Time (s)`, `Low Freq (Hz)`, `High Freq (Hz)`

**Optionale Spalten:**
- `Species`, `Scientific Name`, `Confidence`, `Status`, `Notes`
- `Latitude`, `Longitude`, `Altitude (m)` (fuer GPS-Import)

---

## 12. GPX-Tracklog Import

Wenn Aufnahmen mit einem GPS-Logger oder Smartphone begleitet werden, koennen die Tracks nachtraeglich zugeordnet werden.

### Voraussetzung
- Die Audio-Dateien muessen Zeitstempel (Datum + Uhrzeit) in den Metadaten enthalten
- GPX-Datei mit `<trkpt>` oder `<wpt>` Elementen (Standard-GPX-Format)
- Zeitstempel im GPX muessen UTC sein (Standard bei GPS-Geraeten)

### Ablauf
1. Alle relevanten Audio-Dateien laden
2. Klicken Sie auf das **GPX-Import-Symbol** in der Toolbar (GPS-Icon)
3. GPX-Datei waehlen
4. AMSEL matcht jede Audio-Datei mit dem zeitlich naechsten GPS-Punkt
5. Statusmeldung: "GPX-Import: GPS fuer X von Y Dateien gesetzt"

Die GPS-Daten werden in den Aufnahme-Metadaten gespeichert und erscheinen im AudioMetadataDialog.

**Hinweise:**
- Der zeitlich naechste Punkt wird immer zugeordnet, auch bei grossem Zeitabstand
- UTC-Annahme: Aufnahmezeitstempel werden als UTC interpretiert. Bei Geraeten die lokale Zeit ohne Timezone-Info speichern entsteht ein Offset.

---

## 13. Rauschfilter

Oeffnen Sie das Filter-Panel mit dem **Filter-Symbol** in der Toolbar.

### Filter-Kette (Reihenfolge)
1. **Noise-Filter** → 2. **Expander/Gate** → 3. **Limiter** → 4. **Bandpass** → 5. **Median**

Filter werden **live** angewendet (300ms Verzoegerung).

### Noise-Filter
Entfernt leise Anteile unter einem Schwellenwert.

| Parameter | Bereich | Beschreibung |
|-----------|---------|-------------|
| Schwelle | 0-95 dB | Hoeher = mehr wird entfernt |

### Expander/Gate
Unterdrueckt Hintergrundrauschen.

| Parameter | Bereich | Beschreibung |
|-----------|---------|-------------|
| Schwelle | -30 bis +10 dB | Relativ zum Median |
| Ratio | 1:1.5 bis 1:8 | Staerke der Absenkung (nur Expander) |

**Typische Einstellungen:**

| Szenario | Schwelle | Range | Knee |
|----------|----------|-------|------|
| Leichtes Entrauschen | -10 dB | -40 dB | 6 dB |
| Moderates Gate | -5 dB | -60 dB | 3 dB |
| Aggressives Gate | 0 dB | -80 dB | 0 dB |

### Bandpass
Begrenzt den sichtbaren Frequenzbereich.

**Typische Vogelgesang-Bereiche:**
- Amsel: 1.5-8 kHz
- Blaumeise: 3-10 kHz
- Zaunkoenig: 2-12 kHz
- Goldhaehnchen: 5-10 kHz

### Filter-Presets
Filtereinstellungen koennen als benannte Presets gespeichert und geladen werden (Speichern-Symbol im Filter-Panel).

---

## 14. Export

### Sonogramm-PNG
1. Annotation auswaehlen (oder Zoom-Bereich nutzen)
2. **Export-Button** klicken
3. Format: PNG mit 600 DPI und eingebetteten Metadaten
4. S/W-Modus umschalten fuer Publikationen

### Audio-Export
- WAV, MP3, FLAC (ffmpeg erforderlich fuer non-WAV)
- Angewendete Filter werden eingerechnet

### PDF-Bericht
- Zusammenfassung + Annotationstabelle mit akustischen Messwerten
- Klick auf "Bericht exportieren" in der Toolbar

### CSV-Artenliste
- Zeitstempel, BirdNET-Konfidenz, Peak-Frequenz, Bandbreite, SNR
- Klick auf "CSV exportieren" in der Toolbar

### Raven Selection Table
- Tab-getrenntes TSV, kompatibel mit Raven Pro
- Klick auf das Export-Symbol → "Raven Selection Table waehlen"

---

## 15. Projekt-Verwaltung

### Projekt speichern
- **Strg+S** oder Speichern-Symbol in der Toolbar
- Format: `.amsel.json` (JSON, lesbar)
- Auto-Save alle 5 Minuten

### Projekt laden
- **Ordner-Symbol** → "Projekt oeffnen"
- Oder: Letzte Projekte im Startmenue

### Mehrere Audio-Dateien
- Mehrere Dateien koennen gleichzeitig in ein Projekt geladen werden
- Umschalten im Datei-Panel links
- Annotationen werden pro Datei separat gespeichert

---

## 16. Einstellungen

Einstellungen oeffnen: Zahnrad-Symbol in der Toolbar.

### Tab: Allgemein
- Standort (Lat/Lon) fuer BirdNET-Filterung
- Sprache fuer Artnamen
- Fenster-Layout

### Tab: Analyse
- BirdNET-Modell auswaehlen
- Konfidenz-Schwelle
- Spektrogramm-Parameter: FFT-Fenstergroesse, Hop-Groesse, Mel-Bins
- Min./Max. Frequenz fuer Analyse

### Tab: Datenbank
- Referenz-Bibliothek Pfad
- Artenset waehlen (CH Brutvoegel, Mitteleuropa, Global)
- Referenzen herunterladen

### Tab: Export
- Achsen-Konfiguration fuer Spektrogramm-PNG
- Standard-Exportformat

---

## 17. Tastaturkuerzel

| Taste | Funktion |
|-------|----------|
| Leertaste | Abspielen / Pausieren |
| Escape | Auswahlmodus beenden |
| Strg+S | Projekt speichern |

---

## 18. Daten und Speicherorte

| Pfad | Inhalt |
|------|--------|
| `~/Documents/AMSEL/settings.json` | App-Einstellungen, Filter-Presets |
| `~/Documents/AMSEL/models/` | ONNX-Modelle, Labels, Python-Skripte |
| `~/Documents/AMSEL/references/` | Xeno-Canto Referenz-Sonogramme |
| `~/Documents/AMSEL/species/` | species_master.json, region_sets.json |
| `*.amsel.json` | Projektdatei (frei waehlbarer Speicherort) |

---

## 19. Fehlerbehebung

### "Kein Modell gefunden"
→ BirdNET V3.0 ONNX-Datei und Labels unter `~/Documents/AMSEL/models/` ablegen

### "BirdNET Python nicht verfuegbar"
→ Optional: `pip install birdnet` (nur fuer V2.4 noetig, V3.0 laeuft nativ)

### Audio wird nicht geladen
→ Format pruefen: WAV, MP3, FLAC, M4A unterstuetzt. OGG nicht unterstuetzt.
→ ffmpeg installieren fuer FLAC/M4A/MP3-Export (Import funktioniert ohne ffmpeg)

### GPX-Import setzt kein GPS
→ Zeitstempel der Audio-Dateien pruefen (Einstellungen → Metadaten bearbeiten)
→ GPX-Datei muss Standard-UTC-Zeitstempel enthalten (`<time>` in ISO 8601)

### Spektrogramm sieht anders aus nach Einstellungen-Aenderung
→ Normal: Spektrogramm-Parameter wurden neu berechnet. Kein Duplikat in der Dateiliste.

### Programm reagiert langsam
→ Zoomen Sie nicht zu weit hinein (max. ~30 Sekunden Ausschnitt empfohlen)
→ Filter-Kette: weniger aktive Filter = schneller

---

## 20. Technische Details

- **FFT:** Mel-Spektrogramm (Anzeige), lineares STFT fuer Export
- **Export-FFT:** 16384 Punkte bei 48 kHz → ~2.93 Hz Aufloesung
- **Voegel:** Standard 100-16 kHz, 256 Mel-Bins
- **Fledermaeusse:** 15-125 kHz (automatische Erkennung)
- **BirdNET V3.0:** 3-Sekunden Chunks, 48 kHz, ONNX Runtime 1.19.0
- **GPS-Import:** UTC-Annahme fuer GPX-Zeitstempel und Aufnahme-Zeitstempel
- **Framework:** Kotlin 2.1.0, Jetbrains Compose Desktop 1.7.3, Material 3

---

*AMSEL — Akustische Analyse fuer Vogelkundler und Biowissenschaftler.*
