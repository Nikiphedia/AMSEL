package ch.etasystems.amsel.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Air
import androidx.compose.material.icons.filled.Nightlife
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import ch.etasystems.amsel.data.ComparisonAlgorithm

/** Preset-Definition fuer Analyse-Einstellungen */
private data class AnalysePreset(
    val name: String,
    val algorithm: ComparisonAlgorithm,
    val birdnetMinConf: Float,
    val eventPrerollSec: Float,
    val eventPostrollSec: Float,
    val sliceLengthMin: Float,
    val sliceOverlapSec: Float
)

private val ANALYSE_PRESETS = listOf(
    AnalysePreset("Voegel", ComparisonAlgorithm.BIRDNET_V3, 0.25f, 1.0f, 0.5f, 3.0f, 0f),
    AnalysePreset("Fledermaeuse", ComparisonAlgorithm.ONNX_EFFICIENTNET, 0.15f, 0.2f, 0.2f, 1.0f, 5f),
)

@Composable
internal fun TabAnalyse(
    selectedAlgorithm: ComparisonAlgorithm,
    onAlgorithmChanged: (ComparisonAlgorithm) -> Unit,
    onnxAvailable: Boolean = false,
    embeddingModelAvailable: Boolean = false,
    birdnetAvailable: Boolean = false,
    birdnetV3Available: Boolean = false,
    confText: String,
    onConfChanged: (String) -> Unit,
    prerollStr: String,
    onPrerollChanged: (String) -> Unit,
    postrollStr: String,
    onPostrollChanged: (String) -> Unit,
    minDisplayStr: String,
    onMinDisplayChanged: (String) -> Unit,
    minExportStr: String,
    onMinExportChanged: (String) -> Unit,
    shortFileStartPct: Float,
    onShortFileStartChanged: (Float) -> Unit,
    birdnetUseFiltered: Boolean = true,
    onBirdnetUseFilteredChanged: (Boolean) -> Unit = {},
    sliceLengthMin: Float = 10f,
    onSliceLengthChanged: (Float) -> Unit = {},
    sliceOverlapSec: Float = 5f,
    onSliceOverlapChanged: (Float) -> Unit = {},
    soloPrerollStr: String = "5",
    onSoloPrerollChanged: (String) -> Unit = {},
    soloPostrollStr: String = "5",
    onSoloPostrollChanged: (String) -> Unit = {},
    onOpenModelManager: () -> Unit = {}
) {
    // === ANALYSE-PRESETS (prominent, ganz oben) ===
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ANALYSE_PRESETS.forEach { preset ->
            ElevatedCard(
                onClick = {
                    onAlgorithmChanged(preset.algorithm)
                    onConfChanged("%.2f".format(preset.birdnetMinConf))
                    onPrerollChanged("%.0f".format(preset.eventPrerollSec))
                    onPostrollChanged("%.0f".format(preset.eventPostrollSec))
                    onSliceLengthChanged(preset.sliceLengthMin)
                    onSliceOverlapChanged(preset.sliceOverlapSec)
                },
                modifier = Modifier.weight(1f)
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(
                        if (preset.name == "Voegel") Icons.Default.Air else Icons.Default.Nightlife,
                        contentDescription = preset.name,
                        modifier = Modifier.size(32.dp)
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(preset.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                    Text(
                        "${preset.algorithm.name.replace('_', ' ')}, Konf. ${(preset.birdnetMinConf * 100).toInt()}%",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }

    Spacer(Modifier.height(12.dp))

    // === KLASSIFIZIERUNG (Card) ===
    SectionCard(title = "Klassifizierung") {
        OutlinedTextField(
            value = confText,
            onValueChange = onConfChanged,
            label = { Text("Min. Konfidenz (0.01 - 0.5)") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            supportingText = { Text("0.1 = 10% (empfohlen), 0.25 = strenger") }
        )

        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(
                checked = birdnetUseFiltered,
                onCheckedChange = { onBirdnetUseFilteredChanged(it) }
            )
            Column {
                Text("Bearbeitetes Material analysieren", style = MaterialTheme.typography.bodyMedium)
                Text(
                    "Filter + Volume Envelope werden vor BirdNET-Analyse angewendet. Verbessert Erkennung bei verrauschtem Material.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        OutlinedButton(onClick = onOpenModelManager) {
            Text("Modelle verwalten...")
        }
    }

    Spacer(Modifier.height(12.dp))

    // === WIEDERGABE (Card) ===
    SectionCard(title = "Event-Klick Vor-/Nachlauf") {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = prerollStr,
                onValueChange = { onPrerollChanged(it.filter { c -> c.isDigit() || c == '.' }) },
                label = { Text("Vorlauf") },
                suffix = { Text("sec") },
                singleLine = true,
                modifier = Modifier.weight(1f)
            )
            OutlinedTextField(
                value = postrollStr,
                onValueChange = { onPostrollChanged(it.filter { c -> c.isDigit() || c == '.' }) },
                label = { Text("Nachlauf") },
                suffix = { Text("sec") },
                singleLine = true,
                modifier = Modifier.weight(1f)
            )
        }
        Text(
            "Zoom-Bereich bei Klick auf Event in der Seitenleiste.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
        )
    }

    Spacer(Modifier.height(12.dp))

    SectionCard(title = "Solo-Modus Vor-/Nachlauf") {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = soloPrerollStr,
                onValueChange = { onSoloPrerollChanged(it.filter { c -> c.isDigit() || c == '.' }) },
                label = { Text("Vorlauf") },
                suffix = { Text("sec") },
                singleLine = true,
                modifier = Modifier.weight(1f)
            )
            OutlinedTextField(
                value = soloPostrollStr,
                onValueChange = { onSoloPostrollChanged(it.filter { c -> c.isDigit() || c == '.' }) },
                label = { Text("Nachlauf") },
                suffix = { Text("sec") },
                singleLine = true,
                modifier = Modifier.weight(1f)
            )
        }
        Text(
            "Zoom-Bereich im Solo-Modus (Tab-Navigation zwischen Chunks).",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
        )
    }

    Spacer(Modifier.height(12.dp))

    // === ERWEITERT (Collapsible) ===
    ExpandableSection(title = "Erweitert") {
        Text(
            "Slice-Laenge: ${sliceLengthMin.toInt()} min",
            style = MaterialTheme.typography.bodySmall
        )
        Slider(
            value = sliceLengthMin,
            onValueChange = onSliceLengthChanged,
            valueRange = 1f..30f,
            steps = 28,
            modifier = Modifier.fillMaxWidth()
        )

        Text(
            "Slice-Ueberlappung: ${sliceOverlapSec.toInt()} sec",
            style = MaterialTheme.typography.bodySmall
        )
        Slider(
            value = sliceOverlapSec,
            onValueChange = onSliceOverlapChanged,
            valueRange = 0f..30f,
            steps = 29,
            modifier = Modifier.fillMaxWidth()
        )
        Text(
            "Lange Dateien werden in Slices aufgeteilt. BirdNET und Filter arbeiten slice-weise. Ueberlappung verhindert verpasste Detektionen an Slice-Grenzen.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
        )

        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

        Text("Kurze Aufnahmen", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = minDisplayStr,
                onValueChange = { onMinDisplayChanged(it.filter { c -> c.isDigit() || c == '.' }) },
                label = { Text("Mindest-Anzeige") },
                suffix = { Text("sec") },
                singleLine = true,
                modifier = Modifier.weight(1f)
            )
            OutlinedTextField(
                value = minExportStr,
                onValueChange = { onMinExportChanged(it.filter { c -> c.isDigit() || c == '.' }) },
                label = { Text("Mindest-Export") },
                suffix = { Text("sec") },
                singleLine = true,
                modifier = Modifier.weight(1f)
            )
        }
        Text(
            "Dateien kuerzer als diese Dauer werden auf die Mindestlaenge gestreckt (mit Stille aufgefuellt).",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
        )

        Text(
            "Datei-Startposition: ${(shortFileStartPct * 100).toInt()}%",
            style = MaterialTheme.typography.bodySmall
        )
        Slider(
            value = shortFileStartPct,
            onValueChange = onShortFileStartChanged,
            valueRange = 0f..1f,
            steps = 9,
            modifier = Modifier.fillMaxWidth()
        )
        Text(
            "Wo die Aufnahme im Zeitfenster beginnt (0% = ganz links, 50% = zentriert).",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
        )
    }
}

