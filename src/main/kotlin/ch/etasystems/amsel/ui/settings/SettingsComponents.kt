package ch.etasystems.amsel.ui.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/** Card-Container fuer eine Settings-Gruppe */
@Composable
fun SectionCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            content()
        }
    }
}

/** Aufklappbarer Bereich fuer selten genutzte Einstellungen */
@Composable
fun ExpandableSection(title: String = "Erweitert", content: @Composable ColumnScope.() -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Icon(
                    if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = if (expanded) "Zuklappen" else "Aufklappen"
                )
            }
            AnimatedVisibility(visible = expanded) {
                Column(
                    modifier = Modifier.padding(top = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    content()
                }
            }
        }
    }
}
