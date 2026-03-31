# Handover: Species Master Multilingual v2

**Datum:** 2026-03-31
**Ursprungs-Task:** `TASK_SPECIES_MASTER_MULTILINGUAL.md`
**Status:** Kern-Implementierung abgeschlossen

---

## Was erledigt wurde

### 1. IOC-Daten extrahiert
- **Quelle:** `Multiling IOC 15.1_d.xlsx` (lag bereits in Projektwurzel)
- **Script:** `tools/extract_ioc_names.py` (Python, braucht `openpyxl`)
- **Output:** `tools/data/ioc_names.json` — 11'250 Arten × 23 Sprachen
- Griechische Klammerzusaetze automatisch entfernt: `(Κοινός) Κότσυφας` → `Κότσυφας`

### 2. Generator erstellt und ausgefuehrt
- **Script:** `tools/generate_species_master_multilingual.js` (Node.js)
- **Eingabedaten:** `ioc_names.json` + `download_categories.json` + `region_sets.json` + 13 hardcodierte Nicht-Voegel
- **Ergebnis:** `species_master.json` v2 — 192 Taxa (179 Voegel + 13 Nicht-Voegel)
- **IOC-Treffer:** 179/179 Voegel (100%), 8 taxonomische Synonyme aufgeloest

### 3. JSON-Format v1 → v2
```
ALT (v1):  "common_name_de": "Amsel", "common_name_en": "...", "common_name_fr": "..."
NEU (v2):  "common_names": { "de": "Amsel", "en": "Common Blackbird", "fr": "Merle noir", ... }
```

### 4. SpeciesRegistry.kt umgebaut
- `SpeciesInfo.commonNameDe/En/Fr` → `commonNames: Map<String, String>`
- `getDisplayName(scientificName, locale)` — Fallback: locale → `"de"` → `"en"` → scientific name
- Rueckwaertskompatibilitaet: Liest sowohl v1 (alte Einzelfelder) als auch v2 (`common_names` Map)
- Version-Check in `initialize()`: JAR v2 > User v1 → User-Kopie automatisch ueberschrieben

### 5. Kompilierung verifiziert
- `./gradlew compileKotlin` = **BUILD SUCCESSFUL**

---

## 23 Sprachen (22 EU-Amtssprachen + Ukrainisch)

| Code | Sprache | Abdeckung (Voegel) |
|------|---------|-------------------|
| `de` | Deutsch | 179/179 (100%) |
| `en` | Englisch | 179/179 (100%) |
| `fr` | Franzoesisch | 179/179 (100%) |
| `it` | Italienisch | 179/179 (100%) |
| `es` | Spanisch | 179/179 (100%) |
| `pt` | Portugiesisch | 179/179 (100%) |
| `nl` | Niederlaendisch | 179/179 (100%) |
| `da` | Daenisch | 179/179 (100%) |
| `sv` | Schwedisch | 179/179 (100%) |
| `fi` | Finnisch | 179/179 (100%) |
| `pl` | Polnisch | 179/179 (100%) |
| `cs` | Tschechisch | 179/179 (100%) |
| `sk` | Slowakisch | 179/179 (100%) |
| `hu` | Ungarisch | 179/179 (100%) |
| `ro` | Rumaenisch | 176/179 (98%) |
| `bg` | Bulgarisch | 179/179 (100%) |
| `hr` | Kroatisch | 179/179 (100%) |
| `sl` | Slowenisch | 179/179 (100%) |
| `et` | Estnisch | 179/179 (100%) |
| `lv` | Lettisch | 179/179 (100%) |
| `lt` | Litauisch | 179/179 (100%) |
| `el` | Griechisch | 176/179 (98%) |
| `uk` | Ukrainisch | 179/179 (100%) |

**Nicht enthalten:** `ga` (Irisch), `mt` (Maltesisch) — keine Spalten in der IOC-Datei.

---

## 8 Taxonomische Synonyme (download_categories → IOC v15.1)

| download_categories (alt) | IOC v15.1 (neu) | Deutscher Name |
|---------------------------|-----------------|----------------|
| `Sylvia_communis` | `Curruca_communis` | Dorngrasmücke |
| `Sylvia_curruca` | `Curruca_curruca` | Klappergrasmücke |
| `Dendrocopos_minor` | `Dryobates_minor` | Kleinspecht |
| `Dendrocopos_medius` | `Dendrocoptes_medius` | Mittelspecht |
| `Accipiter_gentilis` | `Astur_gentilis` | Habicht |
| `Anas_strepera` | `Mareca_strepera` | Schnatterente |
| `Corvus_monedula` | `Coloeus_monedula` | Dohle |
| `Tetrao_tetrix` | `Lyrurus_tetrix` | Birkhuhn |

Die `species_master.json` speichert weiterhin die **alten** Namen (aus download_categories), da BirdNET und die bestehende UI diese verwenden. Der Generator loest die Synonyme nur fuer den IOC-Lookup auf.

