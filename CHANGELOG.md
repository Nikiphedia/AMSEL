# AMSEL Changelog

Alle wichtigen Änderungen an diesem Projekt werden in dieser Datei dokumentiert.
Format basiert auf [Keep a Changelog](https://keepachangelog.com/de/).

## v0.0.5 (2026-03-31)

### Neue Features
- **BirdNET V3.0 ONNX** — Native Klassifikation ohne Python-Installation, wählbar im Analyse-Tab
- **Referenzbibliothek** — Ordnerbasierte Referenzverwaltung (kein SQLite mehr), Xeno-Canto Downloads mit Spektrogramm-PNGs
- **Download-Skip** — Bereits heruntergeladene Referenzen werden übersprungen (4 Fälle: Datei×CSV korrekt behandelt)
- **Rescan-Button** — Referenzen neu scannen ohne Neustart
- **Modell-Dialog** — Modelle verwalten, hinzufügen, entfernen, Typ-Erkennung (BirdNET V3, V2, Custom)
- **Qualitätsfilter** — Referenzen nach Qualitätsstufe A–E filtern (Download + Anzeige getrennt)
- **Audio On-Demand** — Referenz-Aufnahmen (MP3) direkt abspielen, Download bei Bedarf
- **Artensets / Regionfilter** — Artenlisten nach Region einschränken (Schweiz, Mitteleuropa, Alle)
- **Audio Batch-Download** — Alle Audio-Referenzen für ein Artenset herunterladen
- **Species Master Table** — Zentrale Artentabelle (179 Vögel + Platzhalter für Fledermäuse, Amphibien, Heuschrecken), taxon-übergreifend, mit IUCN-Status-Feld und mehrsprachigen Namen (DE/EN/FR)

### Verbesserungen
- **Signal Chain** in professioneller Reihenfolge neu geordnet
- **Settings-Dialog** öffnet sofort (async laden)
- **Limiter** erhält Spektrogramm-Dynamik korrekt (Hard-Clip statt Zero)
- **ONNX Runtime** 1.17.0 → 1.19.0 (IR v10 Support)
- **Player** funktioniert auf allen Windows-Systemen
- **AuditLog** bläht Projektdateien nicht mehr auf
- **Filter-Einstellungen** bleiben beim Speichern/Laden erhalten

### Refactoring (intern)
- Package-Rename: `com.birdsono` → `ch.etasystems.amsel`
- CompareViewModel: 2889 → 957 LOC (−67 %), aufgeteilt in 8 Manager
- SonogramCanvas aufgeteilt in OverviewStrip, ZoomedCanvas, SpectrogramRenderer
- CompareScreen aufgeteilt in ImportDialog, DragDropHandler
- UnifiedSettingsDialog aufgeteilt in 4 Tab-Dateien
- Debug-Code entfernt, SLF4J-Logging eingeführt
- Zirkuläre Abhängigkeit data↔ui.compare aufgelöst
- SQLite/Exposed-Dependencies komplett entfernt
- Deduplizierung: FrequencyFormat, ImageLoader, SpeciesTranslations

### Behobene Fehler
- Player-Abstürze auf bestimmten Windows-Systemen
- FilterConfig ging beim Laden/Speichern verloren
- AuditLog-Einträge wuchsen endlos
- Limiter unterdrückte leise Signale im Spektrogramm
- Download-Duplikate (XC-IDs statt sequentieller IDs)

### Technische Details
- Kotlin 2.1.0, Compose Desktop 1.7.3, Material 3
- ONNX Runtime 1.19.0
- ~21k LOC, ~70+ Kotlin-Files
- Keine externen Datenbanken (rein dateibasiert)

---

## v0.0.4 (2026-03-26)

### BirdNET Integration
- BirdNET V2.4 (6000+ Arten) via Python-Bridge integriert
- Full-File-Scan: Ganzes Audio analysieren mit einem Klick
- Python-Daemon fuer schnelle Folge-Analysen (~2s statt ~15s)
- Standort-basierter Artfilter (konfigurierbar in Einstellungen)
- Konfidenz-Schwelle einstellbar (0.01-0.5)

### Vergleichsfunktion
- Automatische Referenz-Sonogramm-Suche aus Offline-DB (18500+ Bilder)
- Sonogramm-Import-Dialog: Lokale Datei ODER Art-Suche in XC-Datenbank
- Suche nach deutschem, englischem oder wissenschaftlichem Namen
- Embedding-Vektorsuche (MFCC 43-dim Pseudo-Embeddings)
- 4 Vergleichs-Algorithmen: MFCC, Fingerprint, BirdNET, Embedding

### UI-Verbesserungen
- Draggable Splitter (Seitenleiste + Galerie frei skalierbar)
- Annotations nach Arten gruppiert (ausklappbar, sortiert nach Konfidenz)
- Manuelle Markierungen vs BirdNET-Detektionen getrennt gruppiert
- Doppelklick auf Event = Label editieren
- Unified Settings Dialog (4 Tabs: Allgemein/Analyse/Export/Datenbank)
- Statusmeldungen in Seitenleiste (nicht mehr oben)
- Cursor-Info: Hz, kHz, musikalische Note + Cents bei Hover
- Millisekunden-Praezision bei Zeitstempeln
- Log/Linear Frequenzachse umschaltbar
- Full View Modus (Frequenzachse ganzes Fenster)
- Praezise Navigation: Start/Ende separat verschiebbar

### Audio & Playback
- Grosse Dateien (>60s): PCM-Cache fuer schnellen Random Access
- Playback spielt gesamtes Ansichtsfenster (nicht nur Chunk)
- Gefiltertes Audio abspielen (Noise Gate, SpectralGating etc.)
- Auto-Follow: Viewport folgt der Abspielposition
- Datei schliessen Funktion

### Filter & Bearbeitung
- Neue Signal-Chain: Bandpass > SpectralGating > NoiseFilter > Gate > Limiter > Median > Normalisierung
- SpectralGating: Frequenzband-individuell, Auto-Rauschprofil
- Normalisierung auf -6 dBFS
- Bypass per Doppelklick auf Tab
- Tabs werden gruen bei aktiver Bearbeitung
- Median-Filter 1x1 bis 10x10
- Limiter invertierte Skala (0dB = offen)

### Export
- Glutz-Massstab: 5.5 cm/sec, 2 kHz/2cm, 600 DPI
- Export-Einstellungen konfigurierbar (Frequenz, Zeit, Zeilenlaenge)
- Funktioniert jetzt auch bei grossen Dateien (on-demand Decode)
- S/W Palette fuer Druck
- Filter korrekt gerendert (Noise Gate = weisser Hintergrund)
- 2048 Mel-Bins fuer hohe Aufloesung
- Audit-Trail in PNG-Metadaten

### Wissenschaftliche Features
- Cursor zeigt Hz + kHz + musikalische Note + Cents
- Audit-Trail (Filter-Protokoll, BirdNET-Scans, Exporte)
- Export-Metadaten: Bearbeiter, Geraet, Standort, Koordinaten
- Wissenschaftliche Namen ein-/ausblendbar
- Sprache umschaltbar (DE/EN/Wissenschaftlich)

### Performance
- Debounce 500ms (weniger UI-Jank bei Slider)
- Freq-Zoom ohne Bitmap-Neuaufbau
- Compare-Filter nur wenn aktiv
- Event-Detection O(n) statt O(n log n)

### Artenliste
- 170+ mitteleuropaeische Arten mit Kategorien
- Sortierbar (alphabetisch, nach Familie)
- Download nur Sonogramme (kein Audio)
- Audio on-demand bei Bedarf

## v0.0.3 (2026-03-25)
- Drag & Drop Audio-Import
- Event-Detection (Track-basiert mit Kategorien)
- Gummiband-Editing fuer Annotationen
- Timeline-Navigation mit Pfeilen
- BDA als DOCX

## v0.0.2 (2026-03-23)
- Noise-Filter (0.5 dB Schritte)
- Export im Glutz-Stil (600 DPI, konfigurierbare Achsen)
- M4A/AAC Import
- Normalisierung -6 dBFS
- Farbpaletten (Magma, Viridis, Inferno, Grau, S/W)
- Filter-Presets

## v0.0.1
- Initiale Version
- Audio laden (WAV, MP3, FLAC)
- Mel-Spektrogramm Anzeige
- Xeno-Canto API Anbindung
- Filter-Pipeline
- Event-Detection (Energie-basiert)
