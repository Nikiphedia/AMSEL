package ch.etasystems.amsel.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
internal fun TabAllgemein(
    locationName: String,
    onLocationNameChanged: (String) -> Unit,
    latStr: String,
    onLatChanged: (String) -> Unit,
    lonStr: String,
    onLonChanged: (String) -> Unit,
    speciesLanguage: String,
    onLanguageChanged: (String) -> Unit,
    showScientificNames: Boolean,
    onShowScientificChanged: (Boolean) -> Unit,
    apiKey: String,
    onApiKeyChanged: (String) -> Unit,
    operatorName: String = "",
    onOperatorNameChanged: (String) -> Unit = {},
    deviceName: String = "",
    onDeviceNameChanged: (String) -> Unit = {}
) {
    // Standort
    Text("Standort", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
    OutlinedTextField(
        value = locationName,
        onValueChange = onLocationNameChanged,
        label = { Text("Standort-Name") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth()
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = latStr,
            onValueChange = { onLatChanged(it.filter { c -> c.isDigit() || c == '.' || c == '-' }) },
            label = { Text("Breitengrad") },
            singleLine = true,
            modifier = Modifier.weight(1f)
        )
        OutlinedTextField(
            value = lonStr,
            onValueChange = { onLonChanged(it.filter { c -> c.isDigit() || c == '.' || c == '-' }) },
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

    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

    // Sprache
    Text("Sprache fuer Art-Namen", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(
            selected = speciesLanguage == "DE",
            onClick = { onLanguageChanged("DE") },
            label = { Text("Deutsch") }
        )
        FilterChip(
            selected = speciesLanguage == "EN",
            onClick = { onLanguageChanged("EN") },
            label = { Text("English") }
        )
        FilterChip(
            selected = speciesLanguage == "SCIENTIFIC",
            onClick = { onLanguageChanged("SCIENTIFIC") },
            label = { Text("Wissenschaftlich") }
        )
    }

    // Wissenschaftliche Namen
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Checkbox(
            checked = showScientificNames,
            onCheckedChange = onShowScientificChanged
        )
        Text("Wissenschaftliche Namen immer anzeigen", style = MaterialTheme.typography.bodyMedium)
    }

    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

    // Bearbeiter und Geraet (fuer Export-Metadaten)
    Text("Export-Metadaten", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
    OutlinedTextField(
        value = operatorName,
        onValueChange = onOperatorNameChanged,
        label = { Text("Bearbeiter-Name") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth()
    )
    OutlinedTextField(
        value = deviceName,
        onValueChange = onDeviceNameChanged,
        label = { Text("Aufnahmegeraet") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth()
    )
    Text(
        "Werden in PNG-Metadaten eingebettet (AMSEL:Operator, AMSEL:Device).",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
    )

    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

    // API-Key
    Text("Xeno-Canto API-Key", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
    Text(
        "Die Xeno-Canto API v3 braucht einen API-Key (kostenlos).\n" +
        "1. Account erstellen: xeno-canto.org/registration\n" +
        "2. Key abrufen: xeno-canto.org/account",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
    )
    OutlinedTextField(
        value = apiKey,
        onValueChange = { onApiKeyChanged(it.trim()) },
        label = { Text("API-Key") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
        placeholder = { Text("xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx") }
    )
    if (apiKey.isNotBlank() && apiKey.length < 10) {
        Text(
            "Key sieht zu kurz aus",
            style = MaterialTheme.typography.labelSmall,
            color = Color(0xFFF44336)
        )
    }
}
