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
    val chunkLengthMin: Float,
    val chunkOverlapSec: Float
)

private val ANALYSE_PRESETS = listOf(
    AnalysePreset("Voegel", ComparisonAlgorithm.BIRDNET_V3, 0.25f, 1.0f, 0.5f, 3.0f, 0f),
    AnalysePreset("Fledermaeuse", ComparisonAlgorithm.ONNX_EFFICIENTNET, 0.15f, 0.2f, 0.2f, 1.0f, 5f),
)

@Composable
internal fun TabAnalyse(
    selectedAlgorithm: ComparisonAlgorithm,
    onAlgorithmChanged: (ComparisonAlgorithm) -> Unit,
    onnxAvailable: Boolean,
    embeddingModelAvailable: Boolean,
    birdnetAvailable: Boolean,
    birdnetV3Available: Boolean,
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
    chunkLengthMin: Float = 10f,
    onChunkLengthChanged: (Float) -> Unit = {},
    chunkOverlapSec: Float = 5f,
    onChunkOverlapChanged: (Float) -> Unit = {},
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
                    onChunkLengthChanged(preset.chunkLengthMin)
                    onChunkOverlapChanged(preset.chunkOverlapSec)
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
        AlgorithmOption(
            title = "MFCC Basis",
            description = "26-dim Summary + Cosine Similarity. Schnell, gute Grundqualitaet.",
            selected = selectedAlgorithm == ComparisonAlgorithm.MFCC_BASIC,
            enabled = true,
            onClick = { onAlgorithmChanged(ComparisonAlgorithm.MFCC_BASIC) }
        )
        AlgorithmOption(
            title = "MFCC + DTW",
            description = "78-dim Enhanced Features + Dynamic Time Warping. Genauer, aber langsamer.",
            selected = selectedAlgorithm == ComparisonAlgorithm.MFCC_DTW,
            enabled = true,
            onClick = { onAlgorithmChanged(ComparisonAlgorithm.MFCC_DTW) }
        )
        AlgorithmOption(
            title = "ONNX EfficientNet",
            description = if (onnxAvailable) {
                "Mel-Spektrogramm Embedding via EfficientNet. Hoechste Genauigkeit."
            } else {
                "Kein ONNX-Modell gefunden. Legen Sie efficientnet_bird.onnx in %APPDATA%/AMSEL/models/ ab."
            },
            selected = selectedAlgorithm == ComparisonAlgorithm.ONNX_EFFICIENTNET,
            enabled = onnxAvailable,
            onClick = { onAlgorithmChanged(ComparisonAlgorithm.ONNX_EFFICIENTNET) }
        )
        AlgorithmOption(
            title = "Embedding Vektor-Suche",
            description = if (embeddingModelAvailable) {
                "BirdNET-Embedding + lokale Vektor-Datenbank. Findet die aehnlichsten konkreten Aufnahmen."
            } else {
                "MFCC-Pseudo-Embedding (43-dim) + Vektor-Suche. Kein ONNX-Modell noetig."
            },
            selected = selectedAlgorithm == ComparisonAlgorithm.EMBEDDING,
            enabled = true,
            onClick = { onAlgorithmChanged(ComparisonAlgorithm.EMBEDDING) }
        )
        AlgorithmOption(
            title = "BirdNET V2.4 (6000+ Arten)",
            description = if (birdnetAvailable) {
                "Offizielles BirdNET-Modell via Python. Erkennt 6000+ Vogelarten, Fledermaeuse, Amphibien. Beste Genauigkeit."
            } else {
                "Benoetigt Python + birdnetlib (pip install birdnetlib). Nicht installiert."
            },
            selected = selectedAlgorithm == ComparisonAlgorithm.BIRDNET,
            enabled = birdnetAvailable,
            onClick = { onAlgorithmChanged(ComparisonAlgorithm.BIRDNET) }
        )
        AlgorithmOption(
            title = "BirdNET V3.0 (11000+ Arten)",
            description = if (birdnetV3Available) {
                "BirdNET+ V3.0 via ONNX Runtime. 11K Arten, 32kHz. Schnell, kein Python noetig."
            } else {
                "Modell nicht gefunden. Bitte birdnet_v3.onnx in Documents/AMSEL/models/ ablegen."
            },
            selected = selectedAlgorithm == ComparisonAlgorithm.BIRDNET_V3,
            enabled = birdnetV3Available,
            onClick = { onAlgorithmChanged(ComparisonAlgorithm.BIRDNET_V3) }
        )

        Spacer(Modifier.height(4.dp))
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

    // === ERWEITERT (Collapsible) ===
    ExpandableSection(title = "Erweitert") {
        Text(
            "Chunk-Laenge: ${chunkLengthMin.toInt()} min",
            style = MaterialTheme.typography.bodySmall
        )
        Slider(
            value = chunkLengthMin,
            onValueChange = onChunkLengthChanged,
            valueRange = 1f..30f,
            steps = 28,
            modifier = Modifier.fillMaxWidth()
        )

        Text(
            "Chunk-Ueberlappung: ${chunkOverlapSec.toInt()} sec",
            style = MaterialTheme.typography.bodySmall
        )
        Slider(
            value = chunkOverlapSec,
            onValueChange = onChunkOverlapChanged,
            valueRange = 0f..30f,
            steps = 29,
            modifier = Modifier.fillMaxWidth()
        )
        Text(
            "Lange Dateien werden in Chunks aufgeteilt. BirdNET und Filter arbeiten chunk-weise. Ueberlappung verhindert verpasste Detektionen an Chunk-Grenzen.",
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

/** Algorithmus-Auswahl-Option */
@Composable
private fun AlgorithmOption(
    title: String,
    description: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit
) {
    Surface(
        onClick = { if (enabled) onClick() },
        enabled = enabled,
        shape = MaterialTheme.shapes.medium,
        color = if (selected) MaterialTheme.colorScheme.primaryContainer
                else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            RadioButton(
                selected = selected,
                onClick = { if (enabled) onClick() },
                enabled = enabled
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    title,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (enabled) MaterialTheme.colorScheme.onSurface
                            else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                )
                Text(
                    description,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (enabled) MaterialTheme.colorScheme.onSurfaceVariant
                            else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
                )
            }
        }
    }
}
