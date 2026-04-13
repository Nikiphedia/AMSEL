package ch.etasystems.amsel.ui.annotation

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.automirrored.filled.Undo
import ch.etasystems.amsel.core.annotation.Annotation
import ch.etasystems.amsel.core.annotation.SpeciesCandidate
import ch.etasystems.amsel.data.SettingsStore
import ch.etasystems.amsel.data.SpeciesRegistry
import androidx.compose.foundation.clickable

/**
 * Zeigt die BirdNET Top-N Kandidaten fuer die aktive Annotation.
 * Erlaubt dem Nutzer, einen Alternativ-Vorschlag zu uebernehmen.
 */
@Composable
fun CandidatePanel(
    annotation: Annotation,
    onAdoptCandidate: (SpeciesCandidate) -> Unit,
    onVerifyCandidate: (SpeciesCandidate) -> Unit = {},
    onRejectCandidate: (SpeciesCandidate) -> Unit = {},
    onResetCandidate: (SpeciesCandidate) -> Unit = {},
    onUncertainCandidate: (SpeciesCandidate) -> Unit = {},
    onAddCandidate: (scientificName: String, displayLabel: String) -> Unit = { _, _ -> },
    onUpdateNotes: (String) -> Unit = {},
    onUpdateLabel: (String) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val candidates = annotation.candidates
    // candidates.isEmpty() ist OK — der "Art hinzufuegen" Button muss trotzdem sichtbar sein

    // Aktuelle Art aus dem Label extrahieren (zum Hervorheben)
    val currentSpecies = annotation.label.replace(Regex("\\s*\\(\\d+%\\)\\s*$"), "").trim()

    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
        tonalElevation = 1.dp
    ) {
        Column(modifier = Modifier.padding(8.dp)) {
            // State fuer "Art hinzufuegen"
            var isAddingSpecies by remember(annotation.id) { mutableStateOf(false) }

            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        "Kandidaten",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        "${formatTimeShort(annotation.startTimeSec)} \u2013 ${formatTimeShort(annotation.endTimeSec)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                    )
                }
                TextButton(
                    onClick = { isAddingSpecies = true },
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                    modifier = Modifier.height(28.dp)
                ) {
                    Text("Art hinzufuegen", style = MaterialTheme.typography.labelSmall)
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

            // Locale fuer Artnamen (wird in CandidateRow + Label-Editor genutzt)
            val settings = remember(annotation.id) { SettingsStore.load() }
            val locale = when (settings.speciesLanguage) {
                "EN" -> "en"
                "SCIENTIFIC" -> "scientific"
                else -> "de"
            }

            // Inline-Editor fuer "Art hinzufuegen"
            if (isAddingSpecies) {
                Spacer(Modifier.height(4.dp))
                var addText by remember { mutableStateOf("") }
                var addSuggestions by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }

                Column(modifier = Modifier.fillMaxWidth()) {
                    OutlinedTextField(
                        value = addText,
                        onValueChange = { newText ->
                            addText = newText
                            addSuggestions = if (newText.length >= 2) {
                                SpeciesRegistry.searchSpecies(newText, locale, maxResults = 5)
                            } else emptyList()
                        },
                        label = { Text("Art suchen") },
                        modifier = Modifier.fillMaxWidth()
                            .onKeyEvent { event ->
                                when {
                                    event.type == KeyEventType.KeyUp && event.key == Key.Escape -> {
                                        isAddingSpecies = false
                                        true
                                    }
                                    else -> false
                                }
                            },
                        textStyle = MaterialTheme.typography.bodySmall,
                        singleLine = true
                    )

                    // Vorschlagsliste
                    if (addSuggestions.isNotEmpty()) {
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            tonalElevation = 4.dp,
                            shape = MaterialTheme.shapes.small
                        ) {
                            Column {
                                addSuggestions.forEach { (sciName, displayName) ->
                                    Text(
                                        "$displayName ($sciName)",
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable {
                                                onAddCandidate(sciName, displayName)
                                                isAddingSpecies = false
                                                addText = ""
                                                addSuggestions = emptyList()
                                            }
                                            .padding(horizontal = 8.dp, vertical = 4.dp),
                                        style = MaterialTheme.typography.bodySmall,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                    }

                    // Abbrechen
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        IconButton(
                            onClick = { isAddingSpecies = false },
                            modifier = Modifier.size(24.dp)
                        ) {
                            Icon(Icons.Default.Close, null, Modifier.size(14.dp))
                        }
                    }
                }
            }

            // Manuelles Label-Editing (Doppelklick auf Kandidat)
            var isEditingLabel by remember(annotation.id) { mutableStateOf(false) }
            var editText by remember(annotation.id) { mutableStateOf(annotation.label) }

            // Kandidatenliste (scrollbar, max ~200dp)
            if (candidates.isNotEmpty()) {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().heightIn(max = 200.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    items(candidates) { candidate ->
                        val isCurrentSpecies = candidate.species == currentSpecies
                        CandidateRow(
                            candidate = candidate,
                            isSelected = isCurrentSpecies,
                            locale = locale,
                            onAdopt = { onAdoptCandidate(candidate) },
                            onDoubleClick = {
                                editText = candidate.species
                                isEditingLabel = true
                            },
                            onVerify = { onVerifyCandidate(candidate) },
                            onReject = { onRejectCandidate(candidate) },
                            onReset = { onResetCandidate(candidate) },
                            onUncertain = { onUncertainCandidate(candidate) }
                        )
                    }
                }
            }

            if (candidates.isEmpty() && !isAddingSpecies) {
                Text(
                    "Keine Kandidaten — Art manuell hinzufuegen",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                    modifier = Modifier.padding(vertical = 4.dp)
                )
            }

            // Inline-Label-Editor mit Artensuche
            if (isEditingLabel) {
                Spacer(Modifier.height(4.dp))
                var suggestions by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }

                Column(modifier = Modifier.fillMaxWidth()) {
                    OutlinedTextField(
                        value = editText,
                        onValueChange = { newText ->
                            editText = newText
                            suggestions = if (newText.length >= 2) {
                                SpeciesRegistry.searchSpecies(newText, locale, maxResults = 5)
                            } else emptyList()
                        },
                        label = { Text("Art eingeben") },
                        modifier = Modifier.fillMaxWidth()
                            .onKeyEvent { event ->
                                when {
                                    event.type == KeyEventType.KeyUp && event.key == Key.Escape -> {
                                        isEditingLabel = false
                                        true
                                    }
                                    else -> false
                                }
                            },
                        textStyle = MaterialTheme.typography.bodySmall,
                        singleLine = true
                    )

                    // Vorschlagsliste
                    if (suggestions.isNotEmpty()) {
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            tonalElevation = 4.dp,
                            shape = MaterialTheme.shapes.small
                        ) {
                            Column {
                                suggestions.forEach { (sciName, displayName) ->
                                    Text(
                                        displayName,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable {
                                                onUpdateLabel(sciName)
                                                isEditingLabel = false
                                                suggestions = emptyList()
                                            }
                                            .padding(horizontal = 8.dp, vertical = 4.dp),
                                        style = MaterialTheme.typography.bodySmall,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                    }

                    // Bestaetigen/Abbrechen Buttons
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        IconButton(
                            onClick = { onUpdateLabel(editText); isEditingLabel = false },
                            modifier = Modifier.size(24.dp)
                        ) {
                            Icon(Icons.Default.Check, null, Modifier.size(14.dp), tint = Color(0xFF4CAF50))
                        }
                        IconButton(
                            onClick = { isEditingLabel = false },
                            modifier = Modifier.size(24.dp)
                        ) {
                            Icon(Icons.Default.Close, null, Modifier.size(14.dp))
                        }
                    }
                }
            }

            // Bemerkungsfeld
            Spacer(Modifier.height(4.dp))
            var notesText by remember(annotation.id) { mutableStateOf(annotation.notes) }
            OutlinedTextField(
                value = notesText,
                onValueChange = { notesText = it },
                label = { Text("Bemerkung") },
                placeholder = { Text("z.B. Fehlbestimmung, Umgebung...", style = MaterialTheme.typography.labelSmall) },
                modifier = Modifier.fillMaxWidth().heightIn(min = 40.dp, max = 80.dp)
                    .onFocusChanged { focusState ->
                        if (!focusState.isFocused && notesText != annotation.notes) {
                            onUpdateNotes(notesText)
                        }
                    },
                textStyle = MaterialTheme.typography.bodySmall,
                maxLines = 3,
                singleLine = false
            )
        }
    }
}

@Composable
private fun CandidateRow(
    candidate: SpeciesCandidate,
    isSelected: Boolean,
    locale: String = "de",
    onAdopt: () -> Unit,
    onDoubleClick: () -> Unit = {},
    onVerify: () -> Unit = {},
    onReject: () -> Unit = {},
    onReset: () -> Unit = {},
    onUncertain: () -> Unit = {}
) {
    val sciName = candidate.scientificName
    val displayName = SpeciesRegistry.getDisplayName(sciName, locale)
    val confPercent = (candidate.confidence * 100).toInt()

    val confColor = when {
        candidate.confidence > 0.7f -> Color(0xFF4CAF50)
        candidate.confidence > 0.3f -> Color(0xFFFFA000)
        else -> Color(0xFFE53935)
    }

    val bgColor = if (isSelected) {
        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
    } else {
        Color.Transparent
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(4.dp))
            .background(bgColor)
            .alpha(if (candidate.rejected) 0.4f else 1f)
            .pointerInput(isSelected) {
                detectTapGestures(
                    onTap = { if (!isSelected) onAdopt() },
                    onDoubleTap = { onDoubleClick() }
                )
            }
            .padding(horizontal = 6.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        // Konfidenz-Balken
        Text(
            "${confPercent}%",
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = confColor,
            modifier = Modifier.width(32.dp)
        )

        // Art-Name
        Column(modifier = Modifier.weight(1f)) {
            Text(
                displayName,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (displayName != sciName.replace('_', ' ')) {
                Text(
                    sciName.replace('_', ' '),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        // Status + Aktionen pro Kandidat
        Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            when {
                candidate.verified -> {
                    Icon(Icons.Default.Check, null, Modifier.size(14.dp), tint = Color(0xFF4CAF50))
                    IconButton(onClick = onReset, modifier = Modifier.size(18.dp)) {
                        Icon(Icons.Default.Close, null, Modifier.size(10.dp),
                            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f))
                    }
                }
                candidate.rejected -> {
                    Icon(Icons.Default.Close, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.error)
                    IconButton(onClick = onReset, modifier = Modifier.size(18.dp)) {
                        Icon(Icons.AutoMirrored.Filled.Undo, null, Modifier.size(10.dp),
                            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f))
                    }
                }
                candidate.uncertain -> {
                    Text("?", style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold, color = Color(0xFFFFA000))
                    IconButton(onClick = onReset, modifier = Modifier.size(18.dp)) {
                        Icon(Icons.AutoMirrored.Filled.Undo, null, Modifier.size(10.dp),
                            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f))
                    }
                }
                else -> {
                    IconButton(onClick = onVerify, modifier = Modifier.size(18.dp)) {
                        Icon(Icons.Default.Check, null, Modifier.size(12.dp), tint = Color(0xFF4CAF50))
                    }
                    IconButton(onClick = onUncertain, modifier = Modifier.size(18.dp)) {
                        Text("?", style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold, color = Color(0xFFFFA000))
                    }
                    IconButton(onClick = onReject, modifier = Modifier.size(18.dp)) {
                        Icon(Icons.Default.Close, null, Modifier.size(12.dp), tint = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
    }
}

private fun formatTimeShort(seconds: Float): String {
    val m = (seconds / 60).toInt()
    val s = (seconds % 60).toInt()
    val frac = ((seconds - seconds.toInt()) * 10).toInt()
    return if (m > 0) "$m:${s.toString().padStart(2, '0')}.$frac"
    else "${s}.${frac}s"
}