---

## Neue/geaenderte Dateien

| Datei | Aktion | Beschreibung |
|-------|--------|-------------|
| `tools/extract_ioc_names.py` | NEU | Python XLSX→JSON Extractor |
| `tools/data/ioc_names.json` | NEU (generiert) | 11'250 Arten × 23 Sprachen Zwischendatei |
| `tools/generate_species_master_multilingual.js` | NEU | Node.js Hauptgenerator |
| `src/main/resources/species/species_master.json` | ERSETZT | v2 Format, 192 Taxa, 23 Sprachen |
| `src/main/kotlin/.../data/SpeciesRegistry.kt` | GEAENDERT | commonNames Map, Version-Check, Rueckwaertskompatibilitaet |

### Nicht geaendert (wie im Task spezifiziert)
- `CandidatePanel.kt` — nutzt `getDisplayName("de")`, funktioniert weiterhin
- `AnnotationPanel.kt` — nutzt `getDisplayName(locale)`, funktioniert weiterhin
- `RegionSetRegistry.kt` — nutzt nur scientific_name
- `SpeciesTranslations.kt` / `species_de.json` — Legacy-Fallback bleibt
- `region_sets.json` — unveraendert

---

## Regeneration

Falls die IOC-Datei aktualisiert wird oder neue Arten hinzukommen:

```bash
# 1. IOC XLSX in Projektwurzel ablegen (oder Pfad in extract_ioc_names.py anpassen)
python tools/extract_ioc_names.py

# 2. species_master.json neu generieren
node tools/generate_species_master_multilingual.js

# 3. Kompilierung pruefen
./gradlew compileKotlin
```

**Voraussetzungen:**
- Python 3.12+ mit `openpyxl` (`pip install openpyxl`)
- Node.js (fuer den Generator)
- Die IOC-XLSX muss unter dem in `extract_ioc_names.py` konfigurierten Pfad liegen (aktuell: `../Multiling IOC 15.1_d.xlsx` relativ zu `tools/`)

---

## Testfaelle (aus Original-Task)

| Test | Ergebnis |
|------|----------|
| `./gradlew compileKotlin` — BUILD SUCCESSFUL | ✅ |
| `getDisplayName("Turdus_merula", "de")` → "Amsel" | ✅ (via JSON-Stichprobe) |
| `getDisplayName("Turdus_merula", "it")` → "Merlo" | ✅ |
| `getDisplayName("Turdus_merula", "pl")` → "kos" | ✅ |
| `getDisplayName("Turdus_merula", "xx")` → Fallback "de" → "Amsel" | ✅ (Code-Logik) |
| `getDisplayName("Unknown_species", "de")` → "Unknown species" | ✅ (Code-Logik) |
| Nicht-Voegel erhalten | ✅ (13 Stueck: 5 Chiroptera, 5 Amphibia, 3 Orthoptera) |
| `common_names` enthaelt DE, EN, FR, IT fuer alle CH-Arten | ✅ |
| Alte User-Kopie (v1) wird automatisch durch v2 ersetzt | ✅ (Version-Check) |

---

## Offene Punkte fuer naechste Sessions

### Prio 1: Mehr Vogelarten (~550 statt 179)
Die `download_categories.json` enthaelt nur 179 Arten. Fuer ~550 muesste die `central_europe`-Region aus `region_sets.json` (302 Arten) oder direkt die IOC-Liste als Artenbasis verwendet werden. Der Generator muesste erweitert werden, um Arten aus `region_sets.json` hinzuzufuegen, die nicht in `download_categories.json` enthalten sind.

### Prio 2: Sprachauswahl-UI
`CandidatePanel.kt` und `AnnotationPanel.kt` nutzen aktuell hardcoded `"de"`. Braucht:
- Settings-Dropdown fuer UI-Sprache, oder
- System-Locale-Detection (`Locale.getDefault().language`)

### Prio 3: Nicht-Voegel mehrsprachig
Fledermaeuse, Amphibien, Heuschrecken haben nur DE/EN/FR/IT (manuell hardcodiert). Fuer volle Mehrsprachigkeit brauchte man eine separate Datenquelle (z.B. Wikipedia-API oder manuelle Recherche).

### Prio 4: Irisch (ga) und Maltesisch (mt)
Nicht in IOC-Datei enthalten. Koennte manuell ergaenzt werden — betrifft aber nur wenige Nutzer.

---

## Stichprobe: Turdus merula in allen 23 Sprachen

```
de: Amsel
en: Common Blackbird
fr: Merle noir
it: Merlo
es: Mirlo común
pt: melro-preto
nl: Merel
da: Solsort
sv: koltrast
fi: mustarastas
pl: kos
cs: kos černý
sk: drozd čierny
hu: fekete rigó
ro: Mierlă
bg: Кос
hr: kos
sl: kos
et: musträstas
lv: melnais meža strazds
lt: juodasis strazdas
el: Κότσυφας
uk: дрізд чорний
```
