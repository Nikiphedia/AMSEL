package ch.etasystems.amsel.core.classifier

import java.io.File
import kotlin.math.exp

/** Aufbau der Label-Datei eines Modells. */
enum class LabelFormat {
    /** Semikolon-CSV mit Kopfzeile, Spalten sci_name und com_name (BirdNET V3). */
    BIRDNET_CSV,
    /** Erste Zeile Namensraum, danach je Zeile ein Name (Perch). Namen ohne Leerzeichen sind Geraeuschklassen. */
    NAMENSLISTE
}

/**
 * Alles, worin sich Roh-Audio-Modelle fuer den Klassifikator unterscheiden.
 * Eine Modellart ist eine Zeile dieser Tabelle, keine eigene Klasse (Vorbild: Sitta, ADAPTER[]).
 *
 * @property art Kennung fuer die Herkunft eines Treffers, Schreibweise wie bei Sitta ("perch-v2")
 * @property fensterFest true: das Modell verlangt genau [fensterSek]; false: der Aufrufer darf die Laenge waehlen
 * @property spitze jedes Fenster wird auf diesen Spitzenwert normiert; null = Eingang unveraendert
 * @property wert Umrechnung des Modellausgangs in einen Wert 0..1
 * @property resamplerUmNyquist Tiefpass des Resamplers mit Mitte auf der Nyquist-Frequenz statt darunter
 *           (siehe AudioResampler). Noetig, wenn das Modell mit so umgetaktetem Audio trainiert wurde.
 */
data class ModellAdapter(
    val art: String,
    val name: String,
    val version: String,
    val sampleRate: Int,
    val fensterSek: Float,
    val fensterFest: Boolean,
    val labelFormat: LabelFormat,
    val spitze: Float?,
    val wert: (Float) -> Float,
    val resamplerUmNyquist: Boolean = false
)

/** Labels eines Modells in der Reihenfolge des Modellausgangs. */
class ModellLabels(
    val wissenschaftlich: List<String>,
    val trivial: List<String>,
    /** false = Geraeuschklasse (kein Artname), wird nicht als Treffer gemeldet */
    val istArt: BooleanArray
) {
    val anzahl: Int get() = wissenschaftlich.size
}

object ModellAdapterTabelle {

    val BIRDNET_V3 = ModellAdapter(
        art = "birdnet-v3", name = "BirdNET V3.0", version = "preview3",
        sampleRate = 32_000, fensterSek = 3.0f, fensterFest = false,
        labelFormat = LabelFormat.BIRDNET_CSV, spitze = null,
        wert = { it },  // das Modell gibt bereits Werte 0..1
        // Die Referenz (birdnet-V3.0-dev, birdnet_v3.py: load_audio) taktet mit scipy resample_poly um,
        // also wie Perch. Gemessen am 13.11.2026: fuer BirdNET V3 macht die Auslegung kaum einen
        // Unterschied (1800 Werte einer 30-min-Aufnahme: groesste Abweichung 0,4 Prozentpunkte).
        resamplerUmNyquist = true
    )

    /** Quelle der Zahlen: Sitta, birdnet/SCHNITTSTELLEN.md Abschnitt 4. */
    val PERCH_V2 = ModellAdapter(
        art = "perch-v2", name = "Perch", version = "2.0",
        sampleRate = 32_000, fensterSek = 5.0f, fensterFest = true,
        labelFormat = LabelFormat.NAMENSLISTE, spitze = 0.25f,
        wert = { logit -> (1.0 / (1.0 + exp(-(0.97 * logit - 10.0)))).toFloat() },
        // Perch-Referenz (chirp/audio_utils.py) taktet mit res_type "polyphase" um. Gemessen am
        // 13.11.2026: ohne diese Auslegung liegen schwache Rufe bis 18 Prozentpunkte unter Sitta.
        resamplerUmNyquist = true
    )

    /** Herkunft fuer Treffer der Python-Bridge. */
    const val ART_BIRDNET_PYTHON = "birdnet-v2.4"

    /** Lesbarer Name einer Modellart fuer die Anzeige; unbekannte Angaben (z.B. aus einer Sitta-Tabelle) unveraendert. */
    fun anzeigeName(art: String): String = when (art) {
        "" -> ""
        BIRDNET_V3.art -> BIRDNET_V3.name
        PERCH_V2.art -> "${PERCH_V2.name} ${PERCH_V2.version}"
        ART_BIRDNET_PYTHON -> "BirdNET 2.4"
        else -> art
    }

    /** Modellart anhand des Dateinamens. Unbekannte Dateien gelten als BirdNET V3 (bisheriges Verhalten). */
    fun fuerDatei(dateiname: String): ModellAdapter =
        if (dateiname.contains("perch", ignoreCase = true)) PERCH_V2 else BIRDNET_V3

    /**
     * Liest eine Label-Datei. [umbenennen] ersetzt Namen (Perch -> BirdNET-Schreibweise),
     * damit dieselbe Art bei allen Modellen gleich heisst.
     */
    fun labelsLesen(datei: File, format: LabelFormat, umbenennen: Map<String, String> = emptyMap()): ModellLabels {
        val zeilen = datei.readLines(Charsets.UTF_8)
        val sci = ArrayList<String>()
        val com = ArrayList<String>()
        when (format) {
            LabelFormat.BIRDNET_CSV -> {
                val kopf = zeilen.firstOrNull()?.trimStart('﻿')?.split(";") ?: emptyList()
                val sciIndex = kopf.indexOf("sci_name")
                val comIndex = kopf.indexOf("com_name")
                require(sciIndex >= 0) { "Label-Datei ${datei.name} hat keine Spalte sci_name" }
                for (zeile in zeilen.drop(1)) {
                    if (zeile.isBlank()) continue
                    val spalten = zeile.split(";")
                    val name = spalten.getOrElse(sciIndex) { "" }.trim()
                    if (name.isEmpty()) continue
                    sci.add(name)
                    com.add(if (comIndex >= 0) spalten.getOrElse(comIndex) { "" }.trim() else "")
                }
                return ModellLabels(sci, com, BooleanArray(sci.size) { true })
            }
            LabelFormat.NAMENSLISTE -> {
                // Leere Zeilen duerfen nicht uebersprungen werden: die Position ist der Klassenindex
                val namen = zeilen.drop(1).map { it.trim() }.dropLastWhile { it.isEmpty() }
                for (name in namen) {
                    sci.add(umbenennen[name] ?: name)
                    com.add("")
                }
                return ModellLabels(sci, com, BooleanArray(namen.size) { namen[it].contains(' ') })
            }
        }
    }

    /**
     * Perch-Name -> BirdNET-Name fuer Arten, die nur anders heissen (Gattungswechsel).
     * Quelle: Sitta, perch_synonyme.tsv (aus der Taxonomie-Datenbank von Perch-Hoplite, Apache 2.0).
     * Uebernommen werden nur die Zeilen mit umbenennen=ja (eindeutige 1:1-Zuordnung).
     */
    val perchSynonyme: Map<String, String> by lazy {
        val text = ModellAdapterTabelle::class.java.getResourceAsStream("/models/perch_synonyme.tsv")
            ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: return@lazy emptyMap()
        text.lineSequence()
            .filter { it.isNotBlank() && !it.startsWith("#") }
            .map { it.split('\t') }
            .filter { it.size >= 3 && it[2].trim() == "ja" }
            .associate { it[1].trim() to it[0].trim() }
    }
}
