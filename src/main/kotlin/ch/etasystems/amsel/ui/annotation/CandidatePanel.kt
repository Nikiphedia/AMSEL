package ch.etasystems.amsel.ui.annotation

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ch.etasystems.amsel.core.annotation.Annotation
import ch.etasystems.amsel.core.annotation.SpeciesCandidate
import ch.etasystems.amsel.data.SpeciesRegistry

/**
 * Zeigt die BirdNET Top-N Kandidaten fuer die aktive Annotation.
 * Erlaubt dem Nutzer, einen Alternativ-Vorschlag zu uebernehmen.
 */
@Composable
fun CandidatePanel(
    annotation: Annotation,
    onAdoptCandidate: (SpeciesCandidate) -> Unit,
    modifier: Modifier = Modifier
) {
    val candidates = annotation.candidates
    if (candidates.isEmpty()) return

    // Aktuelle Art aus dem Label extrahieren (zum Hervorheben)
    val currentSpecies = annotation.label.replace(Regex("\\s*\\(\\d+%\\)\\s*$"), "").trim()

    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
        tonalElevation = 1.dp
    ) {
        Column(modifier = Modifier.padding(8.dp)) {
            // Header
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

            Spacer(modifier = Modifier.height(4.dp))

            // Kandidatenliste (scrollbar, max ~180dp)
            LazyColumn(
                modifier = Modifier.fillMaxWidth().heightIn(max = 200.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                items(candidates) { candidate ->
                    val isCurrentSpecies = candidate.species == currentSpecies
                    CandidateRow(
                        candidate = candidate,
                        isSelected = isCurrentSpecies,
                        onAdopt = { onAdoptCandidate(candidate) }
                    )
                }
            }
        }
    }
}

@Composable
private fun CandidateRow(
    candidate: SpeciesCandidate,
    isSelected: Boolean,
    onAdopt: () -> Unit
) {
    val sciName = candidate.scientificName
    val displayName = SpeciesRegistry.getDisplayName(sciName, "de")
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
            .clickable(enabled = !isSelected, onClick = onAdopt)
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

        // Uebernehmen-Aktion (nur wenn nicht bereits ausgewaehlt)
        if (!isSelected) {
            Text(
                "\u2190",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary
            )
        } else {
            Text(
                "\u2713",
                style = MaterialTheme.typography.labelMedium,
                color = Color(0xFF4CAF50)
            )
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
