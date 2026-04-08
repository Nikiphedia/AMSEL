# Bedienungsanleitung: AMSEL
Version: 0.0.6
Stand: 2026-04-08

---

## Was ist AMSEL?

AMSEL (Another Mel Spectrogram Event Locator) ist eine Desktop-Anwendung zur Analyse von Tieraufnahmen. Sie erzeugt Sonogramme aus Audio-Dateien, erkennt automatisch Vogelarten und Fledermaeuse, und erstellt Berichte. AMSEL richtet sich an Biologen, Ornithologen und Naturschutzfachleute, die Feldaufnahmen auswerten moechten.

---

## Voraussetzungen

- **Windows-PC** mit installiertem AMSEL (MSI-Installer)
- **Audio-Dateien** in einem der unterstuetzten Formate: WAV, MP3, FLAC, OGG, M4A/AAC
- **Optional — fuer automatische Artenerkennung:** Python 3 mit dem Paket `birdnetlib` (BirdNET)
- **Optional — fuer FLAC/M4A/MP3-Export:** `ffmpeg` im System-PATH (WAV-Export funktioniert immer)

---

## Erster Start — Setup-Assistent

Beim ersten Programmstart erscheint ein Einrichtungs-Assistent. Er fragt vier Ordner ab:

**So wird er verwendet:**
1. AMSEL starten. Der Setup-Assistent erscheint automatisch.
2. **Audio-Import-Ordner** festlegen — hier sucht AMSEL nach Ihren Aufnahmen. Vorschlag: `Dokumente\AMSEL\audio`
3. **Projekt-Ordner** festlegen — hier werden Ihre Projekte gespeichert. Vorschlag: `Dokumente\AMSEL\projekte`
4. **Export-Ordner** festlegen — hier landen exportierte Dateien. Vorschlag: `Desktop`
5. **Modell-Ordner** festlegen — hier werden die Erkennungsmodelle abgelegt. Vorschlag: `Dokumente\AMSEL\models`
6. Auf **"Fertig"** klicken. Die Ordner werden automatisch angelegt.

**Ergebnis:** AMSEL merkt sich die Ordner und zeigt den Assistent nicht erneut. Die Ordner koennen spaeter in den Einstellungen geaendert werden.

> Tipp: Mit "Abbrechen" ueberspringen Sie den Assistent. AMSEL verwendet dann Standard-Ordner.

---

## Funktionen

### Audio-Datei oeffnen

**Was sie tut:**
Laedt eine Audio-Datei und erzeugt ein Sonogramm (Frequenz-Zeit-Diagramm).

**So wird sie verwendet:**
1. Audio-Datei per **Drag & Drop** ins Programmfenster ziehen.
   — Oder: Ueber das Menue eine Datei auswaehlen.
2. AMSEL berechnet das Sonogramm und zeigt es an.

**Ergebnis:** Das Sonogramm erscheint im Hauptfenster. Oben eine Uebersichtsleiste, darunter die Detailansicht.

**Unterstuetzte Formate:** WAV, MP3, FLAC, OGG, M4A, AAC, M4P

---

### Sonogramm bedienen

**Was sie tut:**
Navigation und Zoom im Sonogramm, um einzelne Lautaeusserungen genauer zu betrachten.

**So wird sie verwendet:**
1. **Zoomen:** Mausrad im Sonogramm drehen — vergroessert oder verkleinert den sichtbaren Zeitausschnitt.
2. **Verschieben:** Mit der Maus im Sonogramm klicken und ziehen.
3. **Uebersichtsleiste:** Der obere Streifen zeigt die gesamte Aufnahme. Klicken Sie dort, um zu einer bestimmten Stelle zu springen.

**Ergebnis:** Sie koennen jede Stelle der Aufnahme in beliebiger Vergroesserung betrachten.

---

### Voreinstellungen (Presets)

**Was sie tut:**
Wechselt zwischen optimierten Anzeige-Einstellungen fuer verschiedene Tiergruppen.

**So wird sie verwendet:**
1. Im Sonogramm-Bereich das gewuenschte Preset waehlen:
   - **Bird-Preset:** 100 – 16.000 Hz (Voegel)
   - **Bat-Preset:** 15.000 – 125.000 Hz (Fledermaeuse)

**Ergebnis:** Der angezeigte Frequenzbereich passt sich an die gewaehlte Tiergruppe an.

---

### Audio-Filter anwenden

**Was sie tut:**
Verbessert die Hoerbarkeit und Sichtbarkeit von Tierstimmen durch digitale Klangbearbeitung.

