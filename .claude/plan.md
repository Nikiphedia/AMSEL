# Plan: Settings-Dialog, M4A-Import, Median-Filter, Export-Fix

## 1. Settings-Dialog (Frequenz-Export-Einstellungen)

### Neue Felder in `AppSettings` (Settings.kt):
- `exportFreqMin: Int = 0` — Untere Grenzfrequenz (Hz), default 0
- `exportFreqMax: Int = 16000` — Obere Grenzfrequenz (Hz), default 16000
- `exportFreqStepHz: Int = 2000` — Schrittweite Achse (Hz), default 2kHz (= 1cm)

### Neuer Dialog `ExportSettingsDialog.kt` in `ui/settings/`:
- Drei Eingabefelder (OutlinedTextField mit Suffix "Hz"):
  - Untere Grenzfrequenz: 0–50000 Hz (Slider + Textfeld)
  - Obere Grenzfrequenz: 1000–125000 Hz (für Fledermäuse/Insekten)
  - Schrittweite: 500–10000 Hz
- Presets-Buttons: "Vögel (0–16 kHz)", "Fledermäuse (15–125 kHz)", "Insekten (0–50 kHz)"
- OK/Abbrechen
- Speichert via `SettingsStore.save()`

### Integration:
- `CompareViewModel`: Liest Settings beim Export, übergibt an ImageExporter
- `ImageExporter`: Nutzt `exportFreqMin/Max/StepHz` statt Hardcoded-Werte
- `CompareScreen`: Settings-Button in Toolbar öffnet Dialog

## 2. M4A/AAC Audio-Import

### Dependency (libs.versions.toml + build.gradle.kts):
- `com.googlecode.soundlibs:jaacodec:0.1.1` oder alternativ FFmpeg-Wrapper
- Einfachste Lösung: `net.sourceforge.jaad:jaad:0.8.6` (Pure-Java AAC Decoder)

### AudioDecoder.kt:
- `.m4a` und `.aac` Erweiterungen in FileChooser-Filter hinzufügen
- JAAD registriert sich als SPI-Provider → javax.sound.sampled erkennt M4A automatisch
- Fallback: Manueller JAAD-Decoder falls SPI nicht greift

### CompareScreen.kt:
- FileChooser-Filter erweitern: `"wav", "mp3", "flac", "ogg", "m4a", "aac"`
- Drag&Drop-Filter erweitern

## 3. Median-Filter 1x1 bis 10x10

### MedianFilter.kt:
- Keine Änderung nötig (akzeptiert beliebigen `kernelSize`)

### FilterConfig (FilterPipeline.kt):
- `medianKernelSize: Int = 3` — bleibt, Wertebereich 1–10

### FilterPanel.kt:
- Median Slider: Range von 1 bis 10 (statt 3–21 odd-only)
- Step = 1 (statt 2)
- Odd-Enforcement ENTFERNEN — 1x1 bis 10x10 in ganzen Zahlen
- Label: "1×1" bis "10×10"

## 4. Farbiger Export: Hintergrund weiss

### ImageExporter.kt:
- Bei Farb-Export (blackAndWhite=false): Niedrigste Werte (norm ≈ 0) → weiss statt Colormap-Farbe
- Colormap-Mapping invertieren: `Colormap.mapValueRgb(norm)` für norm=0 soll weiss geben
- Oder: Background auf weiss setzen und Colormap nur ab norm > threshold verwenden

## Dateien die geändert werden:
1. `src/main/kotlin/com/birdsono/data/Settings.kt` — 3 neue Felder
2. `src/main/kotlin/com/birdsono/ui/settings/ExportSettingsDialog.kt` — NEU
3. `src/main/kotlin/com/birdsono/core/export/ImageExporter.kt` — Settings nutzen + Farb-BG
4. `src/main/kotlin/com/birdsono/ui/compare/CompareViewModel.kt` — Settings an Export übergeben
5. `src/main/kotlin/com/birdsono/ui/compare/CompareScreen.kt` — Dialog + M4A-Filter
6. `src/main/kotlin/com/birdsono/ui/sonogram/FilterPanel.kt` — Median 1–10
7. `gradle/libs.versions.toml` — JAAD dependency
8. `build.gradle.kts` — JAAD dependency
