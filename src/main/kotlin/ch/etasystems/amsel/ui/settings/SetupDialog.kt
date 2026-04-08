package ch.etasystems.amsel.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import ch.etasystems.amsel.data.AppSettings
import java.io.File
import javax.swing.JFileChooser

@Composable
fun SetupDialog(
    initialSettings: AppSettings,
    onComplete: (AppSettings) -> Unit,
    onDismiss: () -> Unit
) {
    val userHome = System.getProperty("user.home")
    var audioDir by remember { mutableStateOf(initialSettings.audioImportDir.ifBlank { "$userHome/Documents/AMSEL/audio" }) }
    var projectDir by remember { mutableStateOf(initialSettings.projectDir.ifBlank { "$userHome/Documents/AMSEL/projekte" }) }
    var exportDir by remember { mutableStateOf(initialSettings.exportDir.ifBlank { "$userHome/Desktop" }) }
    var modelDir by remember { mutableStateOf(initialSettings.modelDir.ifBlank { "$userHome/Documents/AMSEL/models" }) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier.width(600.dp),
            shape = MaterialTheme.shapes.extraLarge,
            tonalElevation = 6.dp
        ) {
            Column(modifier = Modifier.fillMaxWidth().padding(24.dp)) {
                Text(
                    "AMSEL einrichten",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    "Waehle die Ordner fuer deine Arbeit.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                )

                Spacer(Modifier.height(16.dp))

                DirectoryChooserRow(
                    label = "Audio-Import",
                    icon = Icons.Default.FolderOpen,
                    path = audioDir,
                    onPathChanged = { audioDir = it }
                )
                DirectoryChooserRow(
                    label = "Projekte",
                    icon = Icons.Default.Description,
                    path = projectDir,
                    onPathChanged = { projectDir = it }
                )
                DirectoryChooserRow(
                    label = "Exporte",
                    icon = Icons.Default.FileDownload,
                    path = exportDir,
                    onPathChanged = { exportDir = it }
                )
                DirectoryChooserRow(
                    label = "Modelle",
                    icon = Icons.Default.Psychology,
                    path = modelDir,
                    onPathChanged = { modelDir = it }
                )

                Spacer(Modifier.height(24.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = onDismiss) {
                        Text("Abbrechen")
                    }
                    Spacer(Modifier.width(8.dp))
                    Button(
                        onClick = {
                            listOf(audioDir, projectDir, exportDir, modelDir).forEach { dir ->
                                if (dir.isNotBlank()) File(dir).mkdirs()
                            }
                            val updated = initialSettings.copy(
                                audioImportDir = audioDir,
                                projectDir = projectDir,
                                exportDir = exportDir,
                                modelDir = modelDir,
                                setupComplete = true
                            )
                            onComplete(updated)
                        }
                    ) {
                        Icon(Icons.Default.Check, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Fertig")
                    }
                }
            }
        }
    }
}

@Composable
private fun DirectoryChooserRow(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    path: String,
    onPathChanged: (String) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Icon(icon, null, modifier = Modifier.size(24.dp), tint = MaterialTheme.colorScheme.primary)
        Column(modifier = Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.labelMedium)
            Text(
                path.ifBlank { "(nicht gesetzt)" },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                maxLines = 1
            )
        }
        OutlinedButton(
            onClick = {
                val chooser = JFileChooser(if (path.isNotBlank()) File(path) else null).apply {
                    dialogTitle = "$label waehlen"
                    fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
                }
                if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) {
                    onPathChanged(chooser.selectedFile.absolutePath)
                }
            },
            contentPadding = PaddingValues(horizontal = 12.dp)
        ) { Text("Waehlen...") }
    }
}
