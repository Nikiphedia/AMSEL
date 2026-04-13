# Changelog

Alle relevanten Änderungen an AMSEL werden in dieser Datei dokumentiert.

## [0.0.7] — in Arbeit

### Neue Features
- **Loop-Wiedergabe** — Loop-Button in Toolbar (Autorenew-Icon, gruen wenn aktiv), loopt den aktuellen Viewport
- **Seek mit S+Pfeiltasten** — S gehalten + Links/Rechts springt 5 Sekunden im Playback
- **Space = Play/Pause** — Leertaste startet/pausiert Audio (mit Textfeld-Guard fuer Label-Editor)
- **Multi-Audio-Projekte** — Ein Projekt kann mehrere Audio-Dateien enthalten
- **Audiofiles-Panel** — Sidebar-Panel listet alle geladenen Audio-Dateien, wechselbar per Klick
- **Undockbare Panels** — Kandidaten-Panel und Audiofiles-Panel als eigene Fenster abdockbar (+/X Buttons)
- **Projekt-Ordner** — Projekte mit eigenem Ordner (audio/, export/), Dialog "Neues Projekt" und "Projekt oeffnen" in Toolbar
- **Audio-Metadaten** — Datum, Uhrzeit und GPS (Pirol) pro Audio-Datei zuweisbar
- **Drag & Drop Multi-File** — Mehrere Audio-Dateien gleichzeitig per Drag & Drop importieren
- **Report-Sortierung** — CSV/PDF Reports sortierbar nach Zeit, Alphabet oder Systematik (Einstellungen)
- **Multi-File Export** — CSV/PDF Reports aggregieren Annotations ueber alle Audio-Dateien mit Datei-Spalte

- **Referenz-Priorisierung** — Referenzen mit Audio+Bild zuoberst, blaue Hervorhebung fuer lokal verfuegbare Audio-Referenzen
- **CapsLock-Referenz-Engine** — CapsLock wechselt zwischen Haupt- und Referenz-Audio, Tastatur-Routing (Space, Pfeiltasten, Loop), farbiger Rahmen um Gallery
- **Referenz-Playback-Pointer** — Bewegender Playback-Strich in der aktiv spielenden Referenz-Kachel
- **Space+Klick** — Leertaste gehalten + Mausklick im Sonogramm startet Wiedergabe ab Klickposition
- **Artennamen in Benutzersprache** — Alle Anzeigen (Gallery, ResultCard, AnnotationPanel) respektieren die Spracheinstellung
- **Solo-Modus** — Chunk in voller Breite mit konfigurierbarem Vor-/Nachlauf, Tab/R+Tab Navigation, Artenliste filtert auf sichtbaren Viewport
- **Kandidaten erweitert** — "?" (unklar) Status, mehrere Kandidaten gleichzeitig verifizierbar, "Art hinzufuegen" Button fuer manuelle Arterfassung
- **CandidatePanel bei manuellen Markierungen** — Panel erscheint auch ohne BirdNET-Kandidaten, manuelle Arten hinzufuegbar
- **BirdNET Viewport-First Scan** — Sichtbarer Bereich wird zuerst gescannt, UI sofort freigegeben, Rest im Hintergrund mit Live-Zaehler
- **Ueberlappungs-Indikator** — Warnsymbol bei zeitlich ueberlappenden Chunks in der Artenliste
- **Tastenkombinationen-Uebersicht** — TASTENKOMBINATIONEN.md mit allen Shortcuts

### Verbesserungen
- Modellauswahl vereinfacht: RadioButton-Liste entfernt, nur Preset-Kacheln + "Modelle verwalten" Dialog (Kachel-Layout)
- Markierungen-Zaehler aus Toolbar ins AudiofilesPanel verschoben
- Solo-Modus Vor-/Nachlauf separat konfigurierbar (unabhaengig von Event-Klick)

### Behobene Fehler
- AudioPlayer Race-Condition bei schnellem Play-Wechsel (playGeneration Counter)
- Space aktivierte fokussierte Toolbar-Buttons statt Play/Pause (onPreviewKeyEvent Fix)
- CapsLock-Erkennung robust gemacht (Key-Toggle + getLockingKeyState Fallback)
- CandidatePanel pointerInput-Bug (veraltete Closures bei State-Aenderung)

### Refactoring
- Chunks → Slices umbenannt (UI + Code)
- Datenmodell v2: Multi-Audio (`audioFiles` statt `audio`), `audioFileId` in Annotations
- Projekt-Migration v1 → v2 mit automatischem Backup
- PlaybackMode Enum (MAIN/REFERENCE) fuer expliziten Modus-Wechsel
- BirdNET Scan-Logik: Helper-Methoden extrahiert (applyRegionFilter, buildCandidateMap)

## [0.0.6] — 2026-04-08

