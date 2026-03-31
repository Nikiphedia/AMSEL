package ch.etasystems.amsel.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import ch.etasystems.amsel.data.RegionSetRegistry
import ch.etasystems.amsel.data.SpeciesRegistry
import ch.etasystems.amsel.data.reference.ReferenceDownloader
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory

/** Eine Art mit wissenschaftlichem und deutschem Namen + Familie */
@Serializable
internal data class BirdSpecies(
    val scientific: String,
    val german: String,
    @SerialName("taxonomicFamily") val family: String
)

/** Familien-Kategorie mit ihren Arten */
@Serializable
internal data class FamilyCategory(
    val family: String,
    val species: List<BirdSpecies>
) {
    /** Anzeigename der Familie (Alias fuer Abwaertskompatibilitaet) */
    val name: String get() = family
}

private val downloadCategoriesLogger = LoggerFactory.getLogger("DownloadCategories")

/** Laedt die Artenliste aus der JSON-Ressource. Wird nur noch fuer Familien-Namens-Lookup verwendet. */
internal fun loadCategories(): List<FamilyCategory> {
    val stream = object {}::class.java.getResourceAsStream("/species/download_categories.json")
        ?: error("download_categories.json not found in resources")
    val json = Json { ignoreUnknownKeys = true }
    val categories = json.decodeFromString<List<FamilyCategory>>(stream.bufferedReader(Charsets.UTF_8).use { it.readText() })
    downloadCategoriesLogger.debug("Loaded {} families with {} species from download_categories.json",
        categories.size, categories.sumOf { it.species.size })
    return categories
}

/**
 * Familien-Namens-Lookup aus download_categories.json.
 * Maps lateinischen Familiennamen auf den Anzeigenamen: "Turdidae" -> "Drosseln (Turdidae)"
 */
private val familyDisplayNames: Map<String, String> by lazy {
    try {
        val categories = loadCategories()
        categories.associate { cat ->
            val latinFamily = cat.species.firstOrNull()?.family ?: ""
            latinFamily to cat.family
        }
    } catch (e: Exception) {
        downloadCategoriesLogger.warn("Konnte Familiennamen nicht laden: {}", e.message)
        emptyMap()
    }
}

/**
 * Baut die Artenliste aus SpeciesRegistry, gefiltert nach aktivem Artenset.
 * Nur Voegel (taxonGroup "aves"), gruppiert nach Familie.
 * Familiennamen werden aus download_categories.json bezogen (Fallback: lateinischer Name).
 */
internal fun buildCategoriesFromRegistry(regionSetId: String): List<FamilyCategory> {
    val allAves = SpeciesRegistry.getByTaxonGroup("aves")

    val filtered = if (regionSetId == "all") {
        allAves
    } else {
        val setSpecies = RegionSetRegistry.getSpeciesForSet(regionSetId)
        if (setSpecies.isEmpty()) {
            allAves
        } else {
            allAves.filter { it.scientificName in setSpecies }
        }
    }

    return filtered
        .groupBy { it.family }
        .toSortedMap()
        .map { (family, species) ->
            val displayName = familyDisplayNames[family] ?: family
            FamilyCategory(
                family = displayName,
                species = species.map { info ->
                    BirdSpecies(
                        scientific = info.scientificName.replace('_', ' '),
                        german = info.commonNames["de"] ?: info.scientificName.replace('_', ' '),
                        family = info.family
                    )
                }.sortedBy { it.german }
            )
        }
}

/**
 * Dialog zum Herunterladen von Xeno-Canto-Arten fuer den Offline-Cache.
 * Kategorisierte Artenliste mit Familien-Gruppen.
 */
