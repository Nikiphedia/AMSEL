package ch.etasystems.amsel.ui.compare

import ch.etasystems.amsel.core.model.VolumePoint
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlin.math.roundToInt

/**
 * Verwaltet die Lautstaerke-Automation (Volume Envelope / Breakpoint-Automation).
 * Isolierter Manager ohne Abhaengigkeiten zu anderen Managern.
 */
class VolumeManager(
    private val onStateChanged: () -> Unit = {},
    private val onEnvelopeModified: () -> Unit = {}
) {

    data class State(
        val volumeEnvelope: List<VolumePoint> = emptyList(),
        val volumeEnvelopeActive: Boolean = false,
        val selectedVolumeIndex: Int = -1
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    /** Punkt hinzufuegen (automatisch nach Zeit sortiert). */
    fun addVolumePoint(timeSec: Float, gainDb: Float) {
        val snappedTime = (timeSec / 0.025f).roundToInt() * 0.025f  // 25ms Raster
        val snappedGain = gainDb.roundToInt().toFloat().coerceIn(-60f, 6f)  // 1dB Raster
        _state.update { state ->
            val points = (state.volumeEnvelope + VolumePoint(snappedTime, snappedGain))
                .sortedBy { it.timeSec }
            state.copy(volumeEnvelope = points, volumeEnvelopeActive = true)
        }
        onEnvelopeModified()
        onStateChanged()
    }

    /** Punkt verschieben (Index-basiert). */
    fun moveVolumePoint(index: Int, timeSec: Float, gainDb: Float) {
        _state.update { state ->
            val points = state.volumeEnvelope.toMutableList()
            if (index in points.indices) {
                val snappedTime = (timeSec / 0.025f).roundToInt() * 0.025f
                val snappedGain = gainDb.roundToInt().toFloat().coerceIn(-60f, 6f)
                points[index] = VolumePoint(snappedTime, snappedGain)
                points.sortBy { it.timeSec }
            }
            state.copy(volumeEnvelope = points)
        }
        onEnvelopeModified()
        onStateChanged()
    }

    /** Punkt loeschen. */
    fun removeVolumePoint(index: Int) {
        _state.update { state ->
            val points = state.volumeEnvelope.toMutableList()
            if (index in points.indices) points.removeAt(index)
            state.copy(
                volumeEnvelope = points,
                volumeEnvelopeActive = state.volumeEnvelopeActive && points.isNotEmpty()
            )
        }
        onStateChanged()
    }

    /** Alle Punkte loeschen. */
    fun clearVolumeEnvelope() {
        _state.update { it.copy(volumeEnvelope = emptyList(), volumeEnvelopeActive = false) }
        onStateChanged()
    }

    /** Volume-Punkt selektieren (blau). -1 = Selektion aufheben. */
    fun selectVolumePoint(index: Int) {
        _state.update { it.copy(selectedVolumeIndex = index) }
    }

    /** Envelope ein-/ausschalten (ohne Punkte zu loeschen). */
    fun toggleVolumeEnvelope() {
        _state.update { it.copy(volumeEnvelopeActive = !it.volumeEnvelopeActive) }
        onEnvelopeModified()
    }

    /** State aus Projektdatei wiederherstellen. */
    fun restoreFromProject(envelope: List<VolumePoint>, active: Boolean) {
        _state.update { it.copy(volumeEnvelope = envelope, volumeEnvelopeActive = active, selectedVolumeIndex = -1) }
    }

    /** State zuruecksetzen. */
    fun reset() {
        _state.value = State()
    }
}
