package ch.etasystems.amsel.core.model

import kotlinx.serialization.Serializable
import kotlin.math.pow

/**
 * Einzelner Eintrag im Audit-Trail (Filter-Protokoll).
 * Dokumentiert jede Aenderung an der Signalverarbeitung fuer wissenschaftliche Reproduzierbarkeit.
 */
@Serializable
data class AuditEntry(
    val timestamp: Long = System.currentTimeMillis(),
    val action: String,
    val details: String
)

/**
 * Breakpoint in der Lautstaerke-Automation.
 * @param timeSec Zeitposition in Sekunden
 * @param gainDb Gain in dB (-60..+6, Float.NEGATIVE_INFINITY = Stille)
 */
@Serializable
data class VolumePoint(val timeSec: Float, val gainDb: Float)

/** Lineare Interpolation des Gains an einer beliebigen Zeitposition. */
fun List<VolumePoint>.gainAtTime(timeSec: Float): Float {
    if (isEmpty()) return 0f
    if (size == 1) return first().gainDb
    // Vor erstem Punkt: Wert des ersten Punkts
    if (timeSec <= first().timeSec) return first().gainDb
    // Nach letztem Punkt: Wert des letzten Punkts
    if (timeSec >= last().timeSec) return last().gainDb
    // Zwischen zwei Punkten: linear interpolieren
    for (i in 0 until size - 1) {
        val a = this[i]
        val b = this[i + 1]
        if (timeSec in a.timeSec..b.timeSec) {
            val t = (timeSec - a.timeSec) / (b.timeSec - a.timeSec)
            return a.gainDb + t * (b.gainDb - a.gainDb)
        }
    }
    return last().gainDb
}

/** Gain in dB → linearer Amplituden-Faktor. Unter -60 dB = Stille. */
fun gainDbToLinear(gainDb: Float): Float {
    if (gainDb <= -60f || gainDb.isInfinite()) return 0f
    return 10f.pow(gainDb / 20f)
}
