package ch.etasystems.amsel.ui.results

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import ch.etasystems.amsel.core.annotation.MatchResult

/**
 * Ergebnis-Anzeige mit Gruppierung nach Art + Ruftyp.
 * Zeigt den besten Treffer pro Gruppe als Repraesentant,
 * aufklappbar fuer alle Varianten.
 */
@Composable
fun ResultsPanel(
    results: List<MatchResult>,
    isLoading: Boolean,
    onResultClicked: (MatchResult) -> Unit,
    onPlayAudio: ((MatchResult) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    Box(modifier = modifier.fillMaxSize()) {
        when {
            isLoading -> {
                Column(
                    modifier = Modifier.align(Alignment.Center),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    CircularProgressIndicator()
                    Text(
                        "Suche laeuft...",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f)
                    )
                }
            }

            results.isEmpty() -> {
                Text(
                    "Aehnliche Sonogramme erscheinen hier",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.4f),
                    modifier = Modifier.align(Alignment.Center)
                )
            }

            else -> {
                // Nach Art gruppieren, innerhalb nach Ruftyp
                val grouped = remember(results) { groupResults(results) }

                Column {
                    // Header
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                    ) {
                        Text(
                            "${results.size} Treffer in ${grouped.size} Arten",
                            style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    // Gruppierte Liste
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        items(grouped, key = { it.key }) { group ->
                            SpeciesGroup(
                                group = group,
                                onResultClicked = onResultClicked,
                                onPlayAudio = onPlayAudio
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Eine Artgruppe: bester Treffer als Karte, aufklappbar fuer Varianten.
 */
@Composable
private fun SpeciesGroup(
    group: ResultGroup,
    onResultClicked: (MatchResult) -> Unit,
    onPlayAudio: ((MatchResult) -> Unit)? = null
) {
    var expanded by remember { mutableStateOf(false) }

    Column {
        // Haupt-Karte (bester Treffer der Gruppe)
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Top
        ) {
            // Ergebnis-Karte
            ResultCard(
                result = group.best,
                onClick = { onResultClicked(group.best) },
                onPlayAudio = onPlayAudio,
                modifier = Modifier.weight(1f)
            )

            // Gruppen-Info Seitenleiste
            if (group.variants.size > 1) {
                Column(
                    modifier = Modifier
                        .width(60.dp)
                        .clickable { expanded = !expanded }
                        .padding(4.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    // Anzahl Badge
                    Surface(
                        shape = MaterialTheme.shapes.small,
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)
                    ) {
                        Text(
                            "${group.variants.size}x",
                            style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            color = MaterialTheme.colorScheme.primary
                        )
                    }

                    // Ruftypen als Chips
                    for (type in group.types.take(3)) {
                        Text(
                            type,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                            maxLines = 1
                        )
                    }
                    if (group.types.size > 3) {
                        Text(
                            "+${group.types.size - 3}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                        )
                    }

                    // Expand Icon
                    Icon(
                        if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = if (expanded) "Zuklappen" else "Alle anzeigen",
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                    )
                }
            }
        }

        // Aufgeklappte Varianten (ohne den besten, der ist schon oben)
        AnimatedVisibility(visible = expanded) {
            Column(
                modifier = Modifier.padding(start = 16.dp, top = 4.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                for (variant in group.variants.drop(1)) {
                    ResultCard(
                        result = variant,
                        onClick = { onResultClicked(variant) },
                        onPlayAudio = onPlayAudio
                    )
                }
            }
        }
    }
}

// ================================================================
// Gruppierungs-Logik
// ================================================================

private data class ResultGroup(
    val key: String,               // scientificName
    val best: MatchResult,         // Hoechste Similarity
    val variants: List<MatchResult>, // Alle Treffer, sortiert nach Similarity
    val types: List<String>        // Vorkommende Ruftypen ("song", "call", ...)
)

/**
 * Gruppiert Ergebnisse nach Art.
 * Innerhalb jeder Art: sortiert nach Similarity absteigend.
 * Gruppen untereinander: sortiert nach bester Similarity.
 */
private fun groupResults(results: List<MatchResult>): List<ResultGroup> {
    return results
        .groupBy { it.scientificName }
        .map { (name, entries) ->
            val sorted = entries.sortedByDescending { it.similarity }
            val types = entries.map { it.type }
                .filter { it.isNotBlank() }
                .distinct()
                .sorted()

            ResultGroup(
                key = name,
                best = sorted.first(),
                variants = sorted,
                types = types
            )
        }
        .sortedByDescending { it.best.similarity }
}
