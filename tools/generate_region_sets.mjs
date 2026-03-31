#!/usr/bin/env node
/**
 * Generates region_sets.json by validating species lists against BirdNET V3.0 labels.
 * Usage: node tools/generate_region_sets.mjs [output-dir]
 */
import { readFileSync, writeFileSync } from 'fs';
import { join } from 'path';
import { homedir } from 'os';

// --- 1. Parse BirdNET labels ---
const labelsPath = join(homedir(), 'Documents', 'AMSEL', 'models', 'birdnet_v3_labels.csv');
const raw = readFileSync(labelsPath, 'utf-8');
const lines = raw.split('\n').filter(l => l.trim());

// Header: idx;id;sci_name;com_name;class;order
const header = lines[0].replace(/^\uFEFF/, '').split(';');
const sciIdx = header.indexOf('sci_name');

// Build set of all BirdNET scientific names (with underscores)
const birdnetNames = new Set();
for (let i = 1; i < lines.length; i++) {
  const cols = lines[i].split(';');
  if (cols[sciIdx]) {
    const name = cols[sciIdx].trim().replace(/ /g, '_');
    birdnetNames.add(name);
  }
}
console.log(`BirdNET labels loaded: ${birdnetNames.size} species`);

// --- 2. Species lists from task ---

const ch_breeding_raw = `
Accipiter_gentilis, Accipiter_nisus, Acrocephalus_arundinaceus, Acrocephalus_palustris,
Acrocephalus_scirpaceus, Actitis_hypoleucos, Aegithalos_caudatus, Aegolius_funereus,
Alauda_arvensis, Alcedo_atthis, Anas_crecca, Anas_platyrhynchos, Anas_querquedula,
Anthus_campestris, Anthus_pratensis, Anthus_spinoletta, Anthus_trivialis, Apus_apus,
Apus_melba, Aquila_chrysaetos, Ardea_cinerea, Ardea_purpurea, Asio_otus,
Athene_noctua, Aythya_ferina, Aythya_fuligula, Bonasa_bonasia,
Botaurus_stellaris, Bubo_bubo, Burhinus_oedicnemus, Buteo_buteo,
Caprimulgus_europaeus, Carduelis_carduelis, Certhia_brachydactyla, Certhia_familiaris,
Charadrius_dubius, Chloris_chloris, Ciconia_ciconia, Ciconia_nigra,
Cinclus_cinclus, Circaetus_gallicus, Circus_aeruginosus, Circus_cyaneus,
Coccothraustes_coccothraustes, Columba_livia, Columba_oenas, Columba_palumbus,
Coracias_garrulus, Corvus_corax, Corvus_corone, Corvus_monedula,
Coturnix_coturnix, Crex_crex, Cuculus_canorus, Cyanistes_caeruleus,
Delichon_urbicum, Dendrocopos_major, Dendrocopos_medius, Dendrocopos_minor,
Dryobates_minor, Dryocopus_martius, Egretta_alba, Emberiza_cia,
Emberiza_cirlus, Emberiza_citrinella, Emberiza_hortulana, Emberiza_schoeniclus,
Erithacus_rubecula, Falco_peregrinus, Falco_subbuteo, Falco_tinnunculus,
Ficedula_albicollis, Ficedula_hypoleuca, Fringilla_coelebs, Fringilla_montifringilla,
Fulica_atra, Galerida_cristata, Gallinago_gallinago, Gallinula_chloropus,
Garrulus_glandarius, Glaucidium_passerinum, Gypaetus_barbatus, Gyps_fulvus,
Hieraaetus_pennatus, Hippolais_icterina, Hippolais_polyglotta, Hirundo_rustica,
Ixobrychus_minutus, Jynx_torquilla, Lagopus_muta, Lanius_collurio,
Lanius_senator, Larus_michahellis, Linaria_cannabina, Locustella_naevia,
Lophophanes_cristatus, Loxia_curvirostra, Lullula_arborea, Luscinia_megarhynchos,
Luscinia_svecica, Mareca_strepera, Mergus_merganser, Merops_apiaster,
Milvus_migrans, Milvus_milvus, Monticola_saxatilis, Montifringilla_nivalis,
Motacilla_alba, Motacilla_cinerea, Motacilla_flava, Muscicapa_striata,
Netta_rufina, Nucifraga_caryocatactes, Nycticorax_nycticorax, Oenanthe_oenanthe,
Oriolus_oriolus, Otus_scops, Pandion_haliaetus, Parus_major,
Passer_domesticus, Passer_montanus, Perdix_perdix, Periparus_ater,
Pernis_apivorus, Phalacrocorax_carbo, Phoenicurus_ochruros, Phoenicurus_phoenicurus,
Phylloscopus_bonelli, Phylloscopus_collybita, Phylloscopus_sibilatrix, Phylloscopus_trochilus,
Pica_pica, Picus_canus, Picus_viridis, Podiceps_cristatus,
Podiceps_nigricollis, Poecile_montanus, Poecile_palustris, Prunella_collaris,
Prunella_modularis, Pterocles_orientalis, Ptyonoprogne_rupestris, Pyrrhocorax_graculus,
Pyrrhocorax_pyrrhocorax, Pyrrhula_pyrrhula, Rallus_aquaticus, Regulus_ignicapilla,
Regulus_regulus, Remiz_pendulinus, Riparia_riparia, Saxicola_rubetra,
Saxicola_rubicola, Scolopax_rusticola, Serinus_serinus, Sitta_europaea,
Spatula_clypeata, Spinus_spinus, Sterna_hirundo, Streptopelia_decaocto,
Streptopelia_turtur, Strix_aluco, Sturnus_vulgaris, Sylvia_atricapilla,
Sylvia_borin, Sylvia_communis, Sylvia_curruca, Tachybaptus_ruficollis,
Tachymarptis_melba, Tadorna_tadorna, Tetrao_tetrix, Tetrao_urogallus,
Tichodroma_muraria, Tringa_totanus, Troglodytes_troglodytes, Turdus_merula,
Turdus_philomelos, Turdus_pilaris, Turdus_torquatus, Turdus_viscivorus,
Tyto_alba, Upupa_epops, Vanellus_vanellus
`;

