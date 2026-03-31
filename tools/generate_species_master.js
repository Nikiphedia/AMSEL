const fs = require('fs');

// Load existing data
const categories = JSON.parse(fs.readFileSync('D:/80002/AMSEL/src/main/resources/species/download_categories.json', 'utf8'));
const speciesDe = JSON.parse(fs.readFileSync('D:/80002/AMSEL/src/main/resources/i18n/species_de.json', 'utf8'));
const regionSets = JSON.parse(fs.readFileSync('D:/80002/AMSEL/src/main/resources/species/region_sets.json', 'utf8'));

// Reverse map: German -> English (from species_de.json)
const deToEn = {};
for (const [en, de] of Object.entries(speciesDe)) {
  deToEn[de] = en;
}

// Direct scientific -> English name for species missing in species_de.json
const sciToEn = {
  'Alauda_arvensis': 'Eurasian Skylark',
  'Anas_strepera': 'Gadwall',
  'Anser_anser': 'Greylag Goose',
  'Anser_fabalis': 'Bean Goose',
  'Ardea_alba': 'Great Egret',
  'Bombycilla_garrulus': 'Bohemian Waxwing',
  'Botaurus_stellaris': 'Eurasian Bittern',
  'Calcarius_lapponicus': 'Lapland Longspur',
  'Certhia_brachydactyla': 'Short-toed Treecreeper',
  'Certhia_familiaris': 'Eurasian Treecreeper',
  'Charadrius_dubius': 'Little Ringed Plover',
  'Chloris_chloris': 'European Greenfinch',
  'Ciconia_nigra': 'Black Stork',
  'Coracias_garrulus': 'European Roller',
  'Corvus_frugilegus': 'Rook',
  'Coturnix_coturnix': 'Common Quail',
  'Crex_crex': 'Corncrake',
  'Dendrocopos_leucotos': 'White-backed Woodpecker',
  'Egretta_garzetta': 'Little Egret',
  'Emberiza_hortulana': 'Ortolan Bunting',
  'Ficedula_albicollis': 'Collared Flycatcher',
  'Ficedula_hypoleuca': 'European Pied Flycatcher',
  'Gallinago_gallinago': 'Common Snipe',
  'Hippolais_icterina': 'Icterine Warbler',
  'Hippolais_polyglotta': 'Melodious Warbler',
  'Lanius_collurio': 'Red-backed Shrike',
  'Lanius_excubitor': 'Great Grey Shrike',
  'Larus_canus': 'Mew Gull',
  'Linaria_cannabina': 'Common Linnet',
  'Locustella_luscinioides': "Savi's Warbler",
  'Locustella_naevia': 'Common Grasshopper Warbler',
  'Lullula_arborea': 'Woodlark',
  'Luscinia_svecica': 'Bluethroat',
  'Mergus_merganser': 'Common Merganser',
  'Monticola_saxatilis': 'Rufous-tailed Rock Thrush',
  'Muscicapa_striata': 'Spotted Flycatcher',
  'Nucifraga_caryocatactes': 'Spotted Nutcracker',
  'Nycticorax_nycticorax': 'Black-crowned Night Heron',
  'Oriolus_oriolus': 'Eurasian Golden Oriole',
  'Pandion_haliaetus': 'Western Osprey',
  'Panurus_biarmicus': 'Bearded Reedling',
  'Perdix_perdix': 'Grey Partridge',
  'Pernis_apivorus': 'European Honey Buzzard',
  'Phalacrocorax_carbo': 'Great Cormorant',
  'Phasianus_colchicus': 'Common Pheasant',
  'Pinicola_enucleator': 'Pine Grosbeak',
  'Plectrophenax_nivalis': 'Snow Bunting',
  'Sterna_hirundo': 'Common Tern',
  'Sylvia_curruca': 'Lesser Whitethroat',
  'Tadorna_tadorna': 'Common Shelduck',
  'Tringa_ochropus': 'Green Sandpiper',
  'Tringa_totanus': 'Common Redshank'
};

