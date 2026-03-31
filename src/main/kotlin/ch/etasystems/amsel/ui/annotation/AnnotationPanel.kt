package ch.etasystems.amsel.ui.annotation

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.*
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ch.etasystems.amsel.core.annotation.ANNOTATION_COLORS
import ch.etasystems.amsel.core.annotation.Annotation
import ch.etasystems.amsel.core.i18n.SpeciesTranslations
import ch.etasystems.amsel.data.SpeciesRegistry

/**
 * Seitenleiste mit Annotationen GRUPPIERT nach Art.
 * Jede Gruppe ist ausklappbar und zeigt einzelne Events.
 * Unterart-Komplexe werden mit Warnung markiert.
 */
@Composable
fun AnnotationPanel(
    annotations: List<Annotation>,
    activeAnnotationId: String?,
    editingLabelId: String?,
    onSelect: (String) -> Unit,
    onDelete: (String) -> Unit,
    onUpdateLabel: (String, String) -> Unit,
    onStartEdit: (String) -> Unit,
    onStopEdit: () -> Unit,
    onZoomToEvent: ((Annotation) -> Unit)? = null,
    speciesLanguage: SpeciesTranslations.Language = SpeciesTranslations.Language.DE,
    showScientificNames: Boolean = true,
    modifier: Modifier = Modifier
) {
    // Gruppen aufbauen: BirdNET-Detektionen nach Art, manuelle unter "Manuelle Markierungen"
    val groups = remember(annotations) {
        annotations
            .groupBy { if (it.isBirdNetDetection) extractSpeciesKey(it.label) else "Manuelle Markierungen" }
            .map { (speciesKey, events) ->
                val maxConf = events.maxOfOrNull { extractConfidence(it.label) } ?: 0f
                val color = events.first().colorIndex
                SpeciesGroup(speciesKey, events.sortedBy { it.startTimeSec }, maxConf, color)
            }
            .sortedByDescending { it.maxConfidence }
    }

    // Expanded State pro Gruppe
    val expandedGroups = remember { mutableStateMapOf<String, Boolean>() }

    Surface(
        modifier = modifier.fillMaxHeight(),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Header
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
            ) {
                Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
                    Text(
                        "${groups.size} Arten, ${annotations.size} Events",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    // Referenz-Sonogramme Info fuer aktive Annotation
                    val activeAnn = annotations.find { it.id == activeAnnotationId }
                    if (activeAnn != null && activeAnn.matchResults.isNotEmpty()) {
                        Text(
                            "${activeAnn.matchResults.size} Referenz-Sonogramme",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color(0xFFFFEB3B)
                        )
                    }
                }
            }

            if (annotations.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "BirdNET Scan starten oder Region markieren",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                        modifier = Modifier.padding(16.dp)
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    contentPadding = PaddingValues(4.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    for (group in groups) {
                        val isExpanded = expandedGroups[group.speciesKey] ?: (groups.size <= 3)

                        // Gruppen-Header
                        item(key = "header_${group.speciesKey}") {
                            GroupHeader(
                                group = group,
                                isExpanded = isExpanded,
                                speciesLanguage = speciesLanguage,
                                showScientificNames = showScientificNames,
                                onClick = {
                                    expandedGroups[group.speciesKey] = !isExpanded
                                }
                            )
                        }

                        // Einzelne Events (wenn aufgeklappt)
                        if (isExpanded) {
                            items(group.events, key = { it.id }) { annotation ->
                                AnnotationItem(
                                    annotation = annotation,
                                    isActive = annotation.id == activeAnnotationId,
                                    isEditing = annotation.id == editingLabelId,
                                    onClick = {
                                        onSelect(annotation.id)
                                        onZoomToEvent?.invoke(annotation)
                                    },
                                    onDelete = { onDelete(annotation.id) },
                                    onUpdateLabel = { label -> onUpdateLabel(annotation.id, label) },
                                    onStartEdit = { onStartEdit(annotation.id) },
                                    onStopEdit = onStopEdit
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Gruppen-Header: Art-Name + Anzahl + Konfidenz + Unterart-Warnung */
@Composable
private fun GroupHeader(
    group: SpeciesGroup,
    isExpanded: Boolean,
    speciesLanguage: SpeciesTranslations.Language,
    showScientificNames: Boolean,
    onClick: () -> Unit
) {
    val color = Color(ANNOTATION_COLORS[group.colorIndex % ANNOTATION_COLORS.size])

    // Art-Name: SpeciesRegistry (192 Taxa) zuerst, dann SpeciesTranslations als Fallback
    val sciName = group.speciesKey.substringBefore("_").trim()
    val locale = when (speciesLanguage) {
        SpeciesTranslations.Language.DE -> "de"
        SpeciesTranslations.Language.EN -> "en"
        SpeciesTranslations.Language.SCIENTIFIC -> "scientific"
    }
    val registryName = if (sciName.contains(" ")) SpeciesRegistry.getDisplayName(sciName, locale) else null
    val hasRegistryName = registryName != null &&
        registryName != sciName.replace('_', ' ') &&
        registryName != sciName
    val displayName = if (hasRegistryName) {
        if (showScientificNames) "$registryName ($sciName)" else registryName!!
    } else {
        SpeciesTranslations.translate(group.speciesKey, speciesLanguage, showScientificNames)
    }

    val englishName = group.speciesKey.substringAfter("_").trim()
    val hasSubspecies = SpeciesTranslations.hasSubspeciesComplex(englishName)

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() },
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
        shape = MaterialTheme.shapes.small
    ) {
        Column(modifier = Modifier.padding(8.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                // Farbpunkt
                Box(
                    modifier = Modifier
                        .size(14.dp)
                        .clip(CircleShape)
                        .background(color)
                )

                // Art-Name
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        displayName,
                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 2
                    )
                }

                // Anzahl + Konfidenz
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        "${group.events.size}x",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        "${(group.maxConfidence * 100).toInt()}%",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (group.maxConfidence > 0.7f) Color(0xFF4CAF50)
                        else if (group.maxConfidence > 0.3f) Color(0xFFFFA000)
                        else Color(0xFFE53935)
                    )
                }

                // Auf-/Zuklappen
                Icon(
                    if (isExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // Unterart-Warnung: nur bei taxonomisch schwierigen Komplexen
            // UND nur wenn die Art mit genuegend Konfidenz erkannt wurde
            if (hasSubspecies && group.maxConfidence > 0.3f) {
                val subspecies = SpeciesTranslations.getSubspeciesInfo(englishName)
                Surface(
                    modifier = Modifier.padding(top = 4.dp).fillMaxWidth(),
                    shape = MaterialTheme.shapes.extraSmall,
                    color = Color(0xFFFFF3E0)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            "\u26A0",
                            fontSize = 12.sp
                        )
                        Text(
                            "Unterart pruefen: ${subspecies?.joinToString(", ") { it.germanName } ?: ""}",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color(0xFFE65100),
                            maxLines = 2
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AnnotationItem(
    annotation: Annotation,
    isActive: Boolean,
    isEditing: Boolean,
    onClick: () -> Unit,
    onDelete: () -> Unit,
    onUpdateLabel: (String) -> Unit,
    onStartEdit: () -> Unit,
    onStopEdit: () -> Unit
) {
    val color = Color(ANNOTATION_COLORS[annotation.colorIndex % ANNOTATION_COLORS.size])
    val bgColor = if (isActive) {
        MaterialTheme.colorScheme.surfaceVariant
    } else {
        MaterialTheme.colorScheme.surface
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp)  // Eingerueckt unter Gruppen-Header
            .pointerInput(Unit) {
                detectTapGestures(
                    onDoubleTap = { onStartEdit() },
                    onTap = { onClick() }
                )
            },
        colors = CardDefaults.cardColors(containerColor = bgColor),
        border = if (isActive) {
            androidx.compose.foundation.BorderStroke(2.dp, color)
        } else null
    ) {
        Row(
            modifier = Modifier.padding(6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            // Farbstrich
            Box(
                modifier = Modifier
                    .width(3.dp)
                    .height(32.dp)
                    .background(color, MaterialTheme.shapes.extraSmall)
            )

            Column(modifier = Modifier.weight(1f)) {
                // Zeitbereich
                Text(
                    "${formatTime(annotation.startTimeSec)} - ${formatTime(annotation.endTimeSec)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface
                )
                // Frequenzbereich
                Text(
                    "${"%.1f".format(annotation.lowFreqHz / 1000f)} - ${"%.1f".format(annotation.highFreqHz / 1000f)} kHz",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                )
                // Konfidenz
                val conf = extractConfidence(annotation.label)
                if (conf > 0f) {
                    Text(
                        "${(conf * 100).toInt()}%",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (conf > 0.7f) Color(0xFF4CAF50)
                        else if (conf > 0.3f) Color(0xFFFFA000)
                        else Color(0xFFE53935)
                    )
                }
                // Label anzeigen (Name der Markierung)
                if (isEditing) {
                    EditableLabel(
                        initialText = annotation.label,
                        onConfirm = onUpdateLabel,
                        onCancel = onStopEdit,
                        modifier = Modifier.fillMaxWidth()
                    )
                } else {
                    Text(
                        annotation.label.ifEmpty { "Ohne Label" },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            // Loeschen
            IconButton(
                onClick = onDelete,
                modifier = Modifier.size(20.dp)
            ) {
                Icon(
                    Icons.Default.Close,
                    contentDescription = "Loeschen",
                    modifier = Modifier.size(12.dp),
                    tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                )
            }
        }
    }
}

@Composable
private fun EditableLabel(
    initialText: String,
    onConfirm: (String) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier
) {
    var text by remember { mutableStateOf(initialText) }
    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

    BasicTextField(
        value = text,
        onValueChange = { text = it },
        modifier = modifier
            .focusRequester(focusRequester)
            .onKeyEvent { event ->
                when {
                    event.type == KeyEventType.KeyUp && event.key == Key.Enter -> {
                        onConfirm(text)
                        true
                    }
                    event.type == KeyEventType.KeyUp && event.key == Key.Escape -> {
                        onCancel()
                        true
                    }
                    else -> false
                }
            },
        textStyle = TextStyle(
            color = MaterialTheme.colorScheme.onSurface,
            fontSize = 14.sp
        ),
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        singleLine = true,
        decorationBox = { innerTextField ->
            Box(
                modifier = Modifier
                    .background(
                        MaterialTheme.colorScheme.surfaceVariant,
                        MaterialTheme.shapes.extraSmall
                    )
                    .padding(horizontal = 6.dp, vertical = 2.dp)
            ) {
                if (text.isEmpty()) {
                    Text(
                        "Label eingeben...",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
                    )
                }
                innerTextField()
            }
        }
    )
}

// ═══ Hilfs-Datenklassen und -Funktionen ═══

private data class SpeciesGroup(
    val speciesKey: String,       // BirdNET Label ohne Konfidenz (z.B. "Fringilla coelebs_Common Chaffinch")
    val events: List<Annotation>,
    val maxConfidence: Float,
    val colorIndex: Int
)

/** Extrahiert den Art-Key aus einem Annotation-Label (ohne Konfidenz-Prozent).
 *  Manuelle Markierungen (Markierung_1 etc.) werden unter "Manuelle Markierungen" gruppiert. */
private fun extractSpeciesKey(label: String): String {
    val cleaned = label.replace(Regex("\\s*\\(\\d+%\\)\\s*$"), "").trim()
    // Manuelle Markierungen zusammenfassen
    if (cleaned.startsWith("Markierung") || cleaned.startsWith("Zoom-Bereich")) {
        return "Manuelle Markierungen"
    }
    return cleaned
}

/** Extrahiert die Konfidenz (0..1) aus einem Label wie "Art (96%)" */
private fun extractConfidence(label: String): Float {
    val match = Regex("\\((\\d+)%\\)").find(label) ?: return 0f
    return match.groupValues[1].toFloatOrNull()?.div(100f) ?: 0f
}

private fun formatTime(seconds: Float): String {
    val totalSec = seconds.toInt()
    val m = totalSec / 60
    val s = totalSec % 60
    // Millisekunden-Praezision (3 Dezimalstellen) fuer wissenschaftliche Genauigkeit
    val millis = ((seconds - totalSec) * 1000).toInt().coerceIn(0, 999)
    val fracStr = millis.toString().padStart(3, '0')
    return if (m > 0) "$m:${s.toString().padStart(2, '0')}.$fracStr"
    else "${s}.${fracStr}s"
}