**So wird sie verwendet:**
1. Das **Filter-Panel** oeffnen (seitlich im Sonogramm-Bereich).
2. Gewuenschte Filter aktivieren und einstellen:
   - **Bandpass-Filter:** Frequenzbereich eingrenzen (z.B. nur 2.000–8.000 Hz)
   - **Lautstaerke:** Pegel anpassen
   - **Rauschunterdrueckung:** Hintergrundgeraeusche (Wind, Regen) reduzieren
   - **Normalisierung:** Pegel auf einheitliche Lautstaerke bringen
3. Aenderungen werden sofort im Sonogramm und bei der Wiedergabe hoerbar.

**Ergebnis:** Schwache oder verrauschte Aufnahmen werden klarer. Filter koennen beim Export angewendet werden.

---

### Automatische Artenerkennung (BirdNET)

**Was sie tut:**
Erkennt automatisch, welche Vogelarten in der Aufnahme zu hoeren sind. Verwendet das BirdNET-Modell.

**Voraussetzung:** Python 3 mit `birdnetlib` muss installiert sein. Ohne Python ist der Button deaktiviert.

**So wird sie verwendet:**
1. Audio-Datei laden.
2. Optional: Einen Bereich markieren, um nur diesen Ausschnitt zu analysieren.
3. Den **BirdNET-Button** klicken.
4. Warten, bis die Analyse abgeschlossen ist.

**Ergebnis:** AMSEL zeigt eine Liste erkannter Arten mit Konfidenz-Wert (0–100%). Jeder Treffer wird als Annotation im Sonogramm markiert. Die Artnamen werden in der eingestellten Sprache angezeigt (23 Sprachen verfuegbar).

> Hinweis: AMSEL startet einen lokalen BirdNET-Dienst im Hintergrund. Beim ersten Aufruf dauert es etwas laenger, da das Modell geladen wird.

---

### Annotationen verwalten

**Was sie tut:**
Markierungen im Sonogramm erstellen, benennen, verifizieren und kommentieren.

**So wird sie verwendet:**

**Annotation erstellen:**
1. Im Sonogramm einen Bereich markieren.
2. Die Annotation wird im **Kandidaten-Panel** (rechts) angezeigt.

**Art zuweisen / aendern:**
1. Auf einen Kandidaten im Panel klicken, um ihn auszuwaehlen.
2. **Doppelklick** auf einen Kandidaten oeffnet den Label-Editor.
3. Artnamen eingeben. Mit **Enter** bestaetigen, mit **Escape** abbrechen.

**Verifizieren oder Ablehnen:**
1. Im Kandidaten-Panel den gewuenschten Kandidaten finden.
2. Auf das **Verifizieren-Symbol** klicken — markiert den Kandidaten als bestaetigt.
3. Oder auf das **Ablehnen-Symbol** klicken — markiert ihn als Fehlbestimmung.
4. Verifizierte Kandidaten zeigen ein Badge mit Operator-Name und Zeitstempel.

**Bemerkung hinzufuegen:**
1. Im Kandidaten-Panel nach unten scrollen zum Feld **"Bemerkung"**.
2. Text eingeben (z.B. "Fehlbestimmung", "Umgebungslaerm", "unsicher").

**Ergebnis:** Annotationen werden im Projekt gespeichert. Verifizierungsstatus, Operator und Zeitstempel erscheinen in Exporten (CSV und PDF).

---

### Akustischer Vergleich

**Was sie tut:**
Vergleicht eine Aufnahme mit Referenz-Sonogrammen, um die Artbestimmung zu unterstuetzen.

**So wird sie verwendet:**
1. Eine Annotation auswaehlen.
2. AMSEL sucht aehnliche Aufnahmen — lokal in der Referenz-Bibliothek oder online via Xeno-Canto.
3. Ergebnisse werden mit Aehnlichkeits-Score angezeigt.

**Ergebnis:** Eine rangierte Liste von Referenzaufnahmen mit Konfidenz-Wert. Hilft bei der manuellen Artbestimmung.

---

### Referenz-Bibliothek

**Was sie tut:**
Verwaltet eine Sammlung von Referenz-Sonogrammen fuer den akustischen Vergleich.

**So wird sie verwendet:**
1. Ueber den **Referenz-Editor** oeffnen.
2. **Bulk-Download:** Referenz-Aufnahmen von Xeno-Canto herunterladen.
3. **Eigene Aufnahmen:** Audio-Dateien importieren (WAV, MP3, FLAC, M4A, M4P).
4. **BirdNET-Analyse:** Im Referenz-Editor auf den BirdNET-Button klicken, um den sichtbaren Bereich zu analysieren.
5. **Audio-Export:** Ausschnitte als WAV, FLAC oder M4A exportieren (optional mit Filtern).
6. **CSV-Export:** BirdNET-Ergebnisse als Artenliste exportieren.