// Family -> Order mapping
const familyToOrder = {
  'Turdidae': 'Passeriformes', 'Paridae': 'Passeriformes', 'Fringillidae': 'Passeriformes',
  'Emberizidae': 'Passeriformes', 'Phylloscopidae': 'Passeriformes', 'Sylviidae': 'Passeriformes',
  'Acrocephalidae': 'Passeriformes', 'Muscicapidae': 'Passeriformes', 'Picidae': 'Piciformes',
  'Strigidae': 'Strigiformes', 'Accipitridae': 'Accipitriformes', 'Falconidae': 'Falconiformes',
  'Anatidae': 'Anseriformes', 'Ardeidae': 'Pelecaniformes', 'Rallidae': 'Gruiformes',
  'Charadriiformes': 'Charadriiformes', 'Laridae': 'Charadriiformes',
  'Columbidae': 'Columbiformes', 'Phalacrocoracidae': 'Suliformes',
  'Podicipedidae': 'Podicipediformes', 'Ciconiidae': 'Ciconiiformes',
  'Alaudidae': 'Passeriformes', 'Motacillidae': 'Passeriformes',
  'Hirundinidae': 'Passeriformes', 'Apodidae': 'Apodiformes',
  'Passeridae': 'Passeriformes', 'Laniidae': 'Passeriformes',
  'Corvidae': 'Passeriformes', 'Sittidae': 'Passeriformes',
  'Certhiidae': 'Passeriformes', 'Tichodromidae': 'Passeriformes',
  'Troglodytidae': 'Passeriformes', 'Cinclidae': 'Passeriformes',
  'Prunellidae': 'Passeriformes', 'Regulidae': 'Passeriformes',
  'Alcedinidae': 'Coraciiformes', 'Meropidae': 'Coraciiformes',
  'Coraciidae': 'Coraciiformes', 'Upupidae': 'Bucerotiformes',
  'Phasianidae': 'Galliformes', 'Sturnidae': 'Passeriformes',
  'Bombycillidae': 'Passeriformes', 'Oriolidae': 'Passeriformes',
  'Cuculidae': 'Cuculiformes', 'Caprimulgidae': 'Caprimulgiformes',
  'Panuridae': 'Passeriformes'
};

// Build region tag lookup: scientific_name (underscore) -> tags
const regionTagMap = {};
for (const set of regionSets.sets) {
  if (set.species.length === 0) continue; // 'all' set
  const tag = set.id === 'ch_breeding' ? 'CH_breeding' :
              set.id === 'ch_all' ? 'CH_all' :
              set.id === 'central_europe' ? 'central_europe' : set.id;
  for (const sp of set.species) {
    if (!regionTagMap[sp]) regionTagMap[sp] = [];
    regionTagMap[sp].push(tag);
  }
}

// Build taxa from download_categories
const taxa = [];
const seen = new Set();

for (const cat of categories) {
  for (const sp of cat.species) {
    const sciUnderscore = sp.scientific.replace(/ /g, '_');
    if (seen.has(sciUnderscore)) continue;
    seen.add(sciUnderscore);

    const germanName = sp.german;
    const englishName = deToEn[germanName] || sciToEn[sciUnderscore] || '';
    const family = sp.taxonomicFamily;
    const order = familyToOrder[family] || '';

    const tags = regionTagMap[sciUnderscore] || [];

    taxa.push({
      scientific_name: sciUnderscore,
      taxon_group: 'aves',
      order: order,
      family: family,
      common_name_de: germanName,
      common_name_en: englishName,
      common_name_fr: '',
      iucn_status: '',
      region_tags: tags,
      classifier_support: ['birdnet_v3', 'birdnet_v2']
    });
  }
}

// Sort alphabetically
taxa.sort((a, b) => a.scientific_name.localeCompare(b.scientific_name));

