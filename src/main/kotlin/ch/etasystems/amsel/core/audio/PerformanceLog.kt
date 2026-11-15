package ch.etasystems.amsel.core.audio

import org.slf4j.LoggerFactory
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Leichtgewichtiger Performance-Logger fuer Hot-Path-Messungen.
 * Ausgabe via SLF4J (Konsole) und zusaetzlich in die Datei Dokumente/AMSEL/perf.log:
 * so lassen sich Laufzeiten auch nachtraeglich nachlesen, wenn die Konsole nicht sichtbar war.
 */
object PerformanceLog {
    @PublishedApi
    internal val logger = LoggerFactory.getLogger("PERF")

    /** Ab dieser Groesse wird die Datei neu begonnen. */
    private const val MAX_BYTES = 1_000_000L
    private val zeitFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")

    /** Zieldatei; in Tests umstellbar. */
    @Volatile
    var datei: File = File(System.getProperty("user.home"), "Documents/AMSEL/perf.log")

    /** Schreibt eine Zeile in Konsole und Datei. Ein Fehler beim Schreiben stoert die Arbeit nicht. */
    @Synchronized
    fun eintrag(text: String) {
        logger.info(text)
        try {
            datei.parentFile?.mkdirs()
            if (datei.length() > MAX_BYTES) datei.writeText("")
            datei.appendText("${LocalDateTime.now().format(zeitFormat)}  $text\n")
        } catch (_: Exception) {
        }
    }

    /** Millisekunden seit [startNano], fuer Messungen ueber suspend-Aufrufe hinweg. */
    fun msSeit(startNano: Long): Double = (System.nanoTime() - startNano) / 1_000_000.0

    /** Zahl mit einer Nachkommastelle, unabhaengig von der Sprache des Rechners. */
    fun f1(wert: Double): String = String.format(Locale.US, "%.1f", wert)

    /** Misst einen Block und loggt das Ergebnis */
    inline fun <T> measure(label: String, block: () -> T): T {
        val start = System.nanoTime()
        val result = block()
        eintrag("[$label] ${f1(msSeit(start))} ms")
        return result
    }

    /** Loggt eine Zusammenfassung nach einem Analyse-Lauf */
    fun summary(
        totalMs: Double,
        audioDurationSec: Float,
        totalChunks: Int,
        skippedChunks: Int,
        classifiedChunks: Int
    ) {
        val faktor = if (totalMs > 0) audioDurationSec / (totalMs / 1000.0) else 0.0
        eintrag(
            "Abschnitt: Audio ${f1(audioDurationSec.toDouble())} s in ${f1(totalMs)} ms (${f1(faktor)}x Echtzeit), " +
                "Fenster $totalChunks, als Stille uebersprungen $skippedChunks, gerechnet $classifiedChunks"
        )
    }
}
