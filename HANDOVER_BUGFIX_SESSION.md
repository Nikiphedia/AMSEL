# Handover: Bugfix-Session Scroll, Post-Filter, Namen, korrupte PNGs, Kandidatenliste

**Datum:** 2026-03-31
**AMSEL Version:** 0.0.5 (pre-Beta)
**Projektpfad:** `D:\80002\AMSEL`

---

## Zusammenfassung

5 Bugs behoben, 7 Dateien geaendert, 1 neue Datei erstellt.

| Bug | Prioritaet | Status | Aufwand |
|-----|-----------|--------|---------|
| Bug 1: Kein Scroll im Arten-Panel | KRITISCH | Fertig | ~5 min |
| Bug 5: Alternativ-Kandidaten pro Chunk | HOCH | Fertig | ~40 min |
| Bug 2: Post-Filter auf BirdNET-Ergebnisse | HOCH | Fertig | ~10 min |
| Bug 3: Gemischte Sprachen in Artennamen | MITTEL | Fertig | ~10 min |
| Bug 4: Korrupte Referenz-PNGs | NIEDRIG | Fertig | ~10 min |

---

## Aenderungen im Detail

### Bug 1: Scroll im linken Arten-Panel

**Problem:** Artenliste (69 Arten) abgeschnitten, kein Scroll moeglich.

**Ursache:** Die Sidebar-Column in `CompareScreen.kt` hatte kein `.fillMaxHeight()`, wodurch die LazyColumn in `AnnotationPanel.kt` unbounded Height bekam und alle Items ohne Scrolling renderte.

**Geaenderte Dateien:**
- `CompareScreen.kt` (Zeile ~306): `.fillMaxHeight()` zur Sidebar-Column hinzugefuegt
- `AnnotationPanel.kt` (Zeile ~111): `Modifier.fillMaxSize()` → `Modifier.fillMaxWidth().weight(1f)` auf der LazyColumn

**Risiko:** Minimal. Nur Modifier-Aenderungen, keine Logik-Aenderung.

---

### Bug 5: Alternativ-Kandidaten pro Chunk (Neues Feature)

**Problem:** Pro Chunk wurde nur der beste BirdNET-Treffer angezeigt. BirdNET liefert aber Top-N Scores pro Chunk.

**Loesung:**
1. Neue Datenklasse `SpeciesCandidate` in `Annotation.kt` (species, scientificName, confidence)
2. `candidates: List<SpeciesCandidate>` Feld auf `Annotation` hinzugefuegt
3. In `ClassificationManager.scanBirdNetInternal()`: Per-Chunk Top-10 Kandidaten werden gruppiert und auf jeder Annotation gespeichert
4. `adoptCandidate()` Methode in ClassificationManager und CompareViewModel: Aendert Annotation-Label und loest Referenz-Suche aus
5. Neues Composable `CandidatePanel.kt`: Zeigt Kandidaten mit Konfidenz-Farben, Artname (deutsch via SpeciesRegistry), "Uebernehmen" per Klick
6. CandidatePanel in CompareScreen zwischen AnnotationPanel und ChunkSelector eingebunden

**Geaenderte Dateien:**
- `core/annotation/Annotation.kt` — `SpeciesCandidate` data class + `candidates` Feld
- `ui/compare/ClassificationManager.kt` — Chunk-Grouping + `adoptCandidate()`
- `ui/compare/CompareViewModel.kt` — `adoptCandidate()` Delegation
- `ui/compare/CompareScreen.kt` — CandidatePanel eingebunden
- **NEU:** `ui/annotation/CandidatePanel.kt` — UI-Composable fuer Kandidatenliste

**Risiko:** Mittel. Annotation-Datenklasse erweitert (backward-compatible dank default `emptyList()`). Bestehende Projekte (.amsel.json) laden weiterhin korrekt dank `ignoreUnknownKeys`.

**Offene Punkte:**
- Kandidaten enthalten nur Arten oberhalb `birdnetMinConf` (default 10%). Fuer noch feinere Vorschlaege koennte man einen separaten, niedrigeren Schwellwert (z.B. 5%) fuer Kandidaten einfuehren.
- Kandidatenliste zeigt ALLE Top-N Arten, auch ausserhalb des aktiven Artensets (bewusst — Irrgaeste sollen sichtbar bleiben)

---

### Bug 2: Post-Filter auf BirdNET-Artenliste

**Problem:** Artenset-Filter (z.B. "Schweizer Brutvoegel") wurde nur auf Referenz-Sonogramme angewendet, nicht auf die BirdNET-Ergebnisliste im linken Panel.

**Loesung:** In `ClassificationManager.scanBirdNetInternal()`, nach Dedup und vor Annotation-Erstellung, werden BirdNET-Ergebnisse gegen `RegionSetRegistry.isSpeciesInSet()` gefiltert.

**Geaenderte Datei:**
- `ui/compare/ClassificationManager.kt` — Post-Filter Block nach `allResults`, vor `createAnnotationsFromResults()`

**Verhalten:**
- `activeRegionSet == "all"` → kein Filter (alle BirdNET-Ergebnisse)
- Anderes Set → nur Arten im Set werden als Annotationen erstellt
- Kandidatenliste (Bug 5) zeigt weiterhin ALLE Arten inkl. ausserhalb des Sets
- Meldung wenn alle Ergebnisse weggefiltert wurden

**Risiko:** Gering. Reiner Filter, keine Seiteneffekte auf BirdNET-Inference.

---

### Bug 3: Gemischte Sprachen in Artennamen

**Problem:** Artenliste zeigte Mix aus deutsch und englisch. `SpeciesTranslations` kennt nur ~150 Arten, Rest bleibt englisch.