// Add non-bird examples
const nonBirds = [
  { scientific_name: 'Myotis_myotis', taxon_group: 'chiroptera', order: 'Chiroptera', family: 'Vespertilionidae', common_name_de: 'Grosses Mausohr', common_name_en: 'Greater Mouse-eared Bat', common_name_fr: 'Grand murin', iucn_status: 'LC', region_tags: ['CH_breeding', 'central_europe'], classifier_support: [] },
  { scientific_name: 'Myotis_daubentonii', taxon_group: 'chiroptera', order: 'Chiroptera', family: 'Vespertilionidae', common_name_de: 'Wasserfledermaus', common_name_en: "Daubenton's Bat", common_name_fr: 'Murin de Daubenton', iucn_status: 'LC', region_tags: ['CH_breeding', 'central_europe'], classifier_support: [] },
  { scientific_name: 'Pipistrellus_pipistrellus', taxon_group: 'chiroptera', order: 'Chiroptera', family: 'Vespertilionidae', common_name_de: 'Zwergfledermaus', common_name_en: 'Common Pipistrelle', common_name_fr: 'Pipistrelle commune', iucn_status: 'LC', region_tags: ['CH_breeding', 'central_europe'], classifier_support: [] },
  { scientific_name: 'Nyctalus_noctula', taxon_group: 'chiroptera', order: 'Chiroptera', family: 'Vespertilionidae', common_name_de: 'Grosser Abendsegler', common_name_en: 'Common Noctule', common_name_fr: 'Noctule commune', iucn_status: 'LC', region_tags: ['CH_breeding', 'central_europe'], classifier_support: [] },
  { scientific_name: 'Plecotus_auritus', taxon_group: 'chiroptera', order: 'Chiroptera', family: 'Vespertilionidae', common_name_de: 'Braunes Langohr', common_name_en: 'Brown Long-eared Bat', common_name_fr: 'Oreillard brun', iucn_status: 'LC', region_tags: ['CH_breeding', 'central_europe'], classifier_support: [] },
  { scientific_name: 'Bombina_variegata', taxon_group: 'amphibia', order: 'Anura', family: 'Bombinatoridae', common_name_de: 'Gelbbauchunke', common_name_en: 'Yellow-bellied Toad', common_name_fr: 'Sonneur a ventre jaune', iucn_status: 'LC', region_tags: ['CH_breeding', 'central_europe'], classifier_support: [] },
  { scientific_name: 'Hyla_arborea', taxon_group: 'amphibia', order: 'Anura', family: 'Hylidae', common_name_de: 'Laubfrosch', common_name_en: 'European Tree Frog', common_name_fr: 'Rainette verte', iucn_status: 'LC', region_tags: ['CH_breeding', 'central_europe'], classifier_support: [] },
  { scientific_name: 'Bufo_bufo', taxon_group: 'amphibia', order: 'Anura', family: 'Bufonidae', common_name_de: 'Erdkroete', common_name_en: 'Common Toad', common_name_fr: 'Crapaud commun', iucn_status: 'LC', region_tags: ['CH_breeding', 'central_europe'], classifier_support: [] },
  { scientific_name: 'Pelophylax_esculentus', taxon_group: 'amphibia', order: 'Anura', family: 'Ranidae', common_name_de: 'Wasserfrosch', common_name_en: 'Edible Frog', common_name_fr: 'Grenouille verte', iucn_status: 'LC', region_tags: ['CH_breeding', 'central_europe'], classifier_support: [] },
  { scientific_name: 'Alytes_obstetricans', taxon_group: 'amphibia', order: 'Anura', family: 'Alytidae', common_name_de: 'Geburtshelferkroete', common_name_en: 'Common Midwife Toad', common_name_fr: 'Alyte accoucheur', iucn_status: 'LC', region_tags: ['CH_breeding', 'central_europe'], classifier_support: [] },
  { scientific_name: 'Gryllus_campestris', taxon_group: 'orthoptera', order: 'Orthoptera', family: 'Gryllidae', common_name_de: 'Feldgrille', common_name_en: 'Field Cricket', common_name_fr: 'Grillon champetre', iucn_status: '', region_tags: ['CH_breeding', 'central_europe'], classifier_support: [] },
  { scientific_name: 'Tettigonia_viridissima', taxon_group: 'orthoptera', order: 'Orthoptera', family: 'Tettigoniidae', common_name_de: 'Gruenes Heupferd', common_name_en: 'Great Green Bush-Cricket', common_name_fr: 'Grande sauterelle verte', iucn_status: '', region_tags: ['CH_breeding', 'central_europe'], classifier_support: [] },
  { scientific_name: 'Oecanthus_pellucens', taxon_group: 'orthoptera', order: 'Orthoptera', family: 'Gryllidae', common_name_de: 'Weinhaehnchen', common_name_en: 'Italian Tree Cricket', common_name_fr: "Grillon d'Italie", iucn_status: '', region_tags: ['central_europe'], classifier_support: [] }
];

taxa.push(...nonBirds);

const master = {
  version: 1,
  generated: '2026-03-31',
  sources: ['download_categories', 'species_de', 'region_sets', 'manual'],
  taxa: taxa
};

console.log('Birds: ' + (taxa.length - nonBirds.length));
console.log('Non-birds: ' + nonBirds.length);
console.log('Total taxa: ' + taxa.length);

// Check how many have English names
const withEn = taxa.filter(t => t.common_name_en).length;
console.log('With English name: ' + withEn);
console.log('Without English name: ' + (taxa.length - withEn));

// Check for missing orders
const noOrder = taxa.filter(t => !t.order);
if (noOrder.length > 0) {
  console.log('Missing order for: ' + noOrder.map(t => t.scientific_name + ' (' + t.family + ')').join(', '));
}

fs.writeFileSync('D:/80002/AMSEL/src/main/resources/species/species_master.json', JSON.stringify(master, null, 2), 'utf8');
console.log('Written species_master.json');
