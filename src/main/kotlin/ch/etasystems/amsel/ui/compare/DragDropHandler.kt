package ch.etasystems.amsel.ui.compare

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import java.awt.Component
import java.awt.Container
import java.awt.Window
import java.awt.datatransfer.DataFlavor
import java.awt.dnd.*
import java.io.File

/** Audio-Datei-Endungen die per Drag & Drop akzeptiert werden */
private val AUDIO_EXTENSIONS = setOf("wav", "mp3", "flac", "ogg", "m4a", "aac")

/**
 * Installiert AWT-Drag&Drop auf dem gesamten Fenster (rekursiv auf alle Kinder-Komponenten).
 *
 * @param awtWindow Das AWT-Window (aus LocalWindow.current / ComposeWindow)
 * @param hasAudio true wenn bereits eine Audio-Datei geladen ist (steuert ob Drop als Import oder Compare gilt)
 * @param onImportAudio Callback fuer erste Audio-Datei (Hauptdatei laden)
 * @param onImportCompare Callback fuer zweite Audio-Datei (Vergleichsdatei laden)
 * @param onImportImage Callback fuer Sonogramm-Bild (PNG/JPG)
 */
@Composable
internal fun DragDropHandler(
    awtWindow: Window?,
    hasAudio: () -> Boolean,
    onImportAudio: (File) -> Unit,
    onImportCompare: (File) -> Unit,
    onImportImage: (File) -> Unit
) {
    DisposableEffect(awtWindow) {
        if (awtWindow == null) {
            onDispose { }
        } else {
            val dropListener = object : DropTargetAdapter() {
                override fun dragOver(dtde: DropTargetDragEvent) {
                    if (dtde.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) {
                        dtde.acceptDrag(DnDConstants.ACTION_COPY)
                    } else {
                        dtde.rejectDrag()
                    }
                }

                @Suppress("UNCHECKED_CAST")
                override fun drop(dtde: DropTargetDropEvent) {
                    try {
                        dtde.acceptDrop(DnDConstants.ACTION_COPY)
                        val transferable = dtde.transferable
                        if (transferable.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) {
                            val files = transferable.getTransferData(DataFlavor.javaFileListFlavor) as List<File>

                            for (file in files) {
                                val ext = file.extension.lowercase()
                                if (ext in AUDIO_EXTENSIONS) {
                                    if (!hasAudio()) {
                                        onImportAudio(file)
                                    } else {
                                        onImportCompare(file)
                                    }
                                    break
                                } else if (ext in setOf("png", "jpg", "jpeg")) {
                                    onImportImage(file)
                                    break
                                }
                            }
                            dtde.dropComplete(true)
                        } else {
                            dtde.dropComplete(false)
                        }
                    } catch (_: Exception) {
                        dtde.dropComplete(false)
                    }
                }
            }

            // DropTarget auf ALLE Komponenten in der Fenster-Hierarchie setzen
            // ComposeWindow hat verschachtelte Layer die Events konsumieren
            val targets = mutableListOf<DropTarget>()

            fun installOnComponent(comp: Component) {
                val dt = DropTarget(comp, dropListener)
                comp.dropTarget = dt
                targets.add(dt)
                if (comp is Container) {
                    for (i in 0 until comp.componentCount) {
                        installOnComponent(comp.getComponent(i))
                    }
                }
            }

            installOnComponent(awtWindow)

            onDispose {
                for (dt in targets) {
                    dt.component?.dropTarget = null
                }
            }
        }
    }
}