const ch_all_extra_raw = `
Anas_acuta, Anas_penelope, Anser_albifrons, Anser_anser, Anser_fabalis,
Arenaria_interpres, Branta_canadensis, Branta_leucopsis, Bubulcus_ibis,
Bucephala_clangula, Calidris_alba, Calidris_alpina, Calidris_canutus,
Calidris_ferruginea, Calidris_minuta, Calidris_temminckii, Charadrius_hiaticula,
Charadrius_morinellus, Chlidonias_niger, Chroicocephalus_ridibundus,
Cygnus_cygnus, Cygnus_olor, Falco_columbarius, Falco_vespertinus,
Ficedula_parva, Fulmarus_glacialis, Gavia_arctica, Gavia_stellata,
Haematopus_ostralegus, Hydrocoloeus_minutus, Larus_argentatus, Larus_canus,
Larus_fuscus, Larus_ridibundus, Limosa_limosa, Lymnocryptes_minimus,
Mergellus_albellus, Mergus_serrator, Morus_bassanus, Numenius_arquata,
Numenius_phaeopus, Pandion_haliaetus, Phalacrocorax_aristotelis,
Philomachus_pugnax, Phoenicopterus_roseus, Platalea_leucorodia,
Pluvialis_apricaria, Pluvialis_squatarola, Podiceps_auritus, Podiceps_grisegena,
Recurvirostra_avosetta, Sterna_paradisaea, Tachybaptus_ruficollis,
Tadorna_ferruginea, Tringa_erythropus, Tringa_glareola, Tringa_nebularia,
Tringa_ochropus, Tringa_stagnatilis, Xenus_cinereus
`;

