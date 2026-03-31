package ch.etasystems.amsel.ui.compare

import androidx.compose.ui.geometry.Rect
import ch.etasystems.amsel.core.annotation.Annotation
import ch.etasystems.amsel.core.annotation.MatchResult
import ch.etasystems.amsel.core.spectrogram.MelFilterbank
import ch.etasystems.amsel.core.spectrogram.SpectrogramData
import ch.etasystems.amsel.data.SettingsStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Lazy-Zugriff auf Viewport- und Spektrogramm-Daten fuer die Annotations-Erstellung.
 * Vermeidet zirkulaere Abhaengigkeiten zum SpectrogramManager.
 */
data class AnnotationViewportProvider(
    val viewStartSec: () -> Float,
    val viewEndSec: () -> Float,
    val totalDurationSec: () -> Float,
    val zoomedSpectrogramData: () -> SpectrogramData?,
    val overviewSpectrogramData: () -> SpectrogramData?
)

/**
 * Verwaltet Annotationen: CRUD, Selektion, Labels, Edit-Mode, Rubber-Band-Auswahl.
 * Erzeugt keine Annotationen aus BirdNET/Similarity — das ist Aufgabe des ClassificationManagers (13f).
 *
 * Callbacks:
 * - onStateChanged: State-Bridge in CompareUiState aktualisieren
 * - onDirtyChanged: Projekt als geaendert markieren
 * - onZoomToRange: Viewport auf Zeitbereich zoomen (delegiert an SpectrogramManager)
 * - onStatusUpdate: Statuszeile aktualisieren
 */
