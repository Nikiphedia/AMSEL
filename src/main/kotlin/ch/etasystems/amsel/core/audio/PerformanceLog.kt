package ch.etasystems.amsel.core.audio

import org.slf4j.LoggerFactory

/**
 * Leichtgewichtiger Performance-Logger fuer Hot-Path-Messungen.
 * Ausgabe via SLF4J — sichtbar in der Konsole.
 */
object PerformanceLog {
    @PublishedApi
    internal val logger = LoggerFactory.getLogger("PERF")

    /** Misst einen Block und loggt das Ergebnis */
    inline fun <T> measure(label: String, block: () -> T): T {
        val start = System.nanoTime()
        val result = block()
        val elapsedMs = (System.nanoTime() - start) / 1_000_000.0
        logger.info("[{}] {:.1f} ms", label, elapsedMs)
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
        val ratio = if (audioDurationSec > 0) totalMs / 1000.0 / audioDurationSec else 0.0
        logger.info("=== ANALYSE ZUSAMMENFASSUNG ===")
        logger.info("Audio: {:.1f}s | Dauer: {:.1f}ms | Ratio: {:.2f}x Echtzeit", audioDurationSec, totalMs, ratio)
        logger.info("Chunks total: {} | uebersprungen (Stille): {} | klassifiziert: {}", totalChunks, skippedChunks, classifiedChunks)
        logger.info("===============================")
    }
}
