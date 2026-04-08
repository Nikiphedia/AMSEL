package ch.etasystems.amsel.core.annotation

import kotlinx.serialization.Serializable
import java.util.UUID

/**
 * Eine Annotation markiert einen Bereich im Sonogramm.
 * Speichert Zeit- und Frequenzbereich plus Label und Vergleichsergebnisse.
 */
@Serializable
data class Annotation(
    val id: String = UUID.randomUUID().toString(),
    val label: String = "",
    // Herkunft: true = BirdNET-Erkennung, false = manuell erstellt
    val isBirdNetDetection: Boolean = false,
    // Zeitbereich in Sekunden (absolut, relativ zur Gesamtaufnahme)
    val startTimeSec: Float,
    val endTimeSec: Float,
    // Frequenzbereich in Hz
    val lowFreqHz: Float,
    val highFreqHz: Float,
    // Vergleichsergebnis (nach "Vergleichen")
    val matchResults: List<MatchResult> = emptyList(),
    // Farbe (Index in Palette)
    val colorIndex: Int = 0,
    // BirdNET Top-N Kandidaten fuer diesen Chunk (Alternative Artbestimmungen)
    val candidates: List<SpeciesCandidate> = emptyList(),
    // Freitext-Bemerkung (z.B. bei Fehlbestimmung: "BirdNET sagt Kohlmeise, ist aber Blaumeise")
    val notes: String = ""
) {
    val durationSec: Float get() = endTimeSec - startTimeSec
    val freqRangeHz: Float get() = highFreqHz - lowFreqHz

    /** Mindestens ein Kandidat wurde verifiziert */
    val verified: Boolean get() = candidates.any { it.verified }
    /** Alle Kandidaten wurden abgelehnt (oder keine vorhanden und Chunk manuell abgelehnt) */
    val rejected: Boolean get() = candidates.isNotEmpty() && candidates.all { it.rejected }
    /** Chunk wurde noch nicht vollstaendig bearbeitet */
    val isPending: Boolean get() = !verified && !rejected
}

/**
 * BirdNET-Kandidat pro Chunk: eine moegliche Art-Erkennung mit Konfidenz.
 * Wird auf der Annotation gespeichert um Top-N Alternativ-Vorschlaege anzuzeigen.
 */
@Serializable
data class SpeciesCandidate(
    val species: String,           // Voll-Label: "Parus_major_Great Tit"
    val scientificName: String,    // "Parus major"
    val confidence: Float,         // 0.0 - 1.0
    val verified: Boolean = false,
    val rejected: Boolean = false,
    /** Wer hat verifiziert/abgelehnt (Operator-Name aus Settings) */
    val verifiedBy: String = "",
    /** Wann verifiziert/abgelehnt (Epoch-Millis, 0 = nicht gesetzt) */
    val verifiedAt: Long = 0L
)

/**
 * Ergebnis eines Vergleichs mit Xeno-Canto.
 */
@Serializable
data class MatchResult(
    val recordingId: String,
    val species: String,
    val scientificName: String,
    val sonogramUrl: String,
    val audioUrl: String,
    val quality: String,
    val country: String,
    val similarity: Float,  // 0..1
    val type: String = ""   // "call", "song", etc.
)

/** Farbpalette für Annotationen */
val ANNOTATION_COLORS = listOf(
    0xFFFF6B6B.toInt(),  // Rot
    0xFF4ECDC4.toInt(),  // Türkis
    0xFFFFE66D.toInt(),  // Gelb
    0xFFA8E6CF.toInt(),  // Mintgrün
    0xFFFF8A5C.toInt(),  // Orange
    0xFF6C5CE7.toInt(),  // Violett
    0xFFFF85A1.toInt(),  // Rosa
    0xFF00B4D8.toInt(),  // Hellblau
)