### Neue Features
- **Setup-Assistent** — Erster-Start-Dialog mit 4 konfigurierbaren Ordnern (Audio-Import, Projekte, Exporte, Modelle)
- **Modell-Download** — ONNX-Modelle direkt aus der App herunterladen (ModelDownloader + ModelManagerDialog)
- **Chunk-Verifizierung** — BirdNET-Ergebnisse bestaetigen/ablehnen mit Gruppen-Zaehler und Status-Icons
- **Export-Warnung** — Warndialog bei unverifizierten Chunks vor PDF/CSV-Export; abgelehnte Chunks werden ausgefiltert
- **Multi-Format Audio-Export** — WAV nativ, FLAC/M4A/MP3 via ffmpeg (neuer AudioExporter)
- **Arten-CSV Export** — BirdNET-Ergebnisse als Semikolon-CSV mit Zeitstempel und Konfidenz
- **Globale Tastenkuerzel** — 15 Shortcuts: Space (Play/Pause), Escape (Stop), Pfeiltasten (Navigation), +/- (Zoom), Del (Annotation loeschen), F2 (Umbenennen), F5 (BirdNET Scan)
- **Kontextmenues** — Annotations-Kontextmenue erweitert, neues Canvas-Kontextmenue (Annotation erstellen, Zoom, Playback), Ergebnispanel-Kontextmenue (Sonogramm zeigen, Art uebernehmen, XC-Suche)
- **Multi-Select Annotationen** — Checkbox-Auswahl, Alle auswaehlen (Ctrl+A), Bulk-Loeschen, Bulk-Report
- **PDF-Report Export** — A4-Layout mit Header, Zusammenfassung, Detektionstabelle mit Seitenumbruch (PDFBox 3.0.4)
- **CSV-Report Export** — Semikolon-getrennt, UTF-8 mit BOM, 10 Spalten (Nr, Art, Wissenschaftlich, Start, Ende, Dauer, Freq, Konfidenz, Quelle)
- **ReferenceEditor Audio-Export** — Markierten Bereich als WAV/FLAC/M4A exportieren, optional mit Filter
- **ReferenceEditor BirdNET** — Artenerkennung direkt im Referenz-Editor mit Ergebnisliste und CSV-Export
- **M4A/M4P Import** — iTunes-geschuetzte und ungeschuetzte M4A-Dateien in allen Dialogen

### Verbesserungen
- **Konfigurierbare Ordner** — Audio-Import, Projekte, Exporte, Modelle einzeln waehlbar (Settings + Setup)
- **Modell-Pfade dynamisch** — 6 Core-Dateien lesen Modell-Ordner aus Settings statt hardcodiert ~/Documents/AMSEL/models/
- **Scrollbare Referenz-Vorschlaege** — Artgruppen mit sichtbarem vertikalem Scrollbalken, Varianten horizontal als LazyRow
- **CandidatePanel** — Bestaetigen/Ablehnen/Reaktivieren Buttons mit Farbcodierung
- **AnnotationPanel** — Verifiziert-Icon (gruenes Haekchen), Abgelehnt (ausgegraut alpha 0.35), Gruppen-Zaehler "3/5"
- **Bemerkungsfeld** — Freitext-Notiz pro Annotation (z.B. bei Fehlbestimmung), gespeichert bei Focus-Verlust
- **Doppelklick-Edit** — Doppelklick auf Kandidat oeffnet Inline-Editor zum manuellen Aendern der Art
- **Unabhaengiges Verify/Reject** — Bestaetigung sperrt nicht Ablehnen/Aendern, beide Aktionen immer verfuegbar
- **SonogramGallery Grid** — Adaptives Grid (LazyVerticalGrid) statt horizontaler Reihe, vertikaler Scrollbalken
- **Artensuche mit Autovervollstaendigung** — Doppelklick auf Kandidat oeffnet Textfeld mit Dropdown-Vorschlaegen (SpeciesRegistry.searchSpecies)
- **Fenster-Settings persistent** — Position, Groesse, Sidebar-Breite, Gallery-Hoehe werden bei Beenden gespeichert und beim Start wiederhergestellt
- **Gallery-Splitter repariert** — Inline-Drag statt defektem HorizontalSplitter, Hoehe 80-500dp einstellbar
- **Settings UI Redesign** — Neue SectionCard/ExpandableSection Komponenten, Tabs Allgemein/Analyse/Export/Datenbank komplett ueberarbeitet mit Preset-Karten (Voegel/Fledermaeuse)
- **Stille-Erkennung** — RMS-basiert (-50 dBFS), stille Chunks werden bei ONNX-Inferenz uebersprungen
- **FFT-Parallelisierung** — MelSpectrogram.compute() als suspend, parallele Batch-Verarbeitung (ein Batch pro CPU-Kern), ~2x Speedup auf Dual-Core
- **Filter-Pipeline Fusion** — In-place Operationen fuer Volume Fader, Normalize, Limiter (7 Array-Kopien → 1)
- **Buffer Pooling** — splitIntoChunkRanges() statt splitIntoChunks(), bedingte clone() nur bei aktiven Filtern, Bulk-ByteBuffer-Read in PcmCacheFile
- **Benchmark-Infrastruktur** — PerformanceLog mit measure()/summary(), ClassifyStats fuer Chunk-Statistik (total/skipped/classified)
- **ExportManager refaktoriert** — Delegiert an AudioExporter, unterstuetzt 4 Formate statt 2

