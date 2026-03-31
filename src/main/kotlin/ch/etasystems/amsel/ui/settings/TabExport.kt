package ch.etasystems.amsel.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

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
    // Presets
    Text("Presets", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(
            selected = false,
            onClick = {
                onFreqMinChanged("0"); onFreqMaxChanged("16"); onFreqStepChanged("2"); onMaxFreqChanged("16")
            },
            label = { Text("Voegel") }
        )
        FilterChip(
            selected = false,
            onClick = {
                onFreqMinChanged("15"); onFreqMaxChanged("125"); onFreqStepChanged("10"); onMaxFreqChanged("125")
            },
            label = { Text("Fledermaeuse") }
        )
        FilterChip(
            selected = false,
            onClick = {
                onFreqMinChanged("0"); onFreqMaxChanged("50"); onFreqStepChanged("5"); onMaxFreqChanged("50")
            },
            label = { Text("Insekten") }
        )
    }

    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

    // Spektrogramm max. Frequenz
    Text("Spektrogramm max. Frequenz", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
    OutlinedTextField(
        value = maxFreqKHz,
        onValueChange = { onMaxFreqChanged(it.filter { c -> c.isDigit() || c == '.' }) },
        label = { Text("Max. Frequenz") },
        suffix = { Text("kHz") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth()
    )

    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

    // Export Frequenz-Achse
    Text("Export Frequenz-Achse", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = freqMinKHz,
            onValueChange = { onFreqMinChanged(it.filter { c -> c.isDigit() || c == '.' }) },
            label = { Text("Untere Grenze") },
            suffix = { Text("kHz") },
            singleLine = true,
            modifier = Modifier.weight(1f)
        )
        OutlinedTextField(
            value = freqMaxKHz,
            onValueChange = { onFreqMaxChanged(it.filter { c -> c.isDigit() || c == '.' }) },
            label = { Text("Obere Grenze") },
            suffix = { Text("kHz") },
            singleLine = true,
            modifier = Modifier.weight(1f)
        )
    }
    OutlinedTextField(
        value = freqStepKHz,
        onValueChange = { onFreqStepChanged(it.filter { c -> c.isDigit() || c == '.' }) },
        label = { Text("Schrittweite Achse") },
        suffix = { Text("kHz (pro cm)") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth()
    )
    Text(
        "Beispiel: 2 kHz = Hauptstrich alle 1 cm",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
    )

    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

    // Zeitachse
    Text("Zeitachse", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = secPerCm,
            onValueChange = { newVal ->
                val filtered = newVal.filter { c -> c.isDigit() || c == '.' }
                onSecPerCmChanged(filtered)
                // Bidirektional: secPerCm → cmPerHalfSec
                val spcVal = filtered.toFloatOrNull()
                if (spcVal != null && spcVal > 0f) {
                    onCmPerHalfSecChanged("%.2f".format(0.5f / spcVal))
                }
            },
            label = { Text("sec pro cm") },
            suffix = { Text("sec/cm") },
            singleLine = true,
            modifier = Modifier.weight(1f)
        )
        OutlinedTextField(
            value = cmPerHalfSec,
            onValueChange = { newVal ->
                val filtered = newVal.filter { c -> c.isDigit() || c == '.' }
                onCmPerHalfSecChanged(filtered)
                // Bidirektional: cmPerHalfSec → secPerCm
                val cphVal = filtered.toFloatOrNull()
                if (cphVal != null && cphVal > 0f) {
                    onSecPerCmChanged("%.3f".format(0.5f / cphVal))
                }
            },
            label = { Text("cm pro 0.5s Tick") },
            suffix = { Text("cm") },
            singleLine = true,
            modifier = Modifier.weight(1f)
        )
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = rowLengthCm,
            onValueChange = { onRowLengthChanged(it.filter { c -> c.isDigit() || c == '.' }) },
            label = { Text("Zeilenlaenge") },
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