const central_europe_extra_raw = `
Acrocephalus_melanopogon, Acrocephalus_schoenobaenus, Actitis_macularius,
Alca_torda, Anas_clypeata, Anser_brachyrhynchus, Anthus_cervinus,
Anthus_petrosus, Aquila_pomarina, Ardea_alba, Aythya_marila,
Aythya_nyroca, Botaurus_stellaris, Calandrella_brachydactyla, Calidris_maritima,
Caprimulgus_europaeus, Cepphus_grylle, Cettia_cetti, Charadrius_alexandrinus,
Chlidonias_hybrida, Chlidonias_leucopterus, Circus_pygargus,
Cisticola_juncidis, Clanga_pomarina, Coracias_garrulus, Corvus_frugilegus,
Crex_crex, Cygnus_columbianus, Dendrocopos_leucotos, Dendrocopos_syriacus,
Dryobates_minor, Egretta_garzetta, Emberiza_calandra, Falco_eleonorae,
Ficedula_albicollis, Fratercula_arctica, Galerida_cristata, Gelochelidon_nilotica,
Grus_grus, Haliaeetus_albicilla, Himantopus_himantopus, Hippolais_icterina,
Ixobrychus_minutus, Lanius_excubitor, Lanius_minor, Larus_marinus,
Limosa_lapponica, Locustella_fluviatilis, Locustella_luscinioides,
Lullula_arborea, Luscinia_luscinia, Marmaronetta_angustirostris,
Merops_apiaster, Milvus_migrans, Milvus_milvus, Motacilla_citreola,
Muscicapa_striata, Nycticorax_nycticorax, Oceanodroma_leucorhoa,
Otis_tarda, Otus_scops, Oxyura_leucocephala, Panurus_biarmicus,
Passer_hispaniolensis, Petronia_petronia, Phalacrocorax_pygmeus,
Phoenicurus_moussieri, Phylloscopus_inornatus, Platalea_leucorodia,
Plectrophenax_nivalis, Podiceps_auritus, Pterocles_orientalis,
Recurvirostra_avosetta, Rissa_tridactyla, Saxicola_rubetra,
Somateria_mollissima, Sterna_albifrons, Sterna_caspia, Sterna_sandvicensis,
Stercorarius_parasiticus, Stercorarius_skua, Sternula_albifrons,
Sylvia_cantillans, Sylvia_conspicillata, Sylvia_hortensis, Sylvia_melanocephala,
Sylvia_nisoria, Sylvia_sarda, Sylvia_undata, Tadorna_tadorna,
Thalasseus_sandvicensis, Tringa_totanus, Uria_aalge, Vanellus_vanellus
`;

// --- 3. Parse + dedupe ---
function parseList(raw) {
  return [...new Set(
    raw.split(/[,\n]/)
      .map(s => s.trim())
      .filter(s => s.length > 0)
  )].sort();
}

// Known taxonomic synonyms: task name → BirdNET name
const synonyms = {
  'Anas_querquedula': 'Spatula_querquedula',
  'Anas_clypeata': 'Spatula_clypeata',
  'Anas_penelope': 'Mareca_penelope',
  'Egretta_alba': 'Ardea_alba',
  'Bubulcus_ibis': 'Ardea_ibis',
  'Tetrao_tetrix': 'Lyrurus_tetrix',
  'Corvus_monedula': 'Coloeus_monedula',
  'Bonasa_bonasia': 'Tetrastes_bonasia',
  'Apus_melba': 'Tachymarptis_melba',
  'Dendrocopos_medius': 'Dendrocoptes_medius',
  'Dendrocopos_minor': 'Dryobates_minor',
  'Sylvia_communis': 'Curruca_communis',
  'Sylvia_curruca': 'Curruca_curruca',
  'Sylvia_cantillans': 'Curruca_cantillans',
  'Sylvia_conspicillata': 'Curruca_conspicillata',
  'Sylvia_hortensis': 'Curruca_hortensis',
  'Sylvia_melanocephala': 'Curruca_melanocephala',
  'Sylvia_nisoria': 'Curruca_nisoria',
  'Sylvia_sarda': 'Curruca_sarda',
  'Sylvia_undata': 'Curruca_undata',
  'Larus_ridibundus': 'Chroicocephalus_ridibundus',
  'Philomachus_pugnax': 'Calidris_pugnax',
  'Phalacrocorax_aristotelis': 'Gulosus_aristotelis',
  'Phalacrocorax_pygmeus': 'Microcarbo_pygmaeus',
  'Charadrius_morinellus': 'Eudromias_morinellus',
  'Charadrius_alexandrinus': 'Anarhynchus_alexandrinus',
  'Ixobrychus_minutus': 'Botaurus_minutus',
  'Oceanodroma_leucorhoa': 'Hydrobates_leucorhous',
  'Aquila_pomarina': 'Clanga_pomarina',
  'Accipiter_gentilis': 'Astur_gentilis',
  'Anser_brachyrhynchus': 'Anser_brachyrhynchus',
  'Sterna_albifrons': 'Sternula_albifrons',
  'Sterna_caspia': 'Hydroprogne_caspia',
  'Sterna_sandvicensis': 'Thalasseus_sandvicensis',
};

