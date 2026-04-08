package ch.etasystems.amsel.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.material3.MenuAnchorType
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import ch.etasystems.amsel.data.RegionSetRegistry
import ch.etasystems.amsel.data.reference.ReferenceDownloader

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TabDatenbank(
    referenceSpeciesCount: Int,
    referenceRecordingCount: Int,
    downloadProgress: ReferenceDownloader.DownloadProgress,
    onStartDownload: (List<String>, Int) -> Unit,
    onCancelDownload: () -> Unit,
    isRescanning: Boolean = false,
    rescanResult: String = "",
    onRescan: () -> Unit = {},
    referenceMinQualityDownload: String = "B",
    referenceMinQualityDisplay: String = "C",
    onMinQualityDownloadChanged: (String) -> Unit = {},
    onMinQualityDisplayChanged: (String) -> Unit = {},
    activeRegionSet: String = "all",
    onRegionSetChanged: (String) -> Unit = {},
    isDownloadingAudio: Boolean = false,
    audioDownloadCurrent: Int = 0,
    audioDownloadTotal: Int = 0,
    audioDownloadSpecies: String = "",
    audioDownloadResult: String = "",
    audioExistingCount: Int = 0,
    audioTotalCount: Int = 0,
    onStartAudioBatchDownload: () -> Unit = {},
    onCancelAudioBatchDownload: () -> Unit = {},
) {
    val qualityOrder = listOf("A", "B", "C", "D", "E")
    val categories = remember(activeRegionSet) { buildCategoriesFromRegistry(activeRegionSet) }
    val allSpecies = remember(categories) { categories.flatMap { it.species }.map { it.scientific }.toSet() }
    var selectedSpecies by remember(allSpecies) { mutableStateOf(allSpecies.toMutableSet()) }
    var expandedFamilies by remember { mutableStateOf(mutableSetOf<String>()) }
    var downloadAll by remember { mutableStateOf(true) }
    var maxPerSpecies by remember { mutableStateOf(200f) }
    var searchQuery by remember { mutableStateOf("") }

    val regionSets = remember { RegionSetRegistry.loadSets() }
    val currentSet = remember(activeRegionSet) { RegionSetRegistry.getSet(activeRegionSet) }

    // === STATUS (kompakt, eine Zeile) ===
    Surface(
        color = MaterialTheme.colorScheme.primaryContainer,
        shape = MaterialTheme.shapes.small
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "$referenceSpeciesCount Arten, $referenceRecordingCount Referenzen",
                style = MaterialTheme.typography.labelMedium
            )
            Button(
                onClick = onRescan,
                enabled = !isRescanning && !downloadProgress.isRunning,
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
            ) {
                if (isRescanning) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                }
                Text("Neu scannen", style = MaterialTheme.typography.labelSmall)
            }
        }
    }

    if (rescanResult.isNotBlank()) {
        Text(rescanResult, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }

    Spacer(Modifier.height(12.dp))

    // === REGION & QUALITAET (Card) ===
    SectionCard(title = "Region & Qualitaet") {
        var regionSetExpanded by remember { mutableStateOf(false) }
        ExposedDropdownMenuBox(
            expanded = regionSetExpanded,
            onExpandedChange = { regionSetExpanded = !regionSetExpanded }
        ) {
            OutlinedTextField(
                value = currentSet?.nameDe ?: "Alle Arten",
                onValueChange = {},
                readOnly = true,
                modifier = Modifier.fillMaxWidth().menuAnchor(MenuAnchorType.PrimaryNotEditable),
                label = { Text("Artenset") },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = regionSetExpanded) },
                textStyle = MaterialTheme.typography.bodyMedium,
                singleLine = true
            )
            ExposedDropdownMenu(
                expanded = regionSetExpanded,
                onDismissRequest = { regionSetExpanded = false }
            ) {
                regionSets.forEach { set ->
                    DropdownMenuItem(
                        text = {
                            Column {
                                Text(set.nameDe, style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    set.descriptionDe +
                                        if (set.species.isNotEmpty()) " (${set.species.size} Arten)" else "",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                                )
                            }
                        },
                        onClick = {
                            onRegionSetChanged(set.id)
                            regionSetExpanded = false
                        }
                    )
                }
            }
        }

        Spacer(Modifier.height(8.dp))

        Text("Qualitaet Download", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            qualityOrder.forEach { q ->
                FilterChip(
                    selected = q == referenceMinQualityDownload,
                    onClick = { onMinQualityDownloadChanged(q) },
                    label = { Text(q) }
                )
            }
        }

        Text("Qualitaet Anzeige", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            qualityOrder.forEach { q ->
                FilterChip(
                    selected = q == referenceMinQualityDisplay,
                    onClick = { onMinQualityDisplayChanged(q) },
                    label = { Text(q) }
                )
            }
        }
        Text(
            "A = beste, E = schlechteste. Download filtert beim Herunterladen, Anzeige bei Referenzsuche.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
        )
    }

    Spacer(Modifier.height(12.dp))

    // === ARTENAUSWAHL (Card, scrollbar) ===
    SectionCard(title = "Artenauswahl") {
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
                leadingIcon = { Icon(Icons.Default.Search, null, modifier = Modifier.size(16.dp)) },
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

        Spacer(Modifier.height(4.dp))

        // Familien-Liste mit fixer Hoehe
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 250.dp)
                .verticalScroll(rememberScrollState())
        ) {
            val query = searchQuery.trim().lowercase()
            for (category in categories) {
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

        Text(
            "${selectedSpecies.size} von ${allSpecies.size} Arten ausgewaehlt",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
        )
    }

    Spacer(Modifier.height(12.dp))

    // === DOWNLOAD (Card) ===
    SectionCard(title = "Referenz-Download") {
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
        } else if (downloadProgress.phase.startsWith("Fertig")) {
            Text(downloadProgress.phase, style = MaterialTheme.typography.bodySmall, color = Color(0xFF4CAF50))
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Checkbox(
                checked = downloadAll,
                onCheckedChange = { downloadAll = it },
                enabled = !downloadProgress.isRunning
            )
            Text("Alle verfuegbaren Aufnahmen (empfohlen)", style = MaterialTheme.typography.bodySmall)
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

        Text(
            if (downloadAll) "Alle Aufnahmen werden geladen."
            else "Max ${maxPerSpecies.toInt()} pro Art.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End
        ) {
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
        }
    }

    Spacer(Modifier.height(12.dp))

    // === AUDIO-REFERENZEN (Card) ===
    SectionCard(title = "Audio-Referenzen") {
        Text(
            "Laedt MP3-Dateien von Xeno-Canto fuer Offline-Wiedergabe. " +
                "Artenset: ${currentSet?.nameDe ?: "Alle Arten"}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
        )

        Surface(
            color = MaterialTheme.colorScheme.secondaryContainer,
            shape = MaterialTheme.shapes.small
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(8.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    "$audioExistingCount / $audioTotalCount Audio-Dateien vorhanden",
                    style = MaterialTheme.typography.labelMedium
                )
            }
        }

        if (isDownloadingAudio) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Download: $audioDownloadCurrent / $audioDownloadTotal", style = MaterialTheme.typography.bodyMedium)
                Text(audioDownloadSpecies, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                LinearProgressIndicator(
                    progress = {
                        if (audioDownloadTotal > 0) audioDownloadCurrent.toFloat() / audioDownloadTotal
                        else 0f
                    },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }

        if (audioDownloadResult.isNotBlank() && !isDownloadingAudio) {
            Text(audioDownloadResult, style = MaterialTheme.typography.bodySmall, color = Color(0xFF4CAF50))
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End
        ) {
            if (isDownloadingAudio) {
                Button(
                    onClick = onCancelAudioBatchDownload,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFF44336))
                ) {
                    Text("Abbrechen")
                }
            } else {
                Button(
                    onClick = onStartAudioBatchDownload,
                    enabled = !downloadProgress.isRunning && audioTotalCount > 0
                ) {
                    Text("Audio herunterladen")
                }
            }
        }
    }
}
