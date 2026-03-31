# Handover: Pipeline Species Data (BirdNET x IOC)

**Datum:** 2026-03-31
**Script:** `tools/generate_all_species_data.mjs`
**Ausfuehren:** `node tools/generate_all_species_data.mjs`

---

## BirdNET Labels

| Metrik | Wert |
|--------|------|
| Total Arten | 11'560 |
| Davon Aves | 9'834 |
| Insecta | 696 |
| Amphibia | 647 |
| Mammalia | 350 |
| Andere (Reptilia, Fische, Pflanzen etc.) | 33 |

---

## IOC Matching

| Methode | Anzahl | Anteil |
|---------|--------|--------|
| Direkt gematcht | 9'613 | 83.2% |
| Synonym | 0 | 0% |
| Fuzzy (Levenshtein=1) | 2 | <0.1% |
| **Nicht gematcht** | **1'945** | 16.8% |
| **Match-Rate** | **9'615 / 11'560** | **83.2%** |

**Warum 0 Synonyme?** BirdNET V3.0 und IOC v15.1 verwenden die gleiche moderne Taxonomie. Die vorbereiteten 33 Synonyme (Curruca→Sylvia, Astur→Accipiter, etc.) werden nicht benoetigt fuer BirdNET→IOC Matching, sind aber weiterhin im Script fuer die Region-Set-Validierung (IOC→BirdNET Richtung) aktiv.

**Fuzzy-Matches (2 korrekte Schreibvarianten):**
- `Poospizopsis_hypochondria` → `Poospizopsis_hypocondria` (dist=1)
- `Sylviorthorhynchus_desmursii` → `Sylviorthorhynchus_desmurii` (dist=1)

---

## Region-Sets

| Set | Arten | Status |
|-----|-------|--------|
| all | 0 (kein Filter) | unveraendert |
| ch_breeding | 180 | validiert, unveraendert |
| ch_all | 237 | validiert, unveraendert |
| central_europe | 302 | validiert, unveraendert |

**Hinweis:** Die IOC Multilingual XLSX enthaelt keine Range/Distribution-Spalte. central_europe konnte daher nicht automatisch erweitert werden. Fuer eine Erweiterung auf 500-700 Arten waere die IOC Master List (nicht Multilingual) mit Range-Daten noetig.

### Validierung
- ch_breeding subset ch_all: OK
- ch_all subset central_europe: OK
- Alle Set-Arten in species_master: OK
- Alle CH-Arten haben DE-Namen: OK

---

## Species Master

| Metrik | Wert |
|--------|------|
| Total Taxa | 11'565 |
| Voegel (aves) | 9'834 |
| Nicht-Voegel (manuell) | 13 (5 Chiroptera, 5 Amphibia, 3 Orthoptera) |
| BirdNET Nicht-Voegel | 1'718 (Insecta, Amphibia, Mammalia, etc.) |
| Mit 23 IOC-Sprachen | 9'615 |
| Nur EN (Fallback) | 219 (nicht in IOC + 1'718 Nicht-Voegel ohne IOC) |
| Version | 2 |
| Dateigroesse | 9.5 MB |

### Sprach-Abdeckung (Top 10)

| Sprache | Abdeckung |
|---------|-----------|
| en | 11'565 (100%) |
| fr | 9'628 (83.3%) |
| pt | 9'615 (83.1%) |
| nl | 9'615 (83.1%) |
| sv | 9'615 (83.1%) |
| sk | 9'615 (83.1%) |
| da | 9'587 (82.9%) |
| pl | 9'583 (82.9%) |
| de | 9'524 (82.4%) |
| es | 9'503 (82.2%) |

---

## Dateigroessen

| Datei | Groesse |
|-------|---------|
| `src/main/resources/species/region_sets.json` | 20.9 KB |
| `src/main/resources/species/species_master.json` | 9.5 MB |
| `tools/data/ioc_names.json` (Input, unveraendert) | 7.9 MB |
| `tools/generate_all_species_data.mjs` (Script) | ~10 KB |

---

## Nicht gematchte Arten (Top 20 Voegel)

Diese BirdNET-Vogelarten haben keinen IOC-Eintrag (split/lump Aenderungen, regional endemisch, oder Unterarten):

1. `Acanthis_cabaret` — Lesser Redpoll (oft Unterart von A. flammea)
2. `Acanthis_hornemanni` — Arctic Redpoll (teils in A. flammea gemergt)
3. `Accipiter_toussenelii` — Red-chested Goshawk
4. `Acrocephalus_baeticatus` — African Reed Warbler
5. `Aegithalos_sharpei` — Burmese Bushtit
6. `Aerodramus_germani` — German's Swiftlet
7. `Aethopyga_jefferyi` — Luzon Sunbird
8. `Alethe_castanea` — Fire-crested Alethe
9. `Alethe_montana` — Usambara Alethe
10. `Amaurocichla_bocagii` — Sao Tome Short-tail
11. `Aplonis_dichroa` — San Cristobal Starling
12. `Arizelocichla_montana` — Cameroon Greenbul
13. `Artamus_monachus` — Ivory-backed Woodswallow
14. `Bathmocercus_cerviniventris` — Black-capped Rufous Warbler
15. `Bradornis_mariquensis` — Marico Flycatcher
16. `Cacomantis_aeruginosus` — Moluccan Cuckoo
17. `Campephaga_petiti` — Petit's Cuckooshrike
18. `Chloris_sinica` — Grey-capped Greenfinch
19. `Cinnyris_nectarinioides` — Black-bellied Sunbird
20. `Crithagra_mozambica` — Yellow-fronted Canary

---

## Offene Punkte

1. **Dateigroesse 9.5 MB** — Groesser als die angepeilten 2-5 MB, aber bei 11'565 Taxa x 23 Sprachen realistisch. Koennte durch Weglassen leerer Sprachfelder optimiert werden.
2. **central_europe Erweiterung** — Braucht IOC Master List mit Range-Daten (nicht im Projekt vorhanden).
3. **Build-Test** — `./gradlew compileKotlin` und App-Start sollten geprueft werden, um sicherzustellen dass SpeciesRegistry die groessere Datei korrekt laedt.
4. **1'945 ungematchte Arten** — Grossteils Nicht-Voegel (Frosche, Insekten, Saeugetiere) die erwartungsgemaess nicht in der IOC-Vogelliste stehen. ~200 sind Voegel mit taxonomischen Abweichungen (Splits, Merges, regionale Endemiten).
