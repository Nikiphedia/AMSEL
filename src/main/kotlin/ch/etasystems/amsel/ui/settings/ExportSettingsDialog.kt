package ch.etasystems.amsel.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import ch.etasystems.amsel.data.AppSettings
import ch.etasystems.amsel.data.SettingsStore
import ch.etasystems.amsel.ui.util.formatKHz
import ch.etasystems.amsel.ui.util.parseKHzToHz

/**
 * Dialog fuer Export-Einstellungen: Frequenzbereich + Schrittweite.
 * Anzeige in kHz, Speicherung intern in Hz.
 * Presets fuer Voegel, Fledermaeuse und Insekten.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExportSettingsDialog(
    onDismiss: () -> Unit,
    onSettingsChanged: (AppSettings) -> Unit
) {
    val settings = remember { SettingsStore.load() }
    // Anzeige in kHz (intern Hz / 1000)
    var freqMinKHz by remember { mutableStateOf(formatKHz(settings.exportFreqMinHz)) }
    var freqMaxKHz by remember { mutableStateOf(formatKHz(settings.exportFreqMaxHz)) }
    var freqStepKHz by remember { mutableStateOf(formatKHz(settings.exportFreqStepHz)) }
    var maxFreqKHz by remember { mutableStateOf(formatKHz(settings.maxFrequencyHz)) }
    var secPerCm by remember { mutableStateOf("%.2f".format(settings.exportSecPerCm)) }
    var cmPerHalfSec by remember { mutableStateOf("%.2f".format(0.5f / settings.exportSecPerCm)) }
    var rowLengthCm by remember { mutableStateOf("%.1f".format(settings.exportRowLengthCm)) }
    // Geographie (global fuer BirdNET + andere Funktionen)
    var locationName by remember { mutableStateOf(settings.locationName) }
    var latStr by remember { mutableStateOf("%.4f".format(settings.locationLat)) }
    var lonStr by remember { mutableStateOf("%.4f".format(settings.locationLon)) }
    // Event-Klick Vor-/Nachlauf
    var prerollStr by remember { mutableStateOf("%.0f".format(settings.eventPrerollSec)) }
    var postrollStr by remember { mutableStateOf("%.0f".format(settings.eventPostrollSec)) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Export-Einstellungen") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {

                // Presets
                Text("Presets:", style = MaterialTheme.typography.labelMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = false,
                        onClick = {
                            freqMinKHz = "0"; freqMaxKHz = "16"; freqStepKHz = "2"; maxFreqKHz = "16"
                        },
                        label = { Text("Voegel") }
                    )
                    FilterChip(
                        selected = false,
                        onClick = {
                            freqMinKHz = "15"; freqMaxKHz = "125"; freqStepKHz = "10"; maxFreqKHz = "125"
                        },
                        label = { Text("Fledermaeuse") }
                    )
                    FilterChip(
                        selected = false,
                        onClick = {
                            freqMinKHz = "0"; freqMaxKHz = "50"; freqStepKHz = "5"; maxFreqKHz = "50"
                        },
                        label = { Text("Insekten") }
                    )
                }

                HorizontalDivider()

                // Spektrogramm-Frequenzbereich (Anzeige + Berechnung)
                Text("Spektrogramm max. Frequenz:", style = MaterialTheme.typography.labelMedium)
                OutlinedTextField(
                    value = maxFreqKHz,
                    onValueChange = { maxFreqKHz = it.filter { c -> c.isDigit() || c == '.' } },
                    label = { Text("Max. Frequenz") },
                    suffix = { Text("kHz") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                HorizontalDivider()

                // Export-Frequenzbereich
                Text("Export Frequenz-Achse:", style = MaterialTheme.typography.labelMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = freqMinKHz,
                        onValueChange = { freqMinKHz = it.filter { c -> c.isDigit() || c == '.' } },
                        label = { Text("Untere Grenze") },
                        suffix = { Text("kHz") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = freqMaxKHz,
                        onValueChange = { freqMaxKHz = it.filter { c -> c.isDigit() || c == '.' } },
                        label = { Text("Obere Grenze") },
                        suffix = { Text("kHz") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                }
                OutlinedTextField(
                    value = freqStepKHz,
                    onValueChange = { freqStepKHz = it.filter { c -> c.isDigit() || c == '.' } },
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

                HorizontalDivider()

                // Zeitachse
                Text("Zeitachse:", style = MaterialTheme.typography.labelMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = secPerCm,
                        onValueChange = { newVal ->
                            val filtered = newVal.filter { c -> c.isDigit() || c == '.' }
                            secPerCm = filtered
                            val spcVal = filtered.toFloatOrNull()
                            if (spcVal != null && spcVal > 0f) {
                                cmPerHalfSec = "%.2f".format(0.5f / spcVal)
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
                            cmPerHalfSec = filtered
                            val cphVal = filtered.toFloatOrNull()
                            if (cphVal != null && cphVal > 0f) {
                                secPerCm = "%.3f".format(0.5f / cphVal)
                            }
                        },
                        label = { Text("cm pro 0.5s Tick") },
                        suffix = { Text("cm") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                }
                OutlinedTextField(
                    value = rowLengthCm,
                    onValueChange = { rowLengthCm = it.filter { c -> c.isDigit() || c == '.' } },
                    label = { Text("Zeilenlaenge") },
                    suffix = { Text("cm") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                // Info-Zeile mit aktuellen Werten
                val spc = secPerCm.toFloatOrNull() ?: 1.818f
                val rl = rowLengthCm.toFloatOrNull() ?: 19.25f
                val cph = if (spc > 0f) 0.5f / spc else 0f
                Text(
                    "= %.1f sec pro Zeile, %.1f mm/sec, %.2f cm pro 0.5s Tick".format(rl * spc, 10f / spc, cph),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                )

                HorizontalDivider()

                // Event-Klick Vor-/Nachlauf
                Text("Event-Klick Vor-/Nachlauf:", style = MaterialTheme.typography.labelMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = prerollStr,
                        onValueChange = { prerollStr = it.filter { c -> c.isDigit() || c == '.' } },
                        label = { Text("Vorlauf") },
                        suffix = { Text("sec") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = postrollStr,
                        onValueChange = { postrollStr = it.filter { c -> c.isDigit() || c == '.' } },
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

                HorizontalDivider()

                // Geographie (global)
                Text("Standort (fuer BirdNET-Artfilter):", style = MaterialTheme.typography.labelMedium)
                OutlinedTextField(
                    value = locationName,
                    onValueChange = { locationName = it },
                    label = { Text("Standort-Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = latStr,
                        onValueChange = { latStr = it.filter { c -> c.isDigit() || c == '.' || c == '-' } },
                        label = { Text("Breitengrad") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = lonStr,
                        onValueChange = { lonStr = it.filter { c -> c.isDigit() || c == '.' || c == '-' } },
                        label = { Text("Laengengrad") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                }
                Text(
                    "Default: Zuerich (47.4, 8.5). Filtert BirdNET auf regional vorkommende Arten.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                )
            }
        },
        confirmButton = {
            Button(onClick = {
                val fMin = parseKHzToHz(freqMinKHz, 0)
                val fMax = parseKHzToHz(freqMaxKHz, 16000)
                val fStep = parseKHzToHz(freqStepKHz, 2000)
                val mfHz = parseKHzToHz(maxFreqKHz, 16000)

                val spc = secPerCm.toFloatOrNull() ?: 1.818f
                val rl = rowLengthCm.toFloatOrNull() ?: 19.25f

                val lat = latStr.toFloatOrNull() ?: 47.4f
                val lon = lonStr.toFloatOrNull() ?: 8.5f
                val preroll = prerollStr.toFloatOrNull() ?: 10f
                val postroll = postrollStr.toFloatOrNull() ?: 20f
                val updated = settings.copy(
                    exportFreqMinHz = fMin.coerceIn(0, 200000),
                    exportFreqMaxHz = fMax.coerceIn(1000, 200000),
                    exportFreqStepHz = fStep.coerceIn(500, 50000),
                    maxFrequencyHz = mfHz.coerceIn(1000, 200000),
                    exportSecPerCm = spc.coerceIn(0.1f, 20f),
                    exportRowLengthCm = rl.coerceIn(1f, 50f),
                    locationLat = lat.coerceIn(-90f, 90f),
                    locationLon = lon.coerceIn(-180f, 180f),
                    locationName = locationName.trim(),
                    eventPrerollSec = preroll.coerceIn(0f, 60f),
                    eventPostrollSec = postroll.coerceIn(0f, 120f)
                )
                SettingsStore.save(updated)
                onSettingsChanged(updated)
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

