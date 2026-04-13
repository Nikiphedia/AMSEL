# AMSEL — Entwicklungsrichtlinien

## Projekt
**AMSEL** (Another Mel Spectrogram Event Locator) — Desktop-App zur Sonogramm-Analyse und Artenerkennung.
Kotlin 2.1.0 + Compose Desktop 1.7.3 + Material3 Dark Theme.

## Build
```bash
set "JAVA_HOME=C:\Program Files\Microsoft\jdk-21.0.10.7-hotspot"
cd D:\80002\AMSEL
.\gradlew.bat compileKotlin   # Typcheck
.\gradlew.bat run              # Starten
.\gradlew.bat packageMsi       # Installer
```

## Codestil
- **Sprache im Code:** Deutsch (Kommentare, Variablen, UI-Texte). Englisch nur wenn Library-API es vorgibt.
- **Kein `any`** ohne erklaerenden Kommentar
- **Vollstaendige Dateien** ausgeben — kein "rest bleibt gleich"
- **Keine typografischen Anfuehrungszeichen** in Kotlin-Strings (nur ASCII `"` und `'`)
- **Kein Refactoring** ueber den Task-Scope hinaus
- **Keine neuen Dependencies** ohne explizite Freigabe
- **Locale.US** fuer alle Float-Formatierungen in Export-Dateien (CSV, PDF)

## Architektur
- `Main.kt` → Setup-Check → `CompareScreen` (Haupt-UI)
- **MVVM:** `CompareViewModel` delegiert an 8 Manager (Audio, Annotation, Classification, Export, Playback, Project, Spectrogram, Volume)
- **State:** `MutableStateFlow` in Managern, `combine()` in ViewModel, `collectAsState()` in UI
- **Serialisierung:** `kotlinx.serialization` mit `ignoreUnknownKeys = true` (Backward-Compat)
- **Settings:** `~/Documents/AMSEL/settings.json` via `SettingsStore.load()/save()`
- **Referenzen:** `~/Documents/AMSEL/references/` (ordnerbasiert, `referenzen.csv` + auto-generierter Index)
- **Modelle:** Pfad aus `Settings.resolvedModelDir()` (konfigurierbar)

## Wichtige Packages
```
core/audio/          AudioDecoder, AudioPlayer, AudioSegment, FilteredAudio, SliceManager
core/classifier/     BirdNetBridge, OnnxBirdNetV3, OnnxClassifier
core/export/         AudioExporter, SpeciesCsvExporter, ReportExporter, ImageExporter
core/filter/         FilterPipeline (7 Stufen), FilterConfig
core/spectrogram/    MelSpectrogram, ChunkedSpectrogram, SpectrogramData
data/                Settings, ModelRegistry, ModelDownloader, SpeciesRegistry
data/reference/      ReferenceLibrary, ReferenceDownloader
ui/compare/          CompareScreen, CompareViewModel, 8 Manager
ui/annotation/       CandidatePanel, AnnotationPanel, SliceSelector, AudiofilesPanel
ui/results/          ResultsPanel, SonogramGallery, ResultCard
ui/layout/           UndockablePanel, UndockPanelState
ui/settings/         SetupDialog, UnifiedSettingsDialog, ModelManagerDialog, NewProjectDialog, AudioMetadataDialog
ui/sonogram/         ZoomedCanvas, OverviewStrip, FilterPanel, SonogramToolbar
```

## Datenmodell
- `Annotation` — Zeitbereich + Label + Kandidaten + Notes + Verified/Rejected (computed aus Kandidaten)
- `SpeciesCandidate` — Art + Konfidenz + verified/rejected/verifiedBy/verifiedAt (pro Kandidat)
- `MatchResult` — Referenz-Sonogramm Treffer (XC-ID, Similarity, Quality)
- `AppSettings` — 30+ Felder inkl. Ordner-Pfade, Fenster-State, Analyse-Parameter

## Arbeitsregeln fuer Worker-Agents
- Lies die zugewiesenen Dateien KOMPLETT bevor du aenderst
- Handover-File ist Pflicht (`HANDOVER_APxx.md`)
- `.\gradlew.bat compileKotlin` muss BUILD SUCCESSFUL sein
- Nur Dateien aendern die im Task stehen
- Keine Architekturentscheidungen — bei Unklarheit: Stopp und melde an Master

## Bekannte Einschraenkungen
- BirdNET V3 ONNX Download-URL noch Platzhalter (Modell muss manuell kopiert werden)
- FLAC/M4A/MP3 Export braucht ffmpeg im PATH
- Selbst-signiertes Code-Signing-Zertifikat (Windows SmartScreen warnt)
- PDF-Floats nutzen noch System-Locale (minor)
