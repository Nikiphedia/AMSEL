#!/usr/bin/env node
/**
 * Pipeline: BirdNET V3.0 Labels × IOC Multilingual → region_sets.json + species_master.json
 *
 * Input:
 *   ~/Documents/AMSEL/models/birdnet_v3_labels.csv  (BirdNET V3.0, ~11k Arten)
 *   tools/data/ioc_names.json                        (IOC Multilingual, 11'250 Arten × 23 Sprachen)
 *   src/main/resources/species/region_sets.json       (bestehende CH/CE Sets)
 *
 * Output:
 *   src/main/resources/species/region_sets.json       (re-validiert)
 *   src/main/resources/species/species_master.json    (ALLE BirdNET-Arten, v2)
 *
 * Ausfuehren: node tools/generate_all_species_data.mjs
 */
import { readFileSync, writeFileSync } from 'fs';
import { join, resolve } from 'path';
import { homedir } from 'os';
import { fileURLToPath } from 'url';
import { dirname } from 'path';

const __filename = fileURLToPath(import.meta.url);
const __dirname = dirname(__filename);
const ROOT = resolve(__dirname, '..');

// ============================================================
// 1. BirdNET Labels parsen
// ============================================================
const labelsPath = join(homedir(), 'Documents', 'AMSEL', 'models', 'birdnet_v3_labels.csv');
const rawLabels = readFileSync(labelsPath, 'utf-8');
const labelLines = rawLabels.split('\n').filter(l => l.trim());

// Header: idx;id;sci_name;com_name;class;order
const header = labelLines[0].replace(/^\uFEFF/, '').split(';');
const sciIdx = header.indexOf('sci_name');
const comIdx = header.indexOf('com_name');
const classIdx = header.indexOf('class');
const orderIdx = header.indexOf('order');

// Map: underscore_name -> { en, class, order }
const birdnetMap = new Map();
for (let i = 1; i < labelLines.length; i++) {
  const cols = labelLines[i].split(';');
  const sciRaw = (cols[sciIdx] || '').trim();
  if (!sciRaw) continue;
  const key = sciRaw.replace(/ /g, '_');
  birdnetMap.set(key, {
    en: (cols[comIdx] || '').trim(),
    class: (cols[classIdx] || '').trim(),
    order: (cols[orderIdx] || '').trim(),
  });
}
console.log(`\n=== BirdNET Labels ===`);
console.log(`Total: ${birdnetMap.size} Arten`);

// ============================================================
// 2. IOC-Daten laden
// ============================================================
const iocNames = JSON.parse(readFileSync(join(__dirname, 'data', 'ioc_names.json'), 'utf8'));
const iocKeys = Object.keys(iocNames);
console.log(`\n=== IOC Daten ===`);
console.log(`Total: ${iocKeys.length} Arten`);

// IOC Genus-Index fuer Fuzzy-Matching
const iocByGenus = new Map();
for (const key of iocKeys) {
  const genus = key.split('_')[0];
  if (!iocByGenus.has(genus)) iocByGenus.set(genus, []);
  iocByGenus.get(genus).push(key);
}

// ============================================================
// 3. Synonym-Map (BirdNET-Name -> IOC-Name)
// ============================================================
const synonymMap = {
  // Accipitridae
  'Astur_gentilis': 'Accipiter_gentilis',
  // Curruca -> Sylvia
  'Curruca_communis': 'Sylvia_communis',
  'Curruca_curruca': 'Sylvia_curruca',
  'Curruca_cantillans': 'Sylvia_cantillans',
  'Curruca_conspicillata': 'Sylvia_conspicillata',
  'Curruca_hortensis': 'Sylvia_hortensis',
  'Curruca_melanocephala': 'Sylvia_melanocephala',
  'Curruca_nisoria': 'Sylvia_nisoria',
  'Curruca_sarda': 'Sylvia_sarda',
  'Curruca_undata': 'Sylvia_undata',
  // Tetraonidae
  'Lyrurus_tetrix': 'Tetrao_tetrix',
  'Tetrastes_bonasia': 'Bonasa_bonasia',
  // Anatidae
  'Spatula_querquedula': 'Anas_querquedula',
  'Spatula_clypeata': 'Anas_clypeata',
  'Mareca_penelope': 'Anas_penelope',
  'Mareca_strepera': 'Anas_strepera',
  // Picidae
  'Dendrocoptes_medius': 'Dendrocopos_medius',
  'Dryobates_minor': 'Dendrocopos_minor',
  // Corvidae
  'Coloeus_monedula': 'Corvus_monedula',
  // Apodidae
  'Tachymarptis_melba': 'Apus_melba',
  // Ardeidae
  'Ardea_alba': 'Egretta_alba',
  'Botaurus_minutus': 'Ixobrychus_minutus',
  'Ardea_ibis': 'Bubulcus_ibis',
  // Charadriidae
  'Eudromias_morinellus': 'Charadrius_morinellus',
  'Anarhynchus_alexandrinus': 'Charadrius_alexandrinus',
  // Laridae / Sternidae
  'Chroicocephalus_ridibundus': 'Larus_ridibundus',
  'Sternula_albifrons': 'Sterna_albifrons',
  'Hydroprogne_caspia': 'Sterna_caspia',
  'Thalasseus_sandvicensis': 'Sterna_sandvicensis',
  // Phalacrocoracidae
  'Gulosus_aristotelis': 'Phalacrocorax_aristotelis',
  'Microcarbo_pygmaeus': 'Phalacrocorax_pygmeus',
  // Scolopacidae
  'Calidris_pugnax': 'Philomachus_pugnax',
  // Accipitridae
  'Clanga_pomarina': 'Aquila_pomarina',
  // Hydrobatidae
  'Hydrobates_leucorhous': 'Oceanodroma_leucorhoa',
};

// Reverse-Map (IOC-Name -> BirdNET-Name) fuer Region-Set-Validation
const reverseSynonymMap = {};
for (const [birdnet, ioc] of Object.entries(synonymMap)) {
  reverseSynonymMap[ioc] = birdnet;
}

// ============================================================
// 4. Levenshtein-Distanz fuer Fuzzy-Matching
// ============================================================
function levenshtein(a, b) {
  const m = a.length, n = b.length;
  const dp = Array.from({ length: m + 1 }, () => new Array(n + 1).fill(0));
  for (let i = 0; i <= m; i++) dp[i][0] = i;
  for (let j = 0; j <= n; j++) dp[0][j] = j;
  for (let i = 1; i <= m; i++) {
    for (let j = 1; j <= n; j++) {
      dp[i][j] = a[i - 1] === b[j - 1]
        ? dp[i - 1][j - 1]
        : 1 + Math.min(dp[i - 1][j], dp[i][j - 1], dp[i - 1][j - 1]);
    }
  }
  return dp[m][n];
}

// ============================================================
// 5. BirdNET × IOC Matching
// ============================================================
const matchDirect = [];
const matchSynonym = [];
const matchFuzzy = [];
const unmatched = [];
const newSynonyms = []; // Vom Fuzzy-Match entdeckte Synonyme

// Ergebnis: birdnetKey -> iocKey (oder null)
const matchResult = new Map();

for (const [bKey, bData] of birdnetMap) {
  // Stufe 1: Direkt
  if (iocNames[bKey]) {
    matchResult.set(bKey, bKey);
    matchDirect.push(bKey);
    continue;
  }

  // Stufe 2: Synonym
  const synKey = synonymMap[bKey];
  if (synKey && iocNames[synKey]) {
    matchResult.set(bKey, synKey);
    matchSynonym.push(bKey);
    continue;
  }

  // Stufe 3: Fuzzy (gleicher Genus, Levenshtein <= 2 auf Epithet)
  const genus = bKey.split('_')[0];
  const epithet = bKey.split('_').slice(1).join('_').toLowerCase();
  const candidates = iocByGenus.get(genus) || [];
  let bestMatch = null;
  let bestDist = 2; // Schwellenwert: nur Levenshtein 1 (< 2)

  for (const cKey of candidates) {
    const cEpithet = cKey.split('_').slice(1).join('_').toLowerCase();
    const dist = levenshtein(epithet, cEpithet);
    if (dist > 0 && dist < bestDist) {
      bestDist = dist;
      bestMatch = cKey;
    }
  }

  if (bestMatch) {
    matchResult.set(bKey, bestMatch);
    matchFuzzy.push({ birdnet: bKey, ioc: bestMatch, dist: bestDist });
    newSynonyms.push({ birdnet: bKey, ioc: bestMatch, dist: bestDist });
    continue;
  }

  // Kein Match
  matchResult.set(bKey, null);
  unmatched.push(bKey);
}

console.log(`\n=== IOC Matching ===`);
console.log(`Gematcht (direkt):     ${matchDirect.length}`);
console.log(`Gematcht (Synonym):    ${matchSynonym.length}`);
console.log(`Gematcht (Fuzzy):      ${matchFuzzy.length}`);
console.log(`Nicht gematcht:        ${unmatched.length}`);
const total = matchDirect.length + matchSynonym.length + matchFuzzy.length;
console.log(`Match-Rate:            ${(total / birdnetMap.size * 100).toFixed(1)}%`);

if (matchFuzzy.length > 0) {
  console.log(`\nFuzzy-Matches (neue Synonyme):`);
  for (const { birdnet, ioc, dist } of matchFuzzy.slice(0, 30)) {
    console.log(`  ${birdnet} -> ${ioc} (dist=${dist})`);
  }
  if (matchFuzzy.length > 30) {
    console.log(`  ... und ${matchFuzzy.length - 30} weitere`);
  }
}

if (unmatched.length > 0) {
  console.log(`\nNicht gematcht (Top 30):`);
  for (const u of unmatched.slice(0, 30)) {
    const en = birdnetMap.get(u)?.en || '?';
    console.log(`  ${u} (${en})`);
  }
  if (unmatched.length > 30) {
    console.log(`  ... und ${unmatched.length - 30} weitere`);
  }
}

// ============================================================
// 6. Region-Sets laden und re-validieren
// ============================================================
const existingRegionSets = JSON.parse(
  readFileSync(join(ROOT, 'src/main/resources/species/region_sets.json'), 'utf8')
);

// Region-Set-Arten gegen BirdNET validieren (mit Synonym-Aufloesung)
// Die bestehenden Sets verwenden IOC-Namen, BirdNET nutzt teils andere
function validateRegionSet(species, label) {
  const validated = [];
  const notFound = [];

  for (const name of species) {
    if (birdnetMap.has(name)) {
      validated.push(name);
    } else if (reverseSynonymMap[name] && birdnetMap.has(reverseSynonymMap[name])) {
      // IOC-Name -> BirdNET-Name umwandeln
      validated.push(reverseSynonymMap[name]);
    } else {
      // Manche Region-Set-Namen sind schon BirdNET-Namen, andere IOC
      // Versuche auch vorwaerts-Synonym
      const fwd = synonymMap[name];
      if (fwd && birdnetMap.has(name)) {
        validated.push(name);
      } else {
        notFound.push(name);
      }
    }
  }

  if (notFound.length > 0) {
    console.log(`  ${label}: ${notFound.length} nicht in BirdNET: ${notFound.join(', ')}`);
  }

  return [...new Set(validated)].sort();
}

console.log(`\n=== Region-Sets Validierung ===`);
const regionSetsOut = {
  version: 2,
  sets: []
};

for (const set of existingRegionSets.sets) {
  if (set.id === 'all') {
    regionSetsOut.sets.push({
      id: 'all',
      name_de: set.name_de,
      name_en: set.name_en,
      description_de: set.description_de,
      species: []
    });
    continue;
  }

  const validated = validateRegionSet(set.species, set.id);
  regionSetsOut.sets.push({
    id: set.id,
    name_de: set.name_de,
    name_en: set.name_en,
    description_de: `${set.description_de.replace(/\(\d+ Arten\)/, `(${validated.length} Arten)`)}`,
    species: validated
  });
  console.log(`  ${set.id}: ${set.species.length} -> ${validated.length} validiert`);
}

// Region-Tag-Lookup: birdnet_key -> [tags]
const regionTagMap = new Map();
for (const set of regionSetsOut.sets) {
  if (set.species.length === 0) continue;
  const tag = set.id === 'ch_breeding' ? 'CH_breeding'
    : set.id === 'ch_all' ? 'CH_all'
    : set.id;
  for (const sp of set.species) {
    if (!regionTagMap.has(sp)) regionTagMap.set(sp, []);
    regionTagMap.get(sp).push(tag);
  }
}

// ============================================================
// 7. Species Master generieren
// ============================================================
const ALL_LANGS = [
  'de', 'en', 'fr', 'it', 'es', 'pt', 'nl',
  'da', 'sv', 'fi', 'pl', 'cs', 'sk', 'hu',
  'ro', 'bg', 'hr', 'sl', 'et', 'lv', 'lt',
  'el', 'uk'
];

const taxa = [];
let withAllLangs = 0;

for (const [bKey, bData] of birdnetMap) {
  const iocKey = matchResult.get(bKey);
  const ioc = iocKey ? iocNames[iocKey] : null;

  const commonNames = {};

  if (ioc) {
    for (const lang of ALL_LANGS) {
      commonNames[lang] = ioc[lang] || '';
    }
    // BirdNET EN als Fallback falls IOC leer
    if (!commonNames.en && bData.en) {
      commonNames.en = bData.en;
    }
    withAllLangs++;
  } else {
    for (const lang of ALL_LANGS) {
      commonNames[lang] = '';
    }
    commonNames.en = bData.en || '';
  }

  // Order: IOC bevorzugen (Title Case), Fallback BirdNET
  let order = '';
  if (ioc && ioc.order) {
    order = ioc.order.charAt(0).toUpperCase() + ioc.order.slice(1).toLowerCase();
  }
  if (!order && bData.order) {
    order = bData.order.charAt(0).toUpperCase() + bData.order.slice(1).toLowerCase();
  }

  const family = ioc?.family || '';

  taxa.push({
    scientific_name: bKey,
    taxon_group: (bData.class || 'Aves').toLowerCase() === 'aves' ? 'aves' : bData.class.toLowerCase(),
    order,
    family,
    common_names: commonNames,
    iucn_status: '',
    region_tags: regionTagMap.get(bKey) || [],
    classifier_support: ['birdnet_v3']
  });
}

// Alphabetisch sortieren
taxa.sort((a, b) => a.scientific_name.localeCompare(b.scientific_name));

// Nicht-Voegel hinzufuegen
function makeNonBirdNames(de, en, fr, it) {
  const names = {};
  for (const lang of ALL_LANGS) names[lang] = '';
  names.de = de; names.en = en;
  if (fr) names.fr = fr;
  if (it) names.it = it;
  return names;
}

const nonBirds = [
  { scientific_name: 'Myotis_myotis', taxon_group: 'chiroptera', order: 'Chiroptera', family: 'Vespertilionidae', common_names: makeNonBirdNames('Grosses Mausohr', 'Greater Mouse-eared Bat', 'Grand murin', 'Vespertilio maggiore'), iucn_status: 'LC', region_tags: ['CH_breeding', 'central_europe'], classifier_support: [] },
  { scientific_name: 'Myotis_daubentonii', taxon_group: 'chiroptera', order: 'Chiroptera', family: 'Vespertilionidae', common_names: makeNonBirdNames('Wasserfledermaus', "Daubenton's Bat", 'Murin de Daubenton', 'Vespertilio di Daubenton'), iucn_status: 'LC', region_tags: ['CH_breeding', 'central_europe'], classifier_support: [] },
  { scientific_name: 'Pipistrellus_pipistrellus', taxon_group: 'chiroptera', order: 'Chiroptera', family: 'Vespertilionidae', common_names: makeNonBirdNames('Zwergfledermaus', 'Common Pipistrelle', 'Pipistrelle commune', 'Pipistrello nano'), iucn_status: 'LC', region_tags: ['CH_breeding', 'central_europe'], classifier_support: [] },
  { scientific_name: 'Nyctalus_noctula', taxon_group: 'chiroptera', order: 'Chiroptera', family: 'Vespertilionidae', common_names: makeNonBirdNames('Grosser Abendsegler', 'Common Noctule', 'Noctule commune', 'Nottola comune'), iucn_status: 'LC', region_tags: ['CH_breeding', 'central_europe'], classifier_support: [] },
  { scientific_name: 'Plecotus_auritus', taxon_group: 'chiroptera', order: 'Chiroptera', family: 'Vespertilionidae', common_names: makeNonBirdNames('Braunes Langohr', 'Brown Long-eared Bat', 'Oreillard brun', 'Orecchione bruno'), iucn_status: 'LC', region_tags: ['CH_breeding', 'central_europe'], classifier_support: [] },
  { scientific_name: 'Bombina_variegata', taxon_group: 'amphibia', order: 'Anura', family: 'Bombinatoridae', common_names: makeNonBirdNames('Gelbbauchunke', 'Yellow-bellied Toad', 'Sonneur a ventre jaune', 'Ululone dal ventre giallo'), iucn_status: 'LC', region_tags: ['CH_breeding', 'central_europe'], classifier_support: [] },
  { scientific_name: 'Hyla_arborea', taxon_group: 'amphibia', order: 'Anura', family: 'Hylidae', common_names: makeNonBirdNames('Laubfrosch', 'European Tree Frog', 'Rainette verte', 'Raganella europea'), iucn_status: 'LC', region_tags: ['CH_breeding', 'central_europe'], classifier_support: [] },
  { scientific_name: 'Bufo_bufo', taxon_group: 'amphibia', order: 'Anura', family: 'Bufonidae', common_names: makeNonBirdNames('Erdkroete', 'Common Toad', 'Crapaud commun', 'Rospo comune'), iucn_status: 'LC', region_tags: ['CH_breeding', 'central_europe'], classifier_support: [] },
  { scientific_name: 'Pelophylax_esculentus', taxon_group: 'amphibia', order: 'Anura', family: 'Ranidae', common_names: makeNonBirdNames('Wasserfrosch', 'Edible Frog', 'Grenouille verte', 'Rana verde'), iucn_status: 'LC', region_tags: ['CH_breeding', 'central_europe'], classifier_support: [] },
  { scientific_name: 'Alytes_obstetricans', taxon_group: 'amphibia', order: 'Anura', family: 'Alytidae', common_names: makeNonBirdNames('Geburtshelferkroete', 'Common Midwife Toad', 'Alyte accoucheur', 'Rospo ostetrico'), iucn_status: 'LC', region_tags: ['CH_breeding', 'central_europe'], classifier_support: [] },
  { scientific_name: 'Gryllus_campestris', taxon_group: 'orthoptera', order: 'Orthoptera', family: 'Gryllidae', common_names: makeNonBirdNames('Feldgrille', 'Field Cricket', 'Grillon champetre', 'Grillo campestre'), iucn_status: '', region_tags: ['CH_breeding', 'central_europe'], classifier_support: [] },
  { scientific_name: 'Tettigonia_viridissima', taxon_group: 'orthoptera', order: 'Orthoptera', family: 'Tettigoniidae', common_names: makeNonBirdNames('Gruenes Heupferd', 'Great Green Bush-Cricket', 'Grande sauterelle verte', 'Cavalletta verde'), iucn_status: '', region_tags: ['CH_breeding', 'central_europe'], classifier_support: [] },
  { scientific_name: 'Oecanthus_pellucens', taxon_group: 'orthoptera', order: 'Orthoptera', family: 'Gryllidae', common_names: makeNonBirdNames('Weinhaehnchen', 'Italian Tree Cricket', "Grillon d'Italie", 'Grillo italiano'), iucn_status: '', region_tags: ['central_europe'], classifier_support: [] }
];

// Nicht-Voegel einfuegen (sortiert an richtige Stelle)
for (const nb of nonBirds) {
  // Pruefen ob schon als BirdNET-Art vorhanden (unwahrscheinlich, aber sicher)
  const existing = taxa.findIndex(t => t.scientific_name === nb.scientific_name);
  if (existing >= 0) {
    // Bestehenden Eintrag mit Nicht-Vogel-Daten ueberschreiben
    taxa[existing] = nb;
  } else {
    taxa.push(nb);
  }
}

// Nochmal sortieren nach Einfuegen
taxa.sort((a, b) => a.scientific_name.localeCompare(b.scientific_name));

const birdCount = taxa.filter(t => t.taxon_group === 'aves').length;
const nonBirdCount = taxa.length - birdCount;

// ============================================================
// 8. Validierung
// ============================================================
console.log(`\n=== Validierung ===`);

// ch_breeding ⊂ ch_all ⊂ central_europe
const sets = {};
for (const s of regionSetsOut.sets) {
  sets[s.id] = new Set(s.species);
}

if (sets.ch_breeding && sets.ch_all) {
  const notInAll = [...sets.ch_breeding].filter(s => !sets.ch_all.has(s));
  console.log(`ch_breeding ⊂ ch_all: ${notInAll.length === 0 ? 'OK' : 'FEHLER - ' + notInAll.join(', ')}`);
}
if (sets.ch_all && sets.central_europe) {
  const notInCE = [...sets.ch_all].filter(s => !sets.central_europe.has(s));
  console.log(`ch_all ⊂ central_europe: ${notInCE.length === 0 ? 'OK' : 'FEHLER - ' + notInCE.join(', ')}`);
}

// Alle Region-Set-Arten in species_master?
const masterNames = new Set(taxa.map(t => t.scientific_name));
for (const s of regionSetsOut.sets) {
  if (s.species.length === 0) continue;
  const missing = s.species.filter(sp => !masterNames.has(sp));
  console.log(`${s.id} alle in species_master: ${missing.length === 0 ? 'OK' : 'FEHLER - ' + missing.join(', ')}`);
}

// Jeder Eintrag hat mindestens EN
const noEn = taxa.filter(t => !t.common_names.en);
console.log(`Alle haben common_names.en: ${noEn.length === 0 ? 'OK' : noEn.length + ' fehlen'}`);
if (noEn.length > 0 && noEn.length <= 10) {
  for (const t of noEn) console.log(`  ${t.scientific_name}`);
}

// CH-Arten haben DE
const chSpecies = sets.ch_all ? [...sets.ch_all] : [];
const chNoDe = chSpecies.filter(sp => {
  const t = taxa.find(x => x.scientific_name === sp);
  return t && !t.common_names.de;
});
console.log(`CH-Arten haben DE: ${chNoDe.length === 0 ? 'OK' : chNoDe.length + ' fehlen'}`);

// Keine Duplikate
const dupCheck = new Set();
let dups = 0;
for (const t of taxa) {
  if (dupCheck.has(t.scientific_name)) dups++;
  dupCheck.add(t.scientific_name);
}
console.log(`Keine Duplikate: ${dups === 0 ? 'OK' : dups + ' Duplikate'}`);

// Nicht-Voegel vorhanden
const nonBirdNames = nonBirds.map(nb => nb.scientific_name);
const nbMissing = nonBirdNames.filter(n => !masterNames.has(n));
console.log(`Nicht-Voegel (${nonBirds.length}): ${nbMissing.length === 0 ? 'OK' : nbMissing.length + ' fehlen'}`);

// ============================================================
// 9. Dateien schreiben
// ============================================================
const regionSetsPath = join(ROOT, 'src/main/resources/species/region_sets.json');
writeFileSync(regionSetsPath, JSON.stringify(regionSetsOut, null, 2) + '\n', 'utf-8');

const master = {
  version: 2,
  generated: new Date().toISOString().split('T')[0],
  sources: ['birdnet_v3_labels', 'ioc_multilingual_v15.1', 'region_sets', 'manual'],
  taxa
};

const masterPath = join(ROOT, 'src/main/resources/species/species_master.json');
writeFileSync(masterPath, JSON.stringify(master, null, 1), 'utf8');

// ============================================================
// 10. Statistiken
// ============================================================
console.log(`\n=== Region-Sets ===`);
for (const s of regionSetsOut.sets) {
  console.log(`${s.id.padEnd(20)} ${s.species.length} Arten`);
}

console.log(`\n=== Species Master ===`);
console.log(`Total Taxa:          ${taxa.length} (${birdCount} Voegel + ${nonBirdCount} Nicht-Voegel)`);
console.log(`Mit IOC-Sprachen:    ${withAllLangs}`);
console.log(`Nur EN (Fallback):   ${taxa.length - withAllLangs - nonBirdCount}`);

// Sprach-Abdeckung
const langCoverage = {};
for (const lang of ALL_LANGS) langCoverage[lang] = 0;
for (const t of taxa) {
  for (const lang of ALL_LANGS) {
    if (t.common_names[lang]) langCoverage[lang]++;
  }
}
console.log(`\nSprach-Abdeckung (Top 10):`);
const sortedLangs = Object.entries(langCoverage).sort((a, b) => b[1] - a[1]);
for (const [lang, count] of sortedLangs.slice(0, 10)) {
  const pct = (count / taxa.length * 100).toFixed(1);
  console.log(`  ${lang}: ${count}/${taxa.length} (${pct}%)`);
}

// Dateigroessen
const regionSetsSize = readFileSync(regionSetsPath).length;
const masterSize = readFileSync(masterPath).length;
console.log(`\n=== Dateigroessen ===`);
console.log(`region_sets.json:    ${(regionSetsSize / 1024).toFixed(1)} KB`);
console.log(`species_master.json: ${(masterSize / (1024 * 1024)).toFixed(1)} MB`);

console.log(`\nFertig.`);
