package ch.etasystems.amsel.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Air
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Nightlife
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/** Export-Preset-Definition */
private data class ExportPreset(
    val name: String,
    val freqMin: String,
    val freqMax: String,
    val freqStep: String,
    val maxFreq: String
)

private val EXPORT_PRESETS = listOf(
    ExportPreset("Voegel", "0", "16", "2", "16"),
    ExportPreset("Fledermaeuse", "15", "125", "10", "125"),
    ExportPreset("Insekten", "0", "50", "5", "50"),
)

@Composable
internal fun TabExport(
    freqMinKHz: String,
    onFreqMinChanged: (String) -> Unit,
    freqMaxKHz: String,
    onFreqMaxChanged: (String) -> Unit,
    freqStepKHz: String,
    onFreqStepChanged: (String) -> Unit,
    maxFreqKHz: String,
    onMaxFreqChanged: (String) -> Unit,
    secPerCm: String,
    onSecPerCmChanged: (String) -> Unit,
    cmPerHalfSec: String,
    onCmPerHalfSecChanged: (String) -> Unit,
    rowLengthCm: String,
    onRowLengthChanged: (String) -> Unit
) {
    // === EXPORT-PRESETS (prominent, ganz oben) ===
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        EXPORT_PRESETS.forEach { preset ->
            ElevatedCard(
                onClick = {
                    onFreqMinChanged(preset.freqMin)
                    onFreqMaxChanged(preset.freqMax)
                    onFreqStepChanged(preset.freqStep)
                    onMaxFreqChanged(preset.maxFreq)
                },
                modifier = Modifier.weight(1f)
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(
                        when (preset.name) {
                            "Voegel" -> Icons.Default.Air
                            "Fledermaeuse" -> Icons.Default.Nightlife
                            else -> Icons.Default.BugReport
                        },
                        contentDescription = preset.name,
                        modifier = Modifier.size(32.dp)
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(preset.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                    Text(
                        "${preset.freqMin}–${preset.freqMax} kHz",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }

    Spacer(Modifier.height(12.dp))

    // === ACHSEN (Card) ===
    SectionCard(title = "Frequenz-Achse") {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = freqMinKHz,
                onValueChange = { onFreqMinChanged(it.filter { c -> c.isDigit() || c == '.' }) },
                label = { Text("Min") },
                suffix = { Text("kHz") },
                singleLine = true,
                modifier = Modifier.weight(1f)
            )
            OutlinedTextField(
                value = freqMaxKHz,
                onValueChange = { onFreqMaxChanged(it.filter { c -> c.isDigit() || c == '.' }) },
                label = { Text("Max") },
                suffix = { Text("kHz") },
                singleLine = true,
                modifier = Modifier.weight(1f)
            )
            OutlinedTextField(
                value = freqStepKHz,
                onValueChange = { onFreqStepChanged(it.filter { c -> c.isDigit() || c == '.' }) },
                label = { Text("Schritt") },
                suffix = { Text("kHz") },
                singleLine = true,
                modifier = Modifier.weight(1f)
            )
        }
        Text(
            "Beispiel: 2 kHz = Hauptstrich alle 1 cm",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
        )
    }

    Spacer(Modifier.height(12.dp))

    SectionCard(title = "Zeitachse") {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = secPerCm,
                onValueChange = { newVal ->
                    val filtered = newVal.filter { c -> c.isDigit() || c == '.' }
                    onSecPerCmChanged(filtered)
                    val spcVal = filtered.toFloatOrNull()
                    if (spcVal != null && spcVal > 0f) {
                        onCmPerHalfSecChanged("%.2f".format(0.5f / spcVal))
                    }
                },
                label = { Text("sec/cm") },
                singleLine = true,
                modifier = Modifier.weight(1f)
            )
            OutlinedTextField(
                value = cmPerHalfSec,
                onValueChange = { newVal ->
                    val filtered = newVal.filter { c -> c.isDigit() || c == '.' }
                    onCmPerHalfSecChanged(filtered)
                    val cphVal = filtered.toFloatOrNull()
                    if (cphVal != null && cphVal > 0f) {
                        onSecPerCmChanged("%.3f".format(0.5f / cphVal))
                    }
                },
                label = { Text("cm/0.5s") },
                singleLine = true,
                modifier = Modifier.weight(1f)
            )
            OutlinedTextField(
                value = rowLengthCm,
                onValueChange = { onRowLengthChanged(it.filter { c -> c.isDigit() || c == '.' }) },
                label = { Text("Zeile") },
                suffix = { Text("cm") },
                singleLine = true,
                modifier = Modifier.weight(1f)
            )
        }
        val spc = secPerCm.toFloatOrNull() ?: 1.818f
        val rl = rowLengthCm.toFloatOrNull() ?: 19.25f
        val cph = if (spc > 0f) 0.5f / spc else 0f
        Text(
            "= %.1f sec pro Zeile, %.1f mm/sec, %.2f cm pro 0.5s Tick".format(rl * spc, 10f / spc, cph),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
        )
    }

    Spacer(Modifier.height(12.dp))

    // === DARSTELLUNG (Card) ===
    SectionCard(title = "Darstellung") {
        OutlinedTextField(
            value = maxFreqKHz,
            onValueChange = { onMaxFreqChanged(it.filter { c -> c.isDigit() || c == '.' }) },
            label = { Text("Max. Darstellungsfrequenz") },
            suffix = { Text("kHz") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
    }
}