function validate(rawList, label) {
  const parsed = parseList(rawList);
  const validated = [];
  const notFound = [];

  for (const name of parsed) {
    if (birdnetNames.has(name)) {
      validated.push(name);
    } else if (synonyms[name] && birdnetNames.has(synonyms[name])) {
      console.log(`  [SYNONYM] ${name} -> ${synonyms[name]}`);
      validated.push(synonyms[name]);
    } else {
      notFound.push(name);
    }
  }

  console.log(`\n${label}: ${parsed.length} input -> ${validated.length} validated, ${notFound.length} not found`);
  if (notFound.length > 0) {
    console.log(`  NOT FOUND: ${notFound.join(', ')}`);
  }

  return [...new Set(validated)].sort();
}

// --- 4. Validate each set ---
console.log('\n=== Validating ch_breeding ===');
const ch_breeding = validate(ch_breeding_raw, 'ch_breeding');

console.log('\n=== Validating ch_all extras ===');
const ch_all_extras = validate(ch_all_extra_raw, 'ch_all extras');

console.log('\n=== Validating central_europe extras ===');
const ce_extras = validate(central_europe_extra_raw, 'central_europe extras');

// Build cumulative sets
const ch_all = [...new Set([...ch_breeding, ...ch_all_extras])].sort();
const central_europe = [...new Set([...ch_all, ...ce_extras])].sort();

console.log(`\n=== Final counts ===`);
console.log(`ch_breeding:    ${ch_breeding.length}`);
console.log(`ch_all:         ${ch_all.length}`);
console.log(`central_europe: ${central_europe.length}`);

// --- 5. Generate JSON ---
const output = {
  version: 2,
  sets: [
    {
      id: "all",
      name_de: "Alle Arten",
      name_en: "All Species",
      description_de: "Alle vom Modell unterstuetzten Arten (keine Einschraenkung)",
      species: []
    },
    {
      id: "ch_breeding",
      name_de: "Schweizer Brutvoegel",
      name_en: "Swiss Breeding Birds",
      description_de: `Regelmaessige Brutvoegel der Schweiz (${ch_breeding.length} Arten)`,
      species: ch_breeding
    },
    {
      id: "ch_all",
      name_de: "Schweiz komplett",
      name_en: "Switzerland All",
      description_de: `Alle in der Schweiz regelmaessig nachgewiesenen Arten (${ch_all.length} Arten)`,
      species: ch_all
    },
    {
      id: "central_europe",
      name_de: "Mitteleuropa",
      name_en: "Central Europe",
      description_de: `Brutvoegel und regelmaessige Gaeste Mitteleuropas (${central_europe.length} Arten)`,
      species: central_europe
    }
  ]
};

const outPath = join(process.argv[2] || '.', 'region_sets.json');
writeFileSync(outPath, JSON.stringify(output, null, 2) + '\n', 'utf-8');
console.log(`\nWritten to: ${outPath}`);
