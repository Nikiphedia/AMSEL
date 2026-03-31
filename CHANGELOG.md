# Changelog

Alle relevanten Änderungen an AMSEL werden in dieser Datei dokumentiert.

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
