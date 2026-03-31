# Handover: TASK_REGION_SETS_FILL

**Datum:** 2026-03-31
**Status:** ERLEDIGT — region_sets.json befuellt, Build gruen

---

## Was gemacht wurde

**`src/main/resources/species/region_sets.json`** komplett neu generiert mit validierten Artenlisten:

| Set | Arten | Beschreibung |
|-----|-------|-------------|
| `all` | 0 (leer) | Keine Einschraenkung |
| `ch_breeding` | **180** | Schweizer Brutvoegel |
| `ch_all` | **237** | CH komplett (Brut + Durchzuegler + Winter) |
| `central_europe` | **302** | Mitteleuropa (DE/AT/FR/IT/Benelux) |

## Wie

- **Generator-Script:** `tools/generate_region_sets.mjs` — parst `~/Documents/AMSEL/models/birdnet_v3_labels.csv` (11'560 Arten), validiert jede Art gegen BirdNET-Labels
- **30 taxonomische Synonyme** aufgeloest (BirdNET nutzt neuere Taxonomie):
  - `Accipiter_gentilis` → `Astur_gentilis`
  - `Sylvia_communis/curruca/etc.` → `Curruca_*`
  - `Tetrao_tetrix` → `Lyrurus_tetrix`
  - `Anas_querquedula` → `Spatula_querquedula`
  - `Dendrocopos_medius` → `Dendrocoptes_medius`
  - `Dendrocopos_minor` → `Dryobates_minor`
  - `Corvus_monedula` → `Coloeus_monedula`
  - `Bonasa_bonasia` → `Tetrastes_bonasia`
  - `Apus_melba` → `Tachymarptis_melba`
  - `Egretta_alba` → `Ardea_alba`
  - `Ixobrychus_minutus` → `Botaurus_minutus`
  - `Anas_penelope` → `Mareca_penelope`
  - `Bubulcus_ibis` → `Ardea_ibis`
  - `Charadrius_morinellus` → `Eudromias_morinellus`
  - `Larus_ridibundus` → `Chroicocephalus_ridibundus`
  - `Phalacrocorax_aristotelis` → `Gulosus_aristotelis`
  - `Philomachus_pugnax` → `Calidris_pugnax`
  - `Anas_clypeata` → `Spatula_clypeata`
  - `Aquila_pomarina` → `Clanga_pomarina`
  - `Charadrius_alexandrinus` → `Anarhynchus_alexandrinus`
  - `Oceanodroma_leucorhoa` → `Hydrobates_leucorhous`
  - `Phalacrocorax_pygmeus` → `Microcarbo_pygmaeus`
  - `Sterna_albifrons` → `Sternula_albifrons`
  - `Sterna_caspia` → `Hydroprogne_caspia`
  - `Sterna_sandvicensis` → `Thalasseus_sandvicensis`
  - `Sylvia_cantillans` → `Curruca_cantillans`
  - `Sylvia_conspicillata` → `Curruca_conspicillata`
  - `Sylvia_hortensis` → `Curruca_hortensis`
  - `Sylvia_melanocephala` → `Curruca_melanocephala`
  - `Sylvia_nisoria` → `Curruca_nisoria`
  - `Sylvia_sarda` → `Curruca_sarda`
  - `Sylvia_undata` → `Curruca_undata`
- **0 nicht-gefundene Arten** — alles matched
- Subset-Beziehungen verifiziert: `ch_breeding ⊂ ch_all ⊂ central_europe`
- `./gradlew compileKotlin` → BUILD SUCCESSFUL

## Geaenderte Dateien

| Datei | Aktion |
|-------|--------|
| `src/main/resources/species/region_sets.json` | ERSETZT (vorher Platzhalter, jetzt 180/237/302 Arten) |
| `tools/generate_region_sets.mjs` | AKTUALISIERT (Generator mit Synonym-Mapping) |

## Was NICHT gemacht wurde / offen

1. **Manueller App-Test** — App starten, Artenset "Schweizer Brutvoegel" waehlen, BirdNET-Analyse ausfuehren, pruefen ob ~180 statt 7 Arten angezeigt werden
2. **RegionSetRegistry.kt** — nicht angefasst (liest nur JSON, keine Code-Aenderung noetig)
3. **species_master.json** — nicht geaendert (separater Task)

## Reproduzierbar

```bash
cd D:\80002\AMSEL
node tools/generate_region_sets.mjs src/main/resources/species
```
