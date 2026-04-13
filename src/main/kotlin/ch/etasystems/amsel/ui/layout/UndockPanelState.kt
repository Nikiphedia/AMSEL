package ch.etasystems.amsel.ui.layout

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * State-Holder fuer ein abdockbares Panel.
 *
 * @param title Fenstertitel wenn abgedockt (z.B. "Audiofiles", "Kandidaten")
 * @param initialWidth Initiale Fensterbreite in dp
 * @param initialHeight Initiale Fensterhoehe in dp
 */
class UndockPanelState(
    val title: String,
    val initialWidth: Int = 350,
    val initialHeight: Int = 500
) {
    /** true = Panel ist als eigenes Fenster abgedockt */
    var isUndocked by mutableStateOf(false)
        private set

    /** AWT-Window-Referenz des abgedockten Fensters (null wenn eingedockt). */
    var awtWindow: java.awt.Window? = null

    /** Dockt das Panel ab (oeffnet als Fenster). */
    fun undock() { isUndocked = true }

    /** Dockt das Panel wieder ein (schliesst Fenster, zeigt in Sidebar). */
    fun dock() {
        isUndocked = false
        awtWindow = null
    }

    /** Bringt das abgedockte Fenster in den Vordergrund. */
    fun bringToFront() {
        awtWindow?.toFront()
    }
}