**Ergebnis:** Eine kuratierte Sammlung in `Dokumente\AMSEL\curated\` und eigene Aufnahmen in `Dokumente\AMSEL\user\`.

**Verfuegbare Artenlisten (Regions-Sets):**
- Schweizer Brutvoegel (180 Arten)
- Mitteleuropa (302 Arten)
- Global (1.000+ Arten)

---

### Export

**Was sie tut:**
Speichert Sonogramme, Audio-Ausschnitte und Analyseberichte in verschiedenen Formaten.

#### Bild-Export (PNG)
1. Gewuenschten Ausschnitt im Sonogramm einstellen.
2. Export-Funktion waehlen → **PNG**.

**Ergebnis:** Sonogramm als Bilddatei mit Achsenbeschriftung und Annotationen.

#### Audio-Export
1. Bereich im Sonogramm markieren.
2. Export-Funktion waehlen → Format auswaehlen:
   - **WAV** — funktioniert immer, verlustfrei
   - **FLAC** — verlustfrei, kleinere Datei (benoetigt ffmpeg)
   - **M4A** — komprimiert (benoetigt ffmpeg)
   - **MP3** — komprimiert, 192 kbit/s (benoetigt ffmpeg)
3. Optional: Filter auf den Export anwenden.

**Ergebnis:** Audio-Datei des markierten Bereichs im gewaehlten Format.

> Hinweis: Ohne ffmpeg sind FLAC/M4A/MP3 ausgegraut. WAV funktioniert immer.

#### PDF-Bericht
1. Export-Funktion waehlen → **PDF**.

**Ergebnis:** Analysebericht im Querformat (A4 Landscape) mit:
- Zusammenfassung der Aufnahme
- Tabelle aller Annotationen mit: Startzeit, Endzeit, Dauer, Art, Konfidenz, Status (verifiziert/abgelehnt/offen), Operator, Datum, Bemerkung

#### Arten-CSV
1. BirdNET-Analyse muss vorher gelaufen sein (Button sonst deaktiviert).
2. Auf das **CSV-Symbol** in der Sonogramm-Toolbar klicken.
3. Speicherort waehlen.

**Ergebnis:** CSV-Datei (Semikolon-getrennt, UTF-8) mit Spalten:
`Startzeit; Endzeit; Art; Wissenschaftlicher Name; Konfidenz; Status; Verifiziert von; Verifiziert am; Bemerkung`

---

### Projekt speichern und laden

**Was sie tut:**
Sichert den gesamten Arbeitsstand in einer Projektdatei und laedt ihn wieder.

**So wird sie verwendet:**
1. **Speichern:** Menue → Speichern. Waehlen Sie einen Dateinamen und Speicherort.
2. **Laden:** Menue → Oeffnen. Waehlen Sie eine `.amsel`-Datei.

**Ergebnis:** Alle Annotationen, Verifizierungen, Bemerkungen und Einstellungen werden in der Projektdatei gespeichert. Beim Laden wird der exakte Zustand wiederhergestellt.

> Hinweis: Die Audio-Datei selbst wird nicht in der Projektdatei gespeichert. Sie muss am selben Ort verfuegbar sein.

---

### Einstellungen

**Was sie tut:**
Passt AMSEL an Ihre Arbeitsweise an.

**Verfuegbare Einstellungen:**
- **Allgemein:** Sprache der Artnamen (23 Sprachen), wissenschaftliche Namen ein/aus, Operator-Name
- **Analyse:** Analyse-Parameter, Chunk-Laenge
- **Datenbank:** Pfade fuer Modelle, Referenzen, Cache
- **Export:** Standard-Export-Ordner, Formate

**Fenster-Layout:**
- AMSEL merkt sich Fenstergroesse und -position beim Schliessen.
- Seitenleisten-Breite und Galerie-Hoehe werden ebenfalls gespeichert.
- Bei Mehrbild-Setups: Falls der Monitor nicht mehr angeschlossen ist, oeffnet AMSEL auf dem Haupt-Monitor.

---

## Was das Produkt nicht kann

- **Keine Echtzeit-Aufnahme:** AMSEL analysiert vorhandene Audio-Dateien. Sie koennen nicht direkt mit dem Mikrofon aufnehmen.
- **Kein automatisches Herunterladen von BirdNET:** Python und birdnetlib muessen manuell installiert werden.
- **Keine 100% sichere Artbestimmung:** BirdNET liefert Wahrscheinlichkeiten, keine Garantien. Eine manuelle Verifizierung durch Fachpersonen ist empfohlen.
- **Keine Batch-Verarbeitung:** Es kann jeweils eine Audio-Datei gleichzeitig analysiert werden.
- **Kein Linux / macOS:** AMSEL laeuft derzeit nur unter Windows.

---

## Haeufige Fragen

**F: BirdNET erkennt keine Arten — der Button ist ausgegraut. Was tun?**
A: Python 3 und das Paket `birdnetlib` muessen installiert sein. AMSEL sucht Python automatisch in gaengigen Installationspfaden (AppData, Anaconda, Scoop, PATH). Pruefen Sie, ob `python --version` in der Eingabeaufforderung funktioniert und installieren Sie birdnetlib mit `pip install birdnetlib`.

**F: FLAC/M4A/MP3-Export ist ausgegraut. Warum?**
A: Diese Formate benoetigen `ffmpeg`. Laden Sie ffmpeg herunter und fuegen Sie es zum System-PATH hinzu. WAV-Export funktioniert ohne ffmpeg.

**F: Die Artenerkennung ist falsch. Wie korrigiere ich das?**
A: Doppelklicken Sie auf den Kandidaten im Kandidaten-Panel und tippen Sie den korrekten Artnamen ein. Nutzen Sie die Verifizieren/Ablehnen-Buttons, um den Status zu dokumentieren. Fuegen Sie eine Bemerkung hinzu fuer spaetere Nachvollziehbarkeit.

**F: Kann ich Artnamen in meiner Sprache anzeigen?**
A: Ja. In den Einstellungen unter "Allgemein" koennen Sie die Sprache der Artnamen waehlen. Es stehen 23 Sprachen zur Verfuegung, darunter Deutsch, Franzoesisch, Italienisch und Englisch.

**F: Meine alte Projektdatei laesst sich nicht oeffnen.**
A: Projektdateien aelterer Versionen sind kompatibel — neue Felder (Bemerkungen, Verifizierung) werden mit Standardwerten gefuellt. Die Audio-Datei muss am urspruenglichen Speicherort vorhanden sein.

**F: Wo finde ich meine exportierten Dateien?**
A: Im Export-Ordner, den Sie beim Setup festgelegt haben (Standard: Desktop). Sie koennen den Ordner in den Einstellungen aendern.

---

## Fehlermeldungen & Probleme

| Problem | Moegliche Ursache | Loesung |
|---------|-------------------|---------|
| "Python + birdnetlib nicht gefunden" | Python nicht installiert oder nicht im PATH | Python 3 installieren, `pip install birdnetlib` ausfuehren |
| Audio-Datei wird nicht geladen | Format nicht unterstuetzt oder Datei beschaedigt | In WAV konvertieren und erneut versuchen |
| FLAC/M4A/MP3-Export ausgegraut | ffmpeg nicht installiert | ffmpeg herunterladen und zum PATH hinzufuegen |
| Sonogramm ist leer/schwarz | Audio-Datei enthaelt Stille oder falsches Preset | Preset wechseln (Bird ↔ Bat), Lautstaerke pruefen |
| Fenster oeffnet sich ausserhalb des Bildschirms | Monitor-Setup geaendert seit letztem Start | AMSEL erkennt das automatisch und oeffnet auf dem Haupt-Monitor |
| BirdNET-Analyse dauert sehr lange | Erste Analyse laedt das Modell; grosse Dateien brauchen Zeit | Beim ersten Aufruf normal. Bei langen Dateien: Bereich markieren und nur Ausschnitt analysieren |
| CSV hat falsche Trennzeichen in Excel | Excel erwartet anderes Trennzeichen | Datei ueber "Daten → Aus Text/CSV" importieren. AMSEL nutzt Semikolon als Standard |

---

## Offene Punkte

- [OFFEN: Installer-Erstellung (MSI/EXE) fuer Endbenutzer-Verteilung — aktuell nur ueber Entwickler-Build verfuegbar]
- [OFFEN: Modell-Download-Dialog — Ablauf nach Installation: wie kommt das BirdNET/ONNX-Modell auf den Rechner?]
- [OFFEN: Fledermaus-Artenerkennung — Bat-Preset zeigt Sonogramm, aber automatische Arterkennung ist auf Voegel fokussiert (BirdNET)]

---

BDA erstellt. Offene Punkte: 3. Bereit zur Freigabe.