/**
 * Generiert species_master.json v2 mit mehrsprachigen Artnamen (23 Sprachen).
 *
 * Eingabedaten:
 *   - tools/data/ioc_names.json (generiert von extract_ioc_names.py)
 *   - src/main/resources/species/download_categories.json (Artenliste)
 *   - src/main/resources/species/region_sets.json (Regionen-Tags)
 *
 * Ausgabe:
 *   - src/main/resources/species/species_master.json (v2)
 *
 * Ausfuehren: node tools/generate_species_master_multilingual.js
 */

const fs = require('fs');
const path = require('path');

const ROOT = path.resolve(__dirname, '..');

// Datenquellen laden
const iocNames = JSON.parse(fs.readFileSync(path.join(__dirname, 'data', 'ioc_names.json'), 'utf8'));
const categories = JSON.parse(fs.readFileSync(path.join(ROOT, 'src/main/resources/species/download_categories.json'), 'utf8'));
const regionSets = JSON.parse(fs.readFileSync(path.join(ROOT, 'src/main/resources/species/region_sets.json'), 'utf8'));

// Alle 23 Sprachcodes (22 EU-Amtssprachen + Ukrainisch)
const ALL_LANGS = [
  'de', 'en', 'fr', 'it', 'es', 'pt', 'nl',
  'da', 'sv', 'fi', 'pl', 'cs', 'sk', 'hu',
  'ro', 'bg', 'hr', 'sl', 'et', 'lv', 'lt',
  'el', 'uk'
  // ga (Irisch), mt (Maltesisch) nicht in IOC enthalten
];

// Family -> Order Mapping (erweitert)
const familyToOrder = {
  // Passeriformes
  'Turdidae': 'Passeriformes', 'Paridae': 'Passeriformes', 'Fringillidae': 'Passeriformes',
  'Emberizidae': 'Passeriformes', 'Phylloscopidae': 'Passeriformes', 'Sylviidae': 'Passeriformes',
  'Acrocephalidae': 'Passeriformes', 'Muscicapidae': 'Passeriformes', 'Alaudidae': 'Passeriformes',
  'Motacillidae': 'Passeriformes', 'Hirundinidae': 'Passeriformes', 'Passeridae': 'Passeriformes',
  'Laniidae': 'Passeriformes', 'Corvidae': 'Passeriformes', 'Sittidae': 'Passeriformes',
  'Certhiidae': 'Passeriformes', 'Tichodromidae': 'Passeriformes', 'Troglodytidae': 'Passeriformes',
  'Cinclidae': 'Passeriformes', 'Prunellidae': 'Passeriformes', 'Regulidae': 'Passeriformes',
  'Sturnidae': 'Passeriformes', 'Bombycillidae': 'Passeriformes', 'Oriolidae': 'Passeriformes',
  'Panuridae': 'Passeriformes', 'Aegithalidae': 'Passeriformes', 'Locustellidae': 'Passeriformes',
  'Remizidae': 'Passeriformes', 'Calcariidae': 'Passeriformes',
  // Andere Ordnungen
  'Picidae': 'Piciformes',
  'Strigidae': 'Strigiformes', 'Tytonidae': 'Strigiformes',
  'Accipitridae': 'Accipitriformes', 'Pandionidae': 'Accipitriformes',
  'Falconidae': 'Falconiformes',
  'Anatidae': 'Anseriformes',
  'Ardeidae': 'Pelecaniformes', 'Threskiornithidae': 'Pelecaniformes',
  'Rallidae': 'Gruiformes', 'Gruidae': 'Gruiformes',
  'Charadriidae': 'Charadriiformes', 'Scolopacidae': 'Charadriiformes',
  'Laridae': 'Charadriiformes', 'Sternidae': 'Charadriiformes',
  'Recurvirostridae': 'Charadriiformes', 'Haematopodidae': 'Charadriiformes',
  'Burhinidae': 'Charadriiformes', 'Glareolidae': 'Charadriiformes',
  'Columbidae': 'Columbiformes',
  'Phalacrocoracidae': 'Suliformes',
  'Podicipedidae': 'Podicipediformes',
  'Ciconiidae': 'Ciconiiformes',
  'Alcedinidae': 'Coraciiformes', 'Meropidae': 'Coraciiformes', 'Coraciidae': 'Coraciiformes',
  'Upupidae': 'Bucerotiformes',
  'Phasianidae': 'Galliformes', 'Tetraonidae': 'Galliformes',
  'Cuculidae': 'Cuculiformes',
  'Caprimulgidae': 'Caprimulgiformes',
  'Apodidae': 'Apodiformes',
  'Gaviidae': 'Gaviiformes',
  'Procellariidae': 'Procellariiformes', 'Hydrobatidae': 'Procellariiformes',
  'Pelecanidae': 'Pelecaniformes',
  'Phoenicopteridae': 'Phoenicopteriformes',
  'Otididae': 'Otidiformes',
};

// Taxonomische Synonyme: download_categories Name -> IOC v15.1 Name
// (IOC hat diese Gattungen umbenannt, download_categories nutzt noch die alten Namen)
const SYNONYMS = {
  'Sylvia_communis': 'Curruca_communis',
  'Sylvia_curruca': 'Curruca_curruca',
  'Dendrocopos_minor': 'Dryobates_minor',
  'Dendrocopos_medius': 'Dendrocoptes_medius',
  'Accipiter_gentilis': 'Astur_gentilis',
  'Anas_strepera': 'Mareca_strepera',
  'Corvus_monedula': 'Coloeus_monedula',
  'Tetrao_tetrix': 'Lyrurus_tetrix',
};

// Region-Tag-Lookup aufbauen: scientific_name (Unterstrich) -> [tags]
const regionTagMap = {};
for (const set of regionSets.sets) {
  if (set.species.length === 0) continue; // 'all' Set ueberspringen
  const tag = set.id === 'ch_breeding' ? 'CH_breeding' :
              set.id === 'ch_all' ? 'CH_all' :
              set.id === 'central_europe' ? 'central_europe' : set.id;
  for (const sp of set.species) {
    if (!regionTagMap[sp]) regionTagMap[sp] = [];
    regionTagMap[sp].push(tag);
  }
}

// Taxa aus download_categories aufbauen
const taxa = [];
const seen = new Set();
let iocHits = 0;
let iocMisses = [];

for (const cat of categories) {
  for (const sp of cat.species) {
    const sciUnderscore = sp.scientific.replace(/ /g, '_');
    if (seen.has(sciUnderscore)) continue;
    seen.add(sciUnderscore);

    const family = sp.taxonomicFamily;
    const iocKey = SYNONYMS[sciUnderscore] || sciUnderscore;
    const ioc = iocNames[iocKey];

    // Order bestimmen: IOC-Daten bevorzugen, dann familyToOrder Fallback
    let order = '';
    if (ioc && ioc.order) {
      // IOC hat Order in GROSSBUCHSTABEN, wir wollen Title Case
      order = ioc.order.charAt(0).toUpperCase() + ioc.order.slice(1).toLowerCase();
    }
    if (!order) {
      order = familyToOrder[family] || '';
    }

    // Sprachnamen aus IOC
    const commonNames = {};
    if (ioc) {
      iocHits++;
      for (const lang of ALL_LANGS) {
        commonNames[lang] = ioc[lang] || '';
      }
      // Falls IOC keinen deutschen Namen hat, download_categories verwenden
      if (!commonNames.de && sp.german) {
        commonNames.de = sp.german;
      }
    } else {
      iocMisses.push(sciUnderscore);
      // Fallback: nur DE aus download_categories
      for (const lang of ALL_LANGS) {
        commonNames[lang] = '';
      }
      commonNames.de = sp.german || '';
    }

    taxa.push({
      scientific_name: sciUnderscore,
      taxon_group: 'aves',
      order: order,
      family: family,
      common_names: commonNames,
      iucn_status: '',
      region_tags: regionTagMap[sciUnderscore] || [],
      classifier_support: ['birdnet_v3', 'birdnet_v2']
    });
  }
}

// Alphabetisch sortieren
taxa.sort((a, b) => a.scientific_name.localeCompare(b.scientific_name));

// Nicht-Voegel hinzufuegen (mit DE/EN/FR/IT, Rest leer)
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

taxa.push(...nonBirds);

// Ergebnis-Objekt
const master = {
  version: 2,
  generated: new Date().toISOString().split('T')[0],
  sources: ['ioc_multilingual_v15.1', 'download_categories', 'region_sets', 'manual'],
  taxa: taxa
};

// Statistik
const birdCount = taxa.length - nonBirds.length;
console.log(`Voegel: ${birdCount}`);
console.log(`Nicht-Voegel: ${nonBirds.length}`);
console.log(`Total Taxa: ${taxa.length}`);
console.log(`IOC-Treffer: ${iocHits} / ${birdCount}`);
if (iocMisses.length > 0) {
  console.log(`IOC-Fehlend (${iocMisses.length}): ${iocMisses.join(', ')}`);
}

// Sprach-Abdeckung zaehlen
const langCoverage = {};
for (const lang of ALL_LANGS) langCoverage[lang] = 0;
for (const t of taxa) {
  for (const lang of ALL_LANGS) {
    if (t.common_names[lang]) langCoverage[lang]++;
  }
}
console.log('\nSprach-Abdeckung:');
for (const [lang, count] of Object.entries(langCoverage).sort((a, b) => b[1] - a[1])) {
  const pct = (count / taxa.length * 100).toFixed(1);
  console.log(`  ${lang}: ${count}/${taxa.length} (${pct}%)`);
}

// Schreiben
const outPath = path.join(ROOT, 'src/main/resources/species/species_master.json');
fs.writeFileSync(outPath, JSON.stringify(master, null, 2), 'utf8');
console.log(`\nGeschrieben: ${outPath}`);
