package ch.etasystems.amsel.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import ch.etasystems.amsel.data.AppSettings
import ch.etasystems.amsel.data.ProjectMetadata
import java.io.File
import javax.swing.JFileChooser

/**
 * Dialog zum Erstellen eines neuen AMSEL-Projekts.
 */
@Composable
fun NewProjectDialog(
    settings: AppSettings,
    onDismiss: () -> Unit,
    onProjectCreated: (projectDir: File, projectName: String, metadata: ProjectMetadata) -> Unit
) {
    val defaultDir = if (settings.projectDir.isNotBlank()) settings.projectDir
        else File(System.getProperty("user.home"), "Documents/AMSEL/projekte").absolutePath

    var projektName by remember { mutableStateOf("") }
    var ueberordner by remember { mutableStateOf(defaultDir) }
    var standort by remember { mutableStateOf(settings.locationName) }
    var bearbeiter by remember { mutableStateOf(settings.operatorName) }
    var geraet by remember { mutableStateOf(settings.deviceName) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Neues Projekt") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = projektName,
                    onValueChange = { projektName = it },
                    label = { Text("Projektname") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = ueberordner,
                        onValueChange = { ueberordner = it },
                        label = { Text("Ueberordner") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = {
                        val chooser = JFileChooser(ueberordner)
                        chooser.fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
                        chooser.dialogTitle = "Projektordner waehlen"
                        if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) {
                            ueberordner = chooser.selectedFile.absolutePath
                        }
                    }) {
                        Text("...")
                    }
                }
                OutlinedTextField(
                    value = standort,
                    onValueChange = { standort = it },
                    label = { Text("Standort (optional)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = bearbeiter,
                    onValueChange = { bearbeiter = it },
                    label = { Text("Bearbeiter") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = geraet,
                    onValueChange = { geraet = it },
                    label = { Text("Geraet") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (projektName.isNotBlank()) {
                        val dir = File(ueberordner, projektName)
                        val meta = ProjectMetadata(
                            location = standort,
                            operator = bearbeiter,
                            device = geraet
                        )
                        onProjectCreated(dir, projektName, meta)
                    }
                },
                enabled = projektName.isNotBlank()
            ) {
                Text("Erstellen")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Abbrechen") }
        }
    )
}
