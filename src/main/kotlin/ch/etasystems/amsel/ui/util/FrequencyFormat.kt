package ch.etasystems.amsel.ui.util

import kotlin.math.roundToInt

/** Hz → kHz String (z.B. 16000 → "16", 2500 → "2.5") */
fun formatKHz(hz: Int): String {
    val khz = hz / 1000.0
    return if (khz == khz.toLong().toDouble()) {
        khz.toLong().toString()
    } else {
        "%.1f".format(khz)
    }
}

/** kHz String → Hz Int (z.B. "16" → 16000, "2.5" → 2500) */
fun parseKHzToHz(kHz: String, default: Int): Int {
    val value = kHz.toDoubleOrNull() ?: return default
    return (value * 1000).toInt()
}

/** Hz (Float) → Display-String mit optionaler Noten-Angabe (z.B. "2.5k Hz (D#7)") */
fun formatFreq(hz: Float): String {
    val rounded = when {
        hz >= 10000 -> "${(hz / 1000).roundToInt()}k"
        hz >= 1000 -> "${"%.1f".format(hz / 1000)}k"
        else -> "${hz.roundToInt()}"
    }
    val note = nearestNote(hz)
    return if (note != null) "$rounded Hz ($note)" else "$rounded Hz"
}

private fun nearestNote(hz: Float): String? {
    if (hz < 20f || hz > 20000f) return null
    val noteNames = arrayOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")
    val semitones = 12.0 * Math.log(hz / 440.0) / Math.log(2.0)
    val rounded = Math.round(semitones).toInt()
    val noteIndex = ((rounded % 12) + 12 + 9) % 12
    val octave = 4 + (rounded + 9) / 12 - (if ((rounded + 9) % 12 < 0) 1 else 0)
    val cents = Math.abs(semitones - rounded) * 100
    return if (cents < 50 && octave in 0..9) "${noteNames[noteIndex]}$octave" else null
}