**Loesung:** In `AnnotationPanel.GroupHeader()` wird jetzt zuerst `SpeciesRegistry.getDisplayName()` (192 Taxa) abgefragt. Nur wenn kein Ergebnis → Fallback auf `SpeciesTranslations.translate()`.

**Geaenderte Datei:**
- `ui/annotation/AnnotationPanel.kt` — `GroupHeader()` Composable: SpeciesRegistry-First-Lookup mit Fallback

**Verhalten:**
- SpeciesRegistry liefert deutschen Namen → wird angezeigt
- SpeciesRegistry hat nur sci. Namen (= nicht in Master Table) → Fallback auf SpeciesTranslations
- Beides hat nichts → englischer BirdNET-Name als Fallback

**Risiko:** Minimal. Nur Anzeige-Logik, keine Daten-Aenderung.

**Offener Punkt:** Fuer volle Abdeckung aller ~6500 BirdNET-Arten muesste `species_master.json` erweitert werden (geplant fuer nach Beta).

---

### Bug 4: Korrupte Referenz-PNGs

**Problem:** Thumbnails zeigten "korrupt" Text statt Sonogramm-Bild.

**Loesung:**
1. **Neue Validierung** in `ImageLoader.kt`: `isValidImageFile()` prueft PNG/JPEG Magic Bytes und Mindestgroesse (100 Bytes)
2. **Vorab-Pruefung** in `readImageRobust()`: Vor dem Laden wird `isValidImageFile()` aufgerufen — korrupte Dateien werden frueh abgefangen
3. **UI-Fix** in `SonogramGallery.kt`: "korrupt"-Text ersetzt durch dezenten Platzhalter (graues Image-Icon + "n/a")

**Geaenderte Dateien:**
- `ui/results/ImageLoader.kt` — `isValidImageFile()` Methode + Vorab-Check in `readImageRobust()`
- `ui/results/SonogramGallery.kt` — Korrupt-Platzhalter: Icon statt Text

**Risiko:** Minimal. Nur zusaetzliche Validierung, kein bestehendes Verhalten geaendert.

---

## Alle geaenderten Dateien (komplett)

```
GEAENDERT:
  src/main/kotlin/ch/etasystems/amsel/core/annotation/Annotation.kt
  src/main/kotlin/ch/etasystems/amsel/ui/annotation/AnnotationPanel.kt
  src/main/kotlin/ch/etasystems/amsel/ui/compare/ClassificationManager.kt
  src/main/kotlin/ch/etasystems/amsel/ui/compare/CompareScreen.kt
  src/main/kotlin/ch/etasystems/amsel/ui/compare/CompareViewModel.kt
  src/main/kotlin/ch/etasystems/amsel/ui/results/ImageLoader.kt
  src/main/kotlin/ch/etasystems/amsel/ui/results/SonogramGallery.kt

NEU:
  src/main/kotlin/ch/etasystems/amsel/ui/annotation/CandidatePanel.kt
```

## Nicht geaendert (wie angefordert)

- BirdNET ONNX Inference (kein Pre-Filter)
- `species_master.json`
- `referenzen.csv` Format
- Download-Logik
- Bestehende Scroll-Bereiche (Spektrogramm, Referenz-Galerie)

---

## Testplan

### Bug 1: Scroll
- [ ] Artenliste links: Alle Arten sichtbar mit Scroll
- [ ] Artenliste scrollt fluessig (LazyColumn)
- [ ] Chunk-Bereich unten bleibt sichtbar waehrend Artenliste scrollt
- [ ] Sidebar Splitter (Drag-Resize) funktioniert weiterhin

### Bug 5: Kandidatenliste
- [ ] Chunk/Annotation anklicken → Kandidatenliste erscheint zwischen Artenliste und Chunks
- [ ] Kandidaten nach Konfidenz sortiert (hoechste zuerst)
- [ ] Aktuelle Art ist markiert (Haekchen)
- [ ] "Uebernehmen" (Klick auf andere Art) setzt neues Label
- [ ] Nach Uebernehmen: Referenz-Galerie wechselt auf neue Art
- [ ] Artennamen in Kandidatenliste auf Deutsch (wo verfuegbar)
- [ ] Wenn keine Annotation selektiert → kein CandidatePanel sichtbar

### Bug 2: Post-Filter
- [ ] Artenset "Schweizer Brutvoegel": Keine australischen/amerikanischen Arten in Hauptliste
- [ ] Artenset "Alle": Alle BirdNET-Ergebnisse sichtbar
- [ ] Kandidatenliste zeigt auch Arten ausserhalb des Artensets
- [ ] Meldung wenn alle Ergebnisse weggefiltert

### Bug 3: Namen
- [ ] Artennamen konsistent deutsch (wo verfuegbar)
- [ ] Arten ohne deutschen Namen: Englischer Name als Fallback
- [ ] Wissenschaftlicher Name in Klammern (wenn Setting aktiv)

### Bug 4: Korrupte PNGs
- [ ] Korrupte PNGs: Kein "korrupt"-Text, stattdessen dezenter Platzhalter (Icon + "n/a")
- [ ] Zu kleine Dateien (< 100 Bytes) werden nicht geladen
- [ ] Referenz-Galerie funktioniert weiterhin mit horizontalem Scroll

---

## Naechste Schritte (nicht in dieser Session)

1. **species_master.json erweitern** auf alle ~6500 BirdNET-Arten (vollstaendige deutsche Uebersetzung)
2. **Kandidaten-Schwellwert** konfigurierbar machen (aktuell = birdnetMinConf)
3. **Korrupte PNGs aufraumen**: Beim Rescan automatisch loeschen (< 100 Bytes oder ungueltige Magic Bytes)
4. **Beta-Release** vorbereiten
