# Handover: Task Download-Skip

**Datum:** 2026-03-31
**Status:** Implementiert, kompiliert (BUILD SUCCESSFUL), nicht manuell getestet
**Betroffene Datei:** `src/main/kotlin/ch/etasystems/amsel/data/reference/ReferenceDownloader.kt`

## Was wurde gemacht

Beim Bulk-Download von Xeno-Canto-Referenzen werden bereits vorhandene Dateien jetzt uebersprungen, statt sie erneut herunterzuladen.

### Neue Methode: `buildCsvIndex()`
- Liest beim Start des Download-Vorgangs **einmalig** alle IDs aus `referenzen.csv` in ein `MutableSet<String>`
- Header-Spalte wird flexibel gesucht (case-insensitive), BOM wird entfernt
- Kein wiederholtes CSV-Parsen pro Datei

### Skip-Logik (nach Qualitaetsfilter, vor HTTP-Request)

| Datei vorhanden (>0 Bytes) | CSV-Eintrag | Aktion |
|---|---|---|
| Ja | Ja | **Skip** — kein Download, kein CSV-Append |
| Ja | Nein | **Nur CSV ergaenzen** — kein HTTP-Request |
| Nein | Ja | **Nur Datei herunterladen** — `skipCsvAppend=true` |
| Nein | Nein | **Normal downloaden** + CSV schreiben (bisheriges Verhalten) |

### Aenderungen an `downloadReference()`
- Neuer Parameter `skipCsvAppend: Boolean = false`
- Alter interner Duplikat-Check (`pngFile.exists()` mit `return false`) entfernt — Logik liegt jetzt im Aufrufer
- Alte `library.getRecordingsForSpecies()`-Pruefung in `startDownload()` entfernt (war pro Datei, langsam)

### Logging
- `logger.debug("Skip: XC{id}_{Art} bereits vorhanden")` fuer uebersprungene Dateien
- `logger.debug("CSV-Eintrag ergaenzen fuer: ...")` bei CSV-Reparatur
- `logger.info("Download fertig: X neu, Y uebersprungen, Z fehlgeschlagen, N CSV-Eintraege ergaenzt")` am Ende

### DownloadProgress
- Neues Feld `totalSkipped: Int = 0` — wird laufend aktualisiert
- UI (DownloadDialog/TabDatenbank) zeigt das Feld noch nicht explizit an, `phase`-String enthaelt die Zahlen aber bereits

## Was NICHT geaendert wurde
- `referenzen.csv` Format (Semikolon-Delimiter, Spaltenreihenfolge)
- `ReferenceLibrary.kt`
- `meetsQuality()` Companion-Funktion
- Download-Logik fuer neue Dateien (HTTP, PNG-Speicherung, Rate-Limit)
- `downloadAudioOnDemand()` (on-demand Audio ist unabhaengig)

## Offene Testfaelle

- [ ] Erster Download einer Art: Alle Dateien werden normal heruntergeladen
- [ ] Zweiter Download derselben Art: Alle Dateien werden uebersprungen
- [ ] Datei manuell geloescht, CSV-Eintrag noch da: Datei wird neu heruntergeladen
- [ ] CSV-Eintrag fehlt, Datei noch da: Nur CSV-Eintrag wird ergaenzt
- [ ] Leere Datei (0 Bytes) auf Platte: Wird als fehlend behandelt, neu heruntergeladen
- [ ] Fortschrittsanzeige zeigt korrekte Zahlen (inkl. uebersprungene)
- [ ] Log-Zusammenfassung am Ende stimmt

## Risiken / Hinweise
- `csvIndex` ist ein `MutableSet` das waehrend des Downloads mutiert wird (bei CSV-Reparatur und Neu-Downloads). Das ist thread-safe weil alles im selben Coroutine-Scope laeuft.
- Falls `referenzen.csv` XC-IDs ohne "XC"-Praefix enthaelt, greift der Index-Lookup nicht. Aktuell schreibt `appendCsvEntry()` immer mit "XC"-Praefix (`xcId = "XC${recording.id}"`), daher konsistent.
- UI-Felder (`DownloadDialog.kt`, `TabDatenbank.kt`) koennten `totalSkipped` kuenftig separat anzeigen — aktuell nur im `phase`-String sichtbar.
