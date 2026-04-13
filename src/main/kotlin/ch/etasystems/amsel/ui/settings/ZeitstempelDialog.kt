package ch.etasystems.amsel.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import ch.etasystems.amsel.data.RecordingMetadata
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

/** Eintrag fuer einen File in der Kette */
data class ZeitstempelEintrag(
    val fileId: String,
    val fileName: String,
    val durationSec: Float,
    val bestehendesMeta: RecordingMetadata?
)

/**
 * Dialog zur Zeitstempel-Verkettung mehrerer Audio-Dateien.
 * Erstes File: manuell. Folgende: automatisch (Start = Ende vorheriges File).
 * Jedes File kann manuell ueberschrieben werden.
 */
@Composable
fun ZeitstempelDialog(
    eintraege: List<ZeitstempelEintrag>,
    onDismiss: () -> Unit,
    onConfirm: (Map<String, RecordingMetadata>) -> Unit
) {
    // State: Datum/Zeit pro File (Index in eintraege)
    val datumsWerte = remember {
        mutableStateListOf(*eintraege.map { it.bestehendesMeta?.date ?: "" }.toTypedArray())
    }
    val zeitWerte = remember {
        mutableStateListOf(*eintraege.map { it.bestehendesMeta?.time ?: "" }.toTypedArray())
    }
    // Manueller Override: true = User hat Wert selbst eingegeben, nicht auto-berechnet
    val istManuell = remember {
        mutableStateListOf(*BooleanArray(eintraege.size) { it == 0 }.toTypedArray())
    }

    /** Berechnet die Zeitkette ab dem gegebenen Index neu (nur nicht-manuelle Eintraege). */
    fun berechneKette(startIdx: Int) {
        for (i in startIdx until eintraege.size) {
            if (istManuell[i]) continue
            val vorherDatum = datumsWerte.getOrNull(i - 1) ?: continue
            val vorherZeit = zeitWerte.getOrNull(i - 1) ?: continue
            val vorherDauer = eintraege.getOrNull(i - 1)?.durationSec ?: continue
            try {
                val fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
                val vorherStart = LocalDateTime.parse("$vorherDatum $vorherZeit", fmt)
                val neuerStart = vorherStart.plusSeconds(vorherDauer.toLong())
                datumsWerte[i] = neuerStart.toLocalDate().format(DateTimeFormatter.ISO_LOCAL_DATE)
                zeitWerte[i] = neuerStart.toLocalTime().format(DateTimeFormatter.ofPattern("HH:mm"))
            } catch (_: DateTimeParseException) {
                // Vorgaenger hat ungueltiges Format — noop
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Zeitstempel-Kette") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Startzeit des ersten Files eingeben. Folgende Files werden automatisch berechnet.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                )
                HorizontalDivider()

                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.heightIn(max = 400.dp)
                ) {
                    itemsIndexed(eintraege) { idx, eintrag ->
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            // Dateiname
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text(
                                    "${idx + 1}. ${eintrag.fileName}",
                                    style = MaterialTheme.typography.labelMedium,
                                    modifier = Modifier.weight(1f)
                                )
                                val dauerMin = (eintrag.durationSec / 60f).toInt()
                                val dauerSek = (eintrag.durationSec % 60f).toInt()
                                Text(
                                    "${dauerMin}m ${dauerSek}s",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                                )
                            }

                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                // Datum-Feld
                                OutlinedTextField(
                                    value = datumsWerte[idx],
                                    onValueChange = { neu ->
                                        datumsWerte[idx] = neu
                                        istManuell[idx] = true
                                        berechneKette(idx + 1)
                                    },
                                    label = { Text("Datum") },
                                    placeholder = { Text("YYYY-MM-DD") },
                                    singleLine = true,
                                    enabled = idx == 0 || istManuell[idx],
                                    modifier = Modifier.weight(1f),
                                    textStyle = MaterialTheme.typography.bodySmall
                                )
                                // Zeit-Feld
                                OutlinedTextField(
                                    value = zeitWerte[idx],
                                    onValueChange = { neu ->
                                        zeitWerte[idx] = neu
                                        istManuell[idx] = true
                                        berechneKette(idx + 1)
                                    },
                                    label = { Text("Zeit") },
                                    placeholder = { Text("HH:mm") },
                                    singleLine = true,
                                    enabled = idx == 0 || istManuell[idx],
                                    modifier = Modifier.weight(1f),
                                    textStyle = MaterialTheme.typography.bodySmall
                                )
                                // Override-Checkbox (nicht beim ersten File)
                                if (idx > 0) {
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        Checkbox(
                                            checked = istManuell[idx],
                                            onCheckedChange = { checked ->
                                                istManuell[idx] = checked
                                                if (!checked) berechneKette(idx)
                                            }
                                        )
                                        Text(
                                            "Manuell",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                HorizontalDivider()
                // Kette neu berechnen Button
                TextButton(
                    onClick = {
                        for (i in 1 until eintraege.size) {
                            if (!istManuell[i]) berechneKette(i)
                        }
                        berechneKette(1)
                    },
                    modifier = Modifier.align(Alignment.End)
                ) {
                    Text("Kette neu berechnen", style = MaterialTheme.typography.labelMedium)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val result = mutableMapOf<String, RecordingMetadata>()
                eintraege.forEachIndexed { idx, eintrag ->
                    val d = datumsWerte[idx].trim()
                    val t = zeitWerte[idx].trim()
                    if (d.isNotBlank() || t.isNotBlank()) {
                        result[eintrag.fileId] = RecordingMetadata(date = d, time = t)
                    }
                }
                onConfirm(result)
            }) {
                Text("Uebernehmen")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Abbrechen") }
        }
    )
}
