# Handover — Task 25: Species Master Table

**Datum:** 2026-03-31
**Status:** Abgeschlossen, `compileKotlin` = BUILD SUCCESSFUL

---

## Was wurde gemacht

### Neue Dateien

| Datei | Zeilen | Beschreibung |
|-------|--------|-------------|
| `src/main/resources/species/species_master.json` | ~2800 | 192 Taxa (179 Voegel, 5 Fledermaeuse, 5 Amphibien, 3 Heuschrecken) |
| `src/main/kotlin/ch/etasystems/amsel/data/SpeciesRegistry.kt` | 251 | Singleton Registry mit allen Query-Methoden |
| `tools/generate_species_master.js` | 130 | Node.js-Generator-Script (merged alle 3 Quellen) |

### Geaenderte Dateien

| Datei | Aenderung |
|-------|-----------|
| `Main.kt` | `SpeciesRegistry.initialize(amselDataDir)` beim App-Start hinzugefuegt |

### Nicht geaendert (wie spezifiziert)

- `species_de.json` — bleibt als Fallback
- `download_categories.json` — bleibt fuer DownloadDialog
- `region_sets.json` — bleibt fuer RegionSetRegistry
- `ClassificationManager.kt` — keine Aenderung
- `AnnotationPanel.kt` — keine Aenderung

---

## species_master.json — Inhalt

```
Version:     1
Generated:   2026-03-31
Sources:     download_categories, species_de, region_sets, manual
Total Taxa:  192
```

| Taxon-Gruppe | Anzahl | classifier_support |
|-------------|--------|-------------------|
| aves | 179 | birdnet_v3, birdnet_v2 |
| chiroptera | 5 | (leer — Platzhalter) |
| amphibia | 5 | (leer — Platzhalter) |
| orthoptera | 3 | (leer — Platzhalter) |

**Datenquellen-Merge:**
- Wissenschaftliche Namen + Deutsche Namen + Familie: aus `download_categories.json` (179 Voegel)
- Englische Namen: 140 aus Reverse-Lookup `species_de.json`, 39 manuell ergaenzt (AviList-konform)
- Region-Tags: aus `region_sets.json` (ch_breeding, ch_all, central_europe — nur Beispielarten)
- Nicht-Voegel: 13 Arten manuell mit DE/EN/FR Namen und IUCN-Status

**Felder pro Taxon:**
`scientific_name`, `taxon_group`, `order`, `family`, `common_name_de`, `common_name_en`, `common_name_fr`, `iucn_status`, `region_tags`, `classifier_support`

---

## SpeciesRegistry.kt — API

```kotlin
object SpeciesRegistry {
    fun initialize(amselDataDir: File)                        // App-Start: JAR→User-Kopie + Laden
    fun getSpecies(scientificName: String): SpeciesInfo?      // Einzelart-Lookup
    fun getAllSpecies(): List<SpeciesInfo>                     // Alle Taxa
    fun getByTaxonGroup(group: String): List<SpeciesInfo>     // z.B. "aves", "chiroptera"
    fun getByRegionTag(tag: String): List<SpeciesInfo>        // z.B. "CH_breeding"
    fun getByClassifier(classifierId: String): List<SpeciesInfo>  // z.B. "birdnet_v3"
    fun getSupportedSpeciesForModel(modelId: String): Set<String> // Set<scientific_name>
    fun getDisplayName(scientificName: String, locale: String = "de"): String  // Fallback-Kette
    fun getIucnStatus(scientificName: String): String?        // LC/NT/VU/EN/CR/EW/EX/DD/NE
    fun reload()                                               // Fuer zukuenftiges Update-Feature
}
```

### Design-Entscheidungen

- **Folgt RegionSetRegistry-Pattern:** Kotlin `object`, `kotlinx.serialization`, SLF4J, graceful Fallback
- **JAR→User-Kopie:** `~/Documents/AMSEL/species/species_master.json` wird beim ersten Start aus JAR kopiert
- **Doppelter Fallback:** User-Datei fehlt → JAR-Ressource direkt laden → leere Map
- **Unterstrich-Konvention:** `scientific_name` mit Unterstrich (`Turdus_merula`), konsistent mit Dateisystem (`curated/Turdus_merula/`)
- **getDisplayName() Fallback-Kette:** de → common_name_de > common_name_en > scientific (mit Leerzeichen)
- **Kein Breaking Change:** Bestehender Code (SpeciesTranslations, RegionSetRegistry) funktioniert weiter

---

## Laufzeitverhalten

```
~/Documents/AMSEL/
├── models/
├── references/
├── cache/
├── settings.json
└── species/                    ← NEU
    └── species_master.json     ← Kopiert aus JAR beim ersten Start
```

1. **Erster Start:** `species_master.json` wird von JAR nach `~/Documents/AMSEL/species/` kopiert
2. **Folgestarts:** Bestehende User-Kopie wird gelesen (nicht ueberschrieben)
3. **User-Kopie geloescht:** Wird beim naechsten Start aus JAR neu erstellt
4. **JAR-Ressource fehlt:** Registry bleibt leer, App laeuft weiter (graceful degradation)

---

## Offene Punkte / Naechste Schritte

| Punkt | Prioritaet | Beschreibung |
|-------|-----------|-------------|
| Franzoesische Namen | Niedrig | `common_name_fr` ist bei Voegeln leer — IOC Multilingual XLSX importieren |
| IUCN-Status Voegel | Mittel | `iucn_status` bei Voegeln leer — IUCN API v4 anbinden oder AviList-CSV importieren |
| Region-Tags erweitern | Mittel | Nur Beispielarten haben Tags — Vogelwarte-Listen importieren |
| SpeciesTranslations Migration | Niedrig | `SpeciesTranslations.translate()` langfristig durch `SpeciesRegistry.getDisplayName()` ersetzen |
| Download-Integration | Niedrig | `DownloadDialog` kann optional `SpeciesRegistry.getByTaxonGroup("aves")` nutzen statt `download_categories.json` |
| BatDetect2 | Spaeter | Bei Integration: `classifier_support: ["batdetect2"]` fuer Fledermaeuse setzen |
| Update-Mechanismus | Spaeter | Button "Taxonomie aktualisieren" in Settings — `reload()` ist vorbereitet |

---

## Verifikation

- [x] `./gradlew compileKotlin` — BUILD SUCCESSFUL
- [ ] App starten → `~/Documents/AMSEL/species/species_master.json` wird erstellt
- [ ] Zweiter Start → bestehende Datei nicht ueberschrieben
- [ ] `SpeciesRegistry.getSpecies("Turdus_merula")` → SpeciesInfo mit Amsel/Eurasian Blackbird
- [ ] `getDisplayName("Turdus_merula", "de")` → "Amsel"
- [ ] `getDisplayName("Turdus_merula", "en")` → "Eurasian Blackbird"
- [ ] `getDisplayName("Unknown_species", "de")` → "Unknown species"
- [ ] `getByTaxonGroup("aves")` → 179 Eintraege
- [ ] `getByTaxonGroup("chiroptera")` → 5 Eintraege
- [ ] `getByClassifier("birdnet_v3")` → 179 Eintraege
- [ ] Bestehende UI zeigt Artennamen weiterhin korrekt an (kein Breaking Change)
