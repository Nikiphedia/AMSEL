package ch.etasystems.amsel.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import ch.etasystems.amsel.data.RecordingMetadata
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/**
 * Dialog zum Zuweisen von Aufnahme-Metadaten zu einer importierten Audio-Datei.
 *
 * @param fileName Name der importierten Datei (fuer Anzeige)
 * @param onDismiss Abbrechen (keine Metadaten zuweisen, RecordingMetadata bleibt null)
 * @param onConfirm Bestaetigen mit ausgefuellten Metadaten
 */
@Composable
fun AudioMetadataDialog(
    fileName: String,
    onDismiss: () -> Unit,
    onConfirm: (RecordingMetadata) -> Unit
) {
    // States
    var date by remember { mutableStateOf(LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE)) }
    var time by remember { mutableStateOf(LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm"))) }
    var isPirolFile by remember { mutableStateOf(false) }
    var latitude by remember { mutableStateOf("") }
    var longitude by remember { mutableStateOf("") }
    var altitude by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Aufnahme-Metadaten") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    fileName,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )

                // Datum
                OutlinedTextField(
                    value = date,
                    onValueChange = { date = it },
                    label = { Text("Datum (YYYY-MM-DD)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                // Uhrzeit
                OutlinedTextField(
                    value = time,
                    onValueChange = { time = it },
                    label = { Text("Startzeit (HH:mm)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                // Pirol-Checkbox
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = isPirolFile,
                        onCheckedChange = { isPirolFile = it }
                    )
                    Text("Pirol-Datei (mit GPS-Daten)", style = MaterialTheme.typography.bodyMedium)
                }

                // GPS-Felder (nur wenn Pirol)
                if (isPirolFile) {
                    OutlinedTextField(
                        value = latitude,
                        onValueChange = { latitude = it },
                        label = { Text("Breitengrad (z.B. 47.3769)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = longitude,
                        onValueChange = { longitude = it },
                        label = { Text("Laengengrad (z.B. 8.5417)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = altitude,
                        onValueChange = { altitude = it },
                        label = { Text("Hoehe (m, optional)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onConfirm(RecordingMetadata(
                    date = date.trim(),
                    time = time.trim(),
                    isPirolFile = isPirolFile,
                    latitude = latitude.toDoubleOrNull() ?: 0.0,
                    longitude = longitude.toDoubleOrNull() ?: 0.0,
                    altitude = altitude.toDoubleOrNull() ?: 0.0
                ))
            }) {
                Text("Zuweisen")
            }
        },
        dismissButton = {
            Row {
                TextButton(onClick = onDismiss) {
                    Text("Ueberspringen")
                }
            }
        }
    )
}