class AnnotationManager(
    private val viewport: AnnotationViewportProvider,
    private val onStateChanged: () -> Unit = {},
    private val onDirtyChanged: () -> Unit = {},
    private val onZoomToRange: (startSec: Float, endSec: Float) -> Unit = { _, _ -> },
    private val onStatusUpdate: (String) -> Unit = {}
) {
    data class State(
        val annotations: List<Annotation> = emptyList(),
        val activeAnnotationId: String? = null,
        val editingLabelId: String? = null,
        val selection: Rect? = null,
        val selectionMode: Boolean = false,
        val editMode: Boolean = false,
        val selectedMatchResult: MatchResult? = null
    ) {
        val activeAnnotation: Annotation?
            get() = annotations.find { it.id == activeAnnotationId }
    }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private var nextColor = 0

    // ====================================================================
    // Auswahl (Rubber-Band)
    // ====================================================================

    fun updateSelection(rect: Rect?) {
        _state.update { it.copy(selection = rect) }
        onStateChanged()
    }

    fun toggleSelectionMode() {
        _state.update { it.copy(selectionMode = !it.selectionMode) }
        onStateChanged()
    }

    fun clearSelection() {
        _state.update { it.copy(selection = null) }
        onStateChanged()
    }

    // ====================================================================
    // Annotation CRUD
    // ====================================================================

    /**
     * Erstellt eine Annotation aus der aktuellen Rubber-Band-Selektion.
     * Konvertiert normalisierte Pixel-Koordinaten in Zeit/Frequenz via Mel-Mapping.
     */
    fun createAnnotationFromSelection() {
        val sel = _state.value.selection ?: return
        val data = viewport.zoomedSpectrogramData() ?: return
        val viewStart = viewport.viewStartSec()
        val viewEnd = viewport.viewEndSec()

        val duration = viewEnd - viewStart
        val startTime = viewStart + sel.left * duration
        val endTime = viewStart + sel.right * duration

        // Inverse Mel-Mapping: normalisierte Pixel-Position → Hz
        val melMin = MelFilterbank.hzToMel(data.fMin)
        val melMax = MelFilterbank.hzToMel(data.fMax)
        val highFreq = MelFilterbank.melToHz(melMax - sel.top * (melMax - melMin))
        val lowFreq = MelFilterbank.melToHz(melMax - sel.bottom * (melMax - melMin))

        val autoLabel = "Markierung_${_state.value.annotations.size + 1}"
        val annotation = Annotation(
            label = autoLabel,
            startTimeSec = startTime,
            endTimeSec = endTime,
            lowFreqHz = lowFreq.coerceAtLeast(data.fMin),
            highFreqHz = highFreq.coerceAtMost(data.fMax),
            colorIndex = allocateColor()
        )

        _state.update {
            it.copy(
                annotations = it.annotations + annotation,
                activeAnnotationId = annotation.id,
                selection = null,
                selectionMode = false
            )
        }
        onStatusUpdate("Markierung erstellt — Label vergeben oder Vergleichen druecken")
        onStateChanged()
        onDirtyChanged()
    }

    /**
     * Selektiert eine Annotation. Gibt die Annotation zurueck damit der Aufrufer
     * orchestrieren kann (z.B. searchSimilar bei BirdNET-Label).
     * Setzt selectedMatchResult wenn Annotation bereits Ergebnisse hat.
     */
    fun selectAnnotation(annotationId: String): Annotation? {
        _state.update { it.copy(activeAnnotationId = annotationId) }
        val annotation = _state.value.annotations.find { it.id == annotationId }
        if (annotation != null && annotation.matchResults.isNotEmpty()) {
            _state.update { it.copy(selectedMatchResult = annotation.matchResults.first()) }
        }
        onStateChanged()
        return annotation
    }

    /**
     * Zoom auf einen Event mit konfigurierbarem Vor-/Nachlauf aus den Settings.
     * Setzt die Annotation als aktiv und zoomt auf den Bereich.
     */
    fun zoomToEvent(annotationId: String) {
        val annotation = _state.value.annotations.find { it.id == annotationId } ?: return
        val settings = SettingsStore.load()
        val preroll = settings.eventPrerollSec
        val postroll = settings.eventPostrollSec

        _state.update { it.copy(activeAnnotationId = annotationId) }
        onStateChanged()

        val totalDuration = viewport.totalDurationSec()
        val zoomStart = (annotation.startTimeSec - preroll).coerceAtLeast(0f)
        val zoomEnd = (annotation.endTimeSec + postroll).coerceAtMost(totalDuration)
        onZoomToRange(zoomStart, zoomEnd)
    }

    fun deleteAnnotation(annotationId: String) {
        _state.update {
            val newList = it.annotations.filter { a -> a.id != annotationId }
            it.copy(
                annotations = newList,
                activeAnnotationId = if (it.activeAnnotationId == annotationId) {
                    newList.lastOrNull()?.id
                } else it.activeAnnotationId
            )
        }
        onStateChanged()
        onDirtyChanged()
    }

    fun updateAnnotationLabel(annotationId: String, label: String) {
        _state.update {
            it.copy(
                annotations = it.annotations.map { a ->
                    if (a.id == annotationId) a.copy(label = label) else a
                },
                editingLabelId = null
            )
        }
        onStateChanged()
        onDirtyChanged()
    }

    fun startEditingLabel(annotationId: String) {
        _state.update { it.copy(editingLabelId = annotationId) }
        onStateChanged()
    }

    fun stopEditingLabel() {
        _state.update { it.copy(editingLabelId = null) }
        onStateChanged()
    }

    /** Edit-Modus umschalten: Annotations-Raender per Drag anpassbar */
    fun toggleEditMode() {
        _state.update { it.copy(editMode = !it.editMode) }
        onStateChanged()
    }

    /**
     * Aktualisiert die Grenzen einer Annotation.
     * Nur nicht-null Parameter werden uebernommen. Validiert min/max Reihenfolge.
     */
    fun updateAnnotationBounds(
        id: String,
        startTimeSec: Float? = null,
        endTimeSec: Float? = null,
        lowFreqHz: Float? = null,
        highFreqHz: Float? = null
    ) {
        val totalDuration = viewport.totalDurationSec()
        _state.update { state ->
            state.copy(
                annotations = state.annotations.map { ann ->
                    if (ann.id != id) return@map ann
                    val newStart = startTimeSec ?: ann.startTimeSec
                    val newEnd = endTimeSec ?: ann.endTimeSec
                    val newLow = lowFreqHz ?: ann.lowFreqHz
                    val newHigh = highFreqHz ?: ann.highFreqHz
                    // Sicherstellung: start < end, low < high
                    ann.copy(
                        startTimeSec = minOf(newStart, newEnd - 0.01f).coerceAtLeast(0f),
                        endTimeSec = maxOf(newEnd, newStart + 0.01f).coerceAtMost(totalDuration),
                        lowFreqHz = minOf(newLow, newHigh - 1f).coerceAtLeast(0f),
                        highFreqHz = maxOf(newHigh, newLow + 1f)
                    )
                }
            )
        }
        onStateChanged()
        onDirtyChanged()
    }

    /**
     * Sync-Funktion: Referenz-Viewport auf aktive Annotation ausrichten.
     * Setzt den Viewport-Start so dass Annotation-Start am linken Rand liegt
     * und die Viewport-Breite zur Annotations-Dauer passt (mit Rand).
     */
    fun syncReferenceToEvent() {
        val annotation = _state.value.activeAnnotation ?: return
        val totalDuration = viewport.totalDurationSec()
        val margin = annotation.durationSec * 0.15f
        val newStart = (annotation.startTimeSec - margin).coerceAtLeast(0f)
        val newEnd = (annotation.endTimeSec + margin).coerceAtMost(totalDuration)
        onZoomToRange(newStart, newEnd)
    }

    // ====================================================================
    // Gummiband-Auswahl aus Overview
    // ====================================================================

    /**
     * Wird von der OverviewStrip aufgerufen wenn der Nutzer ein Gummiband zieht.
     * Erstellt eine Annotation fuer den gewaehlten Zeitbereich und zoomt darauf.
     */
    fun rubberBandSelect(startSec: Float, endSec: Float) {
        val data = viewport.overviewSpectrogramData() ?: return
        val totalDuration = viewport.totalDurationSec()

        val autoLabel = "Markierung_${_state.value.annotations.size + 1}"
        val annotation = Annotation(
            label = autoLabel,
            startTimeSec = startSec,
            endTimeSec = endSec,
            lowFreqHz = data.fMin,
            highFreqHz = data.fMax,
            colorIndex = allocateColor()
        )

        _state.update {
            it.copy(
                annotations = it.annotations + annotation,
                activeAnnotationId = annotation.id
            )
        }
        onStatusUpdate("Bereich markiert (${(endSec - startSec).format(1)}s) — Vergleichen druecken oder Label vergeben")
        onStateChanged()

        // Zoom auf den ausgewaehlten Bereich (mit etwas Rand)
        val margin = (endSec - startSec) * 0.1f
        onZoomToRange(
            (startSec - margin).coerceAtLeast(0f),
            (endSec + margin).coerceAtMost(totalDuration)
        )
    }

    // ====================================================================
    // Referenz-Sonogramm (Match-Result Selektion)
    // ====================================================================

    fun selectMatchResult(result: MatchResult) {
        _state.update { it.copy(selectedMatchResult = result) }
        onStateChanged()
    }

    fun clearMatchResult() {
        _state.update { it.copy(selectedMatchResult = null) }
        onStateChanged()
    }

    // ====================================================================
    // Annotations-Manipulation (fuer externe Erzeuger: BirdNET, EventDetection)
    // ====================================================================

    /** Fuegt eine einzelne Annotation hinzu. */
    fun addAnnotation(annotation: Annotation, setActive: Boolean = true) {
        _state.update {
            it.copy(
                annotations = it.annotations + annotation,
                activeAnnotationId = if (setActive) annotation.id else it.activeAnnotationId
            )
        }
        onStateChanged()
        onDirtyChanged()
    }

    /** Fuegt mehrere Annotationen hinzu. */
    fun addAnnotations(annotations: List<Annotation>, activeId: String? = null) {
        _state.update {
            it.copy(
                annotations = it.annotations + annotations,
                activeAnnotationId = activeId ?: it.activeAnnotationId
            )
        }
        onStateChanged()
        onDirtyChanged()
    }

    /**
     * Ersetzt Annotationen: bestehende werden gefiltert, neue hinzugefuegt.
     * Verwendet fuer BirdNET-Merge: alte Detektionen im Scan-Bereich entfernen.
     */
    fun mergeAnnotations(
        keep: (Annotation) -> Boolean,
        newAnnotations: List<Annotation>,
        activeId: String? = null
    ) {
        _state.update {
            val existing = it.annotations.filter(keep)
            it.copy(
                annotations = existing + newAnnotations,
                activeAnnotationId = activeId ?: it.activeAnnotationId
            )
        }
        onStateChanged()
        onDirtyChanged()
    }

    /** Aktualisiert matchResults auf einer bestimmten Annotation. */
    fun updateAnnotationMatchResults(annotationId: String, matchResults: List<MatchResult>) {
        _state.update { state ->
            state.copy(
                annotations = state.annotations.map { a ->
                    if (a.id == annotationId) a.copy(matchResults = matchResults) else a
                }
            )
        }
        onStateChanged()
    }

    /** Allokiert die naechste Farbe aus dem 8-Farben-Zyklus. */
    fun allocateColor(): Int {
        val color = nextColor
        nextColor = (nextColor + 1) % 8
        return color
    }

    /** Allokiert eine Sequenz von Farben und gibt den Start-Index zurueck. */
    fun allocateColors(count: Int): Int {
        val start = nextColor
        nextColor = (nextColor + count) % 8
        return start
    }

    // ====================================================================
    // Lifecycle
    // ====================================================================

    fun restoreFromProject(annotations: List<Annotation>) {
        _state.update { State(annotations = annotations) }
        nextColor = 0
        onStateChanged()
    }

    fun reset() {
        _state.update { State() }
        nextColor = 0
        onStateChanged()
    }
}
