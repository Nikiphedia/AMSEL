package ch.etasystems.amsel.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import ch.etasystems.amsel.core.classifier.BirdNetBridge
import ch.etasystems.amsel.core.classifier.EmbeddingExtractor
import ch.etasystems.amsel.core.classifier.OnnxBirdNetV3
import ch.etasystems.amsel.core.similarity.OnnxSimilarityMetric
import ch.etasystems.amsel.data.ComparisonAlgorithm
import ch.etasystems.amsel.data.SettingsStore

/**
 * Dialog zur Auswahl des Vergleichs-Algorithmus.
 * Drei Optionen: MFCC Basis, MFCC+DTW, ONNX EfficientNet.
 * ONNX ist deaktiviert wenn kein Modell vorhanden.
 */
@Composable
fun ComparisonSettingsDialog(
    onDismiss: () -> Unit,
    onAlgorithmChanged: (ComparisonAlgorithm) -> Unit
) {
    val settings = remember { SettingsStore.load() }
    var selected by remember { mutableStateOf(settings.comparisonAlgorithm) }
    val onnxAvailable = remember { OnnxSimilarityMetric.isModelAvailable() }
    val embeddingModelAvailable = remember { EmbeddingExtractor().isModelAvailable() }
    var latText by remember { mutableStateOf(settings.locationLat.toString()) }
    var lonText by remember { mutableStateOf(settings.locationLon.toString()) }
    var locationName by remember { mutableStateOf(settings.locationName) }
    var confText by remember { mutableStateOf(settings.birdnetMinConf.toString()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Vergleichs-Algorithmus") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                // Globale Standort-Einstellungen
                Text(
                    "Standort (fuer regionale Artfilterung)",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary
                )
                OutlinedTextField(
                    value = locationName,
                    onValueChange = { locationName = it },
                    label = { Text("Ort") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = latText,
                        onValueChange = { latText = it },
                        label = { Text("Breitengrad") },
                        modifier = Modifier.weight(1f),
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = lonText,
                        onValueChange = { lonText = it },
                        label = { Text("Laengengrad") },
                        modifier = Modifier.weight(1f),
                        singleLine = true
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    "Vergleichs-Algorithmus:",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(4.dp))

                // MFCC Basis
                AlgorithmOption(
                    title = "MFCC Basis",
                    description = "26-dim Summary + Cosine Similarity. Schnell, gute Grundqualitaet.",
                    selected = selected == ComparisonAlgorithm.MFCC_BASIC,
                    enabled = true,
                    onClick = { selected = ComparisonAlgorithm.MFCC_BASIC }
                )

                // MFCC + DTW
                AlgorithmOption(
                    title = "MFCC + DTW",
                    description = "78-dim Enhanced Features + Dynamic Time Warping. Genauer, aber langsamer.",
                    selected = selected == ComparisonAlgorithm.MFCC_DTW,
                    enabled = true,
                    onClick = { selected = ComparisonAlgorithm.MFCC_DTW }
                )

                // ONNX EfficientNet
                AlgorithmOption(
                    title = "ONNX EfficientNet",
                    description = if (onnxAvailable) {
                        "Mel-Spektrogramm Embedding via EfficientNet. Hoechste Genauigkeit."
                    } else {
                        "Kein ONNX-Modell gefunden. Legen Sie efficientnet_bird.onnx in %APPDATA%/AMSEL/models/ ab."
                    },
                    selected = selected == ComparisonAlgorithm.ONNX_EFFICIENTNET,
                    enabled = onnxAvailable,
                    onClick = { selected = ComparisonAlgorithm.ONNX_EFFICIENTNET }
                )

                // Embedding-Vektor-Suche
                AlgorithmOption(
                    title = "Embedding Vektor-Suche",
                    description = if (embeddingModelAvailable) {
                        "BirdNET-Embedding + lokale Vektor-Datenbank. Findet die aehnlichsten konkreten Aufnahmen."
                    } else {
                        "MFCC-Pseudo-Embedding (43-dim) + Vektor-Suche. Kein ONNX-Modell noetig."
                    },
                    selected = selected == ComparisonAlgorithm.EMBEDDING,
                    enabled = true,
                    onClick = { selected = ComparisonAlgorithm.EMBEDDING }
                )

                // BirdNET via Python-Bridge
                val birdnetAvailable = BirdNetBridge.isAvailable()
                AlgorithmOption(
                    title = "BirdNET V2.4 (6000+ Arten)",
                    description = if (birdnetAvailable) {
                        "Offizielles BirdNET-Modell via Python. Erkennt 6000+ Vogelarten, Fledermaeuse, Amphibien. Beste Genauigkeit."
                    } else {
                        "Benoetigt Python + birdnetlib (pip install birdnetlib). Nicht installiert."
                    },
                    selected = selected == ComparisonAlgorithm.BIRDNET,
                    enabled = birdnetAvailable,
                    onClick = { selected = ComparisonAlgorithm.BIRDNET }
                )

                // BirdNET V3.0 via ONNX
                val birdnetV3Available = OnnxBirdNetV3().isAvailable()
                AlgorithmOption(
                    title = "BirdNET V3.0 (11000+ Arten)",
                    description = if (birdnetV3Available) {
                        "BirdNET+ V3.0 via ONNX Runtime. 11K Arten, 32kHz. Schnell, kein Python noetig."
                    } else {
                        "Modell nicht gefunden. Bitte birdnet_v3.onnx in Documents/AMSEL/models/ ablegen."
                    },
                    selected = selected == ComparisonAlgorithm.BIRDNET_V3,
                    enabled = birdnetV3Available,
                    onClick = { selected = ComparisonAlgorithm.BIRDNET_V3 }
                )

                // BirdNET-Einstellungen (immer sichtbar wenn BirdNET verfuegbar)
                if (birdnetAvailable || birdnetV3Available) {
                    Spacer(modifier = Modifier.height(4.dp))
                    OutlinedTextField(
                        value = confText,
                        onValueChange = { confText = it },
                        label = { Text("BirdNET Min. Konfidenz (0.0 - 1.0)") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        supportingText = { Text("0.1 = 10% (empfohlen), 0.25 = strenger") }
                    )
                }
            }
        },
        confirmButton = {
            Button(onClick = {
                val updated = settings.copy(
                    comparisonAlgorithm = selected,
                    locationLat = latText.toFloatOrNull() ?: settings.locationLat,
                    locationLon = lonText.toFloatOrNull() ?: settings.locationLon,
                    locationName = locationName,
                    birdnetMinConf = confText.toFloatOrNull()?.coerceIn(0f, 1f) ?: settings.birdnetMinConf
                )
                SettingsStore.save(updated)
                onAlgorithmChanged(selected)
                onDismiss()
            }) {
                Text("Speichern")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Abbrechen") }
        }
    )
}

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