### Behobene Fehler
- Export-Dialog triggerte PNG-Export bei unbekanntem Format (jetzt korrekt geroutet)
- Referenz-Sonogramme erschienen nicht nach BirdNET Full-Scan (fehlender searchSimilar-Aufruf)
- Notes-Feld feuerte State-Update bei jedem Tastendruck (jetzt nur bei Focus-Verlust)
- JOptionPane Export-Warnung blockierte falschen Thread (jetzt auf AWT EDT)
- ModelDownloader folgte keinen Cross-Domain Redirects (manuelles Redirect-Handling)
- Download-URL zeigte auf falsches Modell (TFLite statt ONNX, jetzt Platzhalter)
- CompareScreen wurde waehrend SetupDialog im Hintergrund initialisiert (jetzt im else-Block)
- isEditingLabel/editText State wurde bei Annotations-Wechsel nicht zurueckgesetzt (remember-Key)
- WAV-Writer Integer-Overflow bei >2GB Audio (Long-Guard mit Exception)
- CSV-Export nutzte System-Locale statt Punkt fuer Floats (Locale.US)
- ReportExporter Parameter-Shadowing entfernt (filteredConfig)
- isExporting in ReferenceEditor wurde bei Fehler nicht zurueckgesetzt (finally-Block)
- Export-Ordner Fallback nutzte nicht den konfigurierten Settings-Pfad
- Label-Parsing Bug: Artnamen ohne Underscore wurden nicht erkannt → keine Referenz-Sonogramme
- Post-Scan searchSimilar Timing-Bug: StateFlow noch nicht propagiert wenn Referenz-Suche startet
- Gallery-Splitter Drag funktionierte nicht (Pointer-Capture-Bug in HorizontalSplitter)
- Fensterposition/-groesse ging bei Neustart verloren
- Sidebar/Gallery-Groesse wurde bei Drag nicht persistent gespeichert

### Abhaengigkeiten
- NEU: Apache PDFBox 3.0.4 (PDF-Report)
- ffmpeg im PATH fuer FLAC/M4A/MP3 Export (optional, WAV funktioniert immer)

## [0.0.5] — 2026-03-31

### Neue Features
- **BirdNET V3.0 ONNX** — Native Klassifikation ohne Python, wählbar im Analyse-Tab
- **Referenzbibliothek** — Ordnerbasierte Referenzverwaltung mit Xeno-Canto Downloads
- **Species Master Table** — 11'565 Taxa mit Namen in 23 Sprachen
- **Artensets / Regionfilter** — Artenlisten nach Region einschränken (Schweiz, Mitteleuropa, Alle)
- **Kandidatenliste** — Top 5-10 Alternativvorschläge pro Chunk, manuell übernehmbar
- **Audio On-Demand** — Referenz-Aufnahmen (MP3) direkt abspielen, Download bei Bedarf
- **Audio Batch-Download** — Alle Audio-Referenzen für ein Artenset herunterladen
- **Qualitätsfilter** — Referenzen nach Qualitätsstufe A-E filtern (Download + Anzeige getrennt)
- **Modell-Dialog** — Modelle verwalten, hinzufügen, entfernen, Typ-Erkennung
- **Rescan-Button** — Referenzen neu scannen ohne Neustart
- **Download-Skip** — Bereits heruntergeladene Referenzen werden übersprungen

### Verbesserungen
- Signal Chain in professioneller Reihenfolge
- Einstellungen öffnen sofort (async)
- Limiter erhält Spektrogramm-Dynamik korrekt
- ONNX Runtime 1.19.0 (IR v10 Support)
- Player funktioniert auf allen Windows-Systemen
- Artennamen konsequent auf Deutsch (SpeciesRegistry)
- Korrupte Referenz-PNGs werden erkannt und dezent angezeigt
- Download-Dialog zeigt Arten aus SpeciesRegistry (nicht mehr statisch 179)

### Refactoring
- Package-Rename: com.birdsono → ch.etasystems.amsel
- CompareViewModel: 2889 → 957 LOC (-67%), 8 Manager
- SQLite/Exposed komplett entfernt (rein dateibasiert)
- SLF4J-Logging statt Debug-Code
- ~21k LOC, ~75 Kotlin-Files

### Behobene Fehler
- Player-Abstürze auf bestimmten Windows-Systemen
- FilterConfig ging beim Laden/Speichern verloren
- AuditLog wuchs endlos
- Download-Duplikate (XC-IDs statt sequentieller IDs)
- Scroll in Artenliste fehlte
- Gemischte Sprachen in Artennamen

## [0.0.4] — 2025-xx-xx

Erste interne Version.