@Composable
fun DownloadDialog(
    downloadProgress: ReferenceDownloader.DownloadProgress,
    referenceSpeciesCount: Int,
    referenceRecordingCount: Int,
    onStartDownload: (List<String>, Int) -> Unit,
    onCancelDownload: () -> Unit,
    onDismiss: () -> Unit,
    activeRegionSet: String = "all"
) {
    val categories = remember(activeRegionSet) { buildCategoriesFromRegistry(activeRegionSet) }
    val allSpecies = remember(categories) { categories.flatMap { it.species }.map { it.scientific }.toSet() }
    var selectedSpecies by remember(allSpecies) { mutableStateOf(allSpecies.toMutableSet()) }
    var expandedFamilies by remember { mutableStateOf(mutableSetOf<String>()) }
    var downloadAll by remember { mutableStateOf(true) }
    var maxPerSpecies by remember { mutableStateOf(200f) }
    var searchQuery by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = { if (!downloadProgress.isRunning) onDismiss() },
        title = { Text("Offline-Datenbank") },
        text = {
            Column(
                modifier = Modifier.width(600.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Cache-Status
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer,
                    shape = MaterialTheme.shapes.small
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("$referenceRecordingCount Referenzen", style = MaterialTheme.typography.labelMedium)
                        Text("$referenceSpeciesCount Arten", style = MaterialTheme.typography.labelMedium)
                    }
                }

                // Download-Fortschritt
                if (downloadProgress.isRunning) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            "Art ${downloadProgress.current}/${downloadProgress.total}: ${downloadProgress.species}",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Text(
                            downloadProgress.phase,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        LinearProgressIndicator(
                            progress = {
                                if (downloadProgress.total > 0) {
                                    downloadProgress.current.toFloat() / downloadProgress.total
                                } else 0f
                            },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Text(
                            "${downloadProgress.totalDownloaded} heruntergeladen, ${downloadProgress.errors} Fehler",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                        )
                    }
                } else {
                    if (downloadProgress.phase.startsWith("Fertig")) {
                        Text(
                            downloadProgress.phase,
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFF4CAF50)
                        )
                    }
                }

                HorizontalDivider()

                // Suche + Alle/Keine Buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        modifier = Modifier.weight(1f).height(44.dp),
                        placeholder = { Text("Suche...", style = MaterialTheme.typography.labelSmall) },
                        textStyle = MaterialTheme.typography.labelSmall,
                        singleLine = true,
                        enabled = !downloadProgress.isRunning
                    )
                    TextButton(
                        onClick = { selectedSpecies = allSpecies.toMutableSet() },
                        enabled = !downloadProgress.isRunning
                    ) { Text("Alle", style = MaterialTheme.typography.labelSmall) }
                    TextButton(
                        onClick = { selectedSpecies = mutableSetOf() },
                        enabled = !downloadProgress.isRunning
                    ) { Text("Keine", style = MaterialTheme.typography.labelSmall) }
                }

                // Familien-Liste mit Kategorien
                val scrollState = rememberScrollState()
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(300.dp)
                        .verticalScroll(scrollState)
                ) {
                    val query = searchQuery.trim().lowercase()
                    for (category in categories) {
                        // Filtere Arten nach Suchbegriff
                        val filteredSpecies = if (query.isEmpty()) category.species
                        else category.species.filter {
                            it.scientific.lowercase().contains(query) ||
                            it.german.lowercase().contains(query) ||
                            category.name.lowercase().contains(query)
                        }
                        if (filteredSpecies.isEmpty()) continue

                        val isExpanded = category.name in expandedFamilies || query.isNotEmpty()
                        val familySelected = filteredSpecies.all { it.scientific in selectedSpecies }
                        val familyPartial = !familySelected &&
                            filteredSpecies.any { it.scientific in selectedSpecies }

                        // Familie-Header
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    expandedFamilies = expandedFamilies.toMutableSet().apply {
                                        if (category.name in this) remove(category.name)
                                        else add(category.name)
                                    }
                                }
                                .padding(vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // Tri-State Checkbox fuer ganze Familie
                            TriStateCheckbox(
                                state = when {
                                    familySelected -> androidx.compose.ui.state.ToggleableState.On
                                    familyPartial -> androidx.compose.ui.state.ToggleableState.Indeterminate
                                    else -> androidx.compose.ui.state.ToggleableState.Off
                                },
                                onClick = {
                                    selectedSpecies = selectedSpecies.toMutableSet().apply {
                                        if (familySelected) {
                                            filteredSpecies.forEach { remove(it.scientific) }
                                        } else {
                                            filteredSpecies.forEach { add(it.scientific) }
                                        }
                                    }
                                },
                                enabled = !downloadProgress.isRunning,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(Modifier.width(4.dp))
                            Icon(
                                if (isExpanded) Icons.Default.KeyboardArrowDown
                                else Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Text(
                                "${category.name} (${filteredSpecies.size})",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(start = 4.dp)
                            )
                        }

                        // Arten der Familie (aufgeklappt)
                        if (isExpanded) {
                            for (species in filteredSpecies) {
                                val isSelected = species.scientific in selectedSpecies
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            selectedSpecies = selectedSpecies.toMutableSet().apply {
                                                if (isSelected) remove(species.scientific)
                                                else add(species.scientific)
                                            }
                                        }
                                        .padding(start = 28.dp, top = 1.dp, bottom = 1.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Checkbox(
                                        checked = isSelected,
                                        onCheckedChange = { checked ->
                                            selectedSpecies = selectedSpecies.toMutableSet().apply {
                                                if (checked) add(species.scientific) else remove(species.scientific)
                                            }
                                        },
                                        enabled = !downloadProgress.isRunning,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(Modifier.width(6.dp))
                                    Text(
                                        species.german,
                                        style = MaterialTheme.typography.bodySmall,
                                        modifier = Modifier.width(140.dp)
                                    )
                                    Text(
                                        species.scientific,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                                    )
                                }
                            }
                        }
                    }
                }

                HorizontalDivider()

                // Menge
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Checkbox(
                        checked = downloadAll,
                        onCheckedChange = { downloadAll = it },
                        enabled = !downloadProgress.isRunning
                    )
                    Text(
                        "Alle verfuegbaren Aufnahmen (empfohlen)",
                        style = MaterialTheme.typography.bodySmall
                    )
                }

                if (!downloadAll) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text("Max pro Art:", style = MaterialTheme.typography.labelMedium)
                        Slider(
                            value = maxPerSpecies,
                            onValueChange = { maxPerSpecies = it },
                            valueRange = 50f..2000f,
                            modifier = Modifier.weight(1f),
                            enabled = !downloadProgress.isRunning
                        )
                        Text("${maxPerSpecies.toInt()}", style = MaterialTheme.typography.labelMedium)
                    }
                }

                // Info
                Text(
                    "${selectedSpecies.size} von ${allSpecies.size} Arten ausgewaehlt. " +
                    if (downloadAll) "Alle Aufnahmen werden geladen."
                    else "Max ${maxPerSpecies.toInt()} pro Art.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                )
            }
        },
        confirmButton = {
            if (downloadProgress.isRunning) {
                Button(
                    onClick = onCancelDownload,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFF44336))
                ) {
                    Text("Abbrechen")
                }
            } else {
                Button(
                    onClick = {
                        val species = selectedSpecies.toList().sorted()
                        if (species.isNotEmpty()) {
                            val max = if (downloadAll) 0 else maxPerSpecies.toInt()
                            onStartDownload(species, max)
                        }
                    },
                    enabled = selectedSpecies.isNotEmpty()
                ) {
                    Text("Herunterladen (${selectedSpecies.size} Arten)")
                }
            }
        },
        dismissButton = {
            if (!downloadProgress.isRunning) {
                TextButton(onClick = onDismiss) {
                    Text("Schliessen")
                }
            }
        }
    )
}
