package ch.etasystems.amsel.core.annotation

import kotlinx.serialization.Serializable
import java.util.Locale

/**
 * Ortung und Zusatzangaben eines Rufs aus einer Sitta-Tabelle (Raven-Format).
 *
 * Die Werte bleiben als Text unter dem Spaltennamen der Tabelle erhalten, genau wie sie
 * das Geraet geschrieben hat. So geht beim Hin und Zurueck (Import, Export) nichts verloren,
 * auch keine Nachkommastelle. Leere Zellen werden nicht gespeichert.
 */
@Serializable
data class OrtungsDaten(val spalten: Map<String, String> = emptyMap()) {

    private fun zahl(name: String): Float? = spalten[name]?.replace(',', '.')?.toFloatOrNull()

    private fun f(muster: String, wert: Float): String = String.format(Locale.US, muster, wert)

    /** Tier-Nummer, z.B. "T3157". */
    val tier: String? get() = spalten[TIER]
    val kanal: Int? get() = spalten[KANAL]?.toIntOrNull()
    val entfernungM: Float? get() = zahl(ENTFERNUNG)
    val sigmaEntfernungM: Float? get() = zahl(SIGMA_ENTFERNUNG)
    /** Richtung zum Tier in Grad. */
    val kursGrad: Float? get() = zahl(KURS)
    val hoehenwinkelGrad: Float? get() = zahl(HOEHENWINKEL)
    val pegelDbfs: Float? get() = zahl(PEGEL_DBFS)
    val pegelDbSpl: Float? get() = zahl(PEGEL_DBSPL)

    /** Kurzfassung fuer die Annotationsliste; leer wenn keiner der gezeigten Werte vorhanden ist. */
    fun anzeigeZeile(): String = buildList {
        tier?.let { add(it) }
        kursGrad?.let { add("Kurs ${f("%.0f", it)} Grad") }
        // "Range Determined" = 0: nur die Richtung ist bestimmt, die Entfernung ist ein Platzhalter
        entfernungM?.takeIf { spalten["Range Determined"] != "0" }?.let { e ->
            val sigma = sigmaEntfernungM?.let { " +/- ${f("%.2f", it)}" } ?: ""
            add("${f("%.1f", e)}$sigma m")
        }
        (pegelDbSpl?.let { "${f("%.1f", it)} dB SPL" } ?: pegelDbfs?.let { "${f("%.1f", it)} dBFS" })
            ?.let { add(it) }
        kanal?.let { add("Kanal $it") }
    }.joinToString("  ")

    companion object {
        const val KANAL = "Channel"
        const val TIER = "Target"
        const val ENTFERNUNG = "Distance (m)"
        const val SIGMA_ENTFERNUNG = "Sigma Distance (m)"
        const val KURS = "Azimuth (deg)"
        const val HOEHENWINKEL = "Elevation (deg)"
        const val PEGEL_DBFS = "Level (dBFS)"
        const val PEGEL_DBSPL = "Level (dB SPL)"
        const val SPITZENFREQUENZ = "Peak Freq (Hz)"
        const val SNR = "SNR (dB)"

        /**
         * Spalten einer Sitta-Tabelle, die AMSEL unveraendert durchreicht, in der Reihenfolge
         * der Tabelle. Zeit, Frequenzband, Datei und Art fuehrt die Annotation selbst.
         */
        val SPALTEN: List<String> = listOf(
            KANAL, "Begin Date", "Begin Clock Time", SPITZENFREQUENZ, TIER,
            "X (m)", "Y (m)", "Z (m)", ENTFERNUNG, KURS, HOEHENWINKEL,
            "Sigma 3D (m)", SIGMA_ENTFERNUNG, "Sigma Distance c (m)",
            "Sigma Azimuth (deg)", "Sigma Elevation (deg)",
            "Range Determined", "Ambiguous", "Multipath",
            PEGEL_DBFS, PEGEL_DBSPL, "Source Level (dB SPL 1m)", SNR,
            "Loudest Channel", "TDOA Residual (us)", "Pairs Used", "GCC Uniqueness", "c (m/s)",
            "UTC", "Classifier", "Species Source", "Pitch Check", "Rejected Species"
        )
    }
}
