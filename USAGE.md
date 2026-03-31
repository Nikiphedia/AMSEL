# AMSEL — Bedienungsanleitung

## Audioaufnahme importieren

- Datei → Öffnen oder Drag & Drop
- Unterstützte Formate: WAV, MP3, FLAC
- Grosse Dateien werden in Chunks aufgeteilt, PCM-Cache für schnellen Random Access

## Spektrogramm-Ansicht

- **Oben:** Übersichtsstreifen (gesamte Aufnahme)
- **Unten:** Gezoomter Bereich mit Detailansicht
- **Mausrad:** Zoom rein/raus
- **Klick+Ziehen:** Viewport verschieben
- **Cursor-Info:** Hz, kHz, musikalische Note + Cents bei Hover
- **Frequenzachse:** Log/Linear umschaltbar
- **Full View Modus:** Frequenzachse über ganzes Fenster

## Filter / Signal Chain

Die Filter werden in professioneller Reihenfolge angewendet:

1. Bandpass (Hochpass + Tiefpass)
2. Spectral Gating (frequenzband-individuell, Auto-Rauschprofil)
3. Noise Filter
4. Gate
5. Limiter (Hard-Clip, invertierte Skala: 0 dB = offen)
6. Median (1×1 bis 10×10)
7. Normalisierung (−6 dBFS)

- Filter-Tabs werden grün bei aktiver Bearbeitung
- Bypass per Doppelklick auf Tab
- Gefiltertes Audio abspielen möglich

## Analyse / Klassifikation

1. Einstellungen → Tab "Analyse" → Modell wählen (BirdNET V3.0 empfohlen)
2. "Analyse starten" → BirdNET scannt alle Chunks
3. Ergebnisse erscheinen als Annotationen im Spektrogramm
4. Konfidenzwert pro Erkennung, Schwelle einstellbar (0.01–0.5)

## Annotationen

- **Automatisch** aus BirdNET oder **manuell** erstellen
- Klick auf Annotation: Details + Referenzvergleich
- Labels bearbeiten, löschen, verschieben
- Doppelklick auf Event: Label editieren
- Edit-Mode für manuelle Korrekturen
- Annotationen nach Arten gruppiert (ausklappbar, sortiert nach Konfidenz)

## Referenzbibliothek

1. Einstellungen → Tab "Datenbank"
2. "Referenzen herunterladen" → Xeno-Canto Spektrogramme als PNG
3. Artenset wählen: Alle, Schweiz oder Mitteleuropa
4. Qualitätsfilter: A–E für Download und Anzeige getrennt einstellbar
5. "Audio herunterladen" → MP3s für Offline-Abspielen
6. "Rescan" → Referenzen neu einlesen ohne Neustart

Bereits heruntergeladene Referenzen werden automatisch übersprungen.

## Referenz-Vergleich

- Bei Klassifikationsergebnis: Ähnliche Referenzen anzeigen
- Play-Button auf Referenz: MP3 on-demand abspielen
- Sonogramm-Galerie mit Thumbnails
- 4 Vergleichs-Algorithmen: MFCC, Fingerprint, BirdNET, Embedding

## Modelle verwalten

- Einstellungen → Tab "Analyse" → "Modelle verwalten..."
- **BirdNET V3.0** (ONNX, nativ) — empfohlen
- **BirdNET V2.4** (Python-Bridge) — Fallback
- Custom ONNX-Modelle hinzufügen
- Typ-Erkennung: BirdNET V3, V2, Custom

## Projekt speichern / laden

- **Speichern:** Datei → Speichern → `.amsel.json`
- Enthält: Audio-Referenz, Annotationen, Einstellungen, Audit-Trail
- **Laden:** Datei → Öffnen → Projekt wiederherstellen
- Filter-Einstellungen bleiben beim Speichern/Laden erhalten

## Export

- Spektrogramm als PNG (Glutz-Massstab: 5.5 cm/sec, 2 kHz/2 cm, 600 DPI)
- Audio-Segment als WAV oder MP3
- Export-Einstellungen konfigurierbar (Frequenz, Zeit, Zeilenlänge)
- S/W-Palette für Druck
- Audit-Trail in PNG-Metadaten

## Audio-Wiedergabe

- Playback spielt gesamtes Ansichtsfenster
- Auto-Follow: Viewport folgt der Abspielposition
- Gefiltertes Audio abspielen (mit aktiven Filtern)
