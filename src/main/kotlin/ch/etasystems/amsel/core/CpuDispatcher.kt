package ch.etasystems.amsel.core

import kotlinx.coroutines.Dispatchers

/**
 * Zentrale Dispatcher fuer CPU-intensive Arbeit in AMSEL.
 * Begrenzt die Parallelitaet um UI-Responsivitaet zu erhalten.
 */
object AmselDispatchers {
    /** Fuer CPU-intensive Arbeit: maximal (Cores - 1) Threads, mindestens 2 */
    val Compute = Dispatchers.Default.limitedParallelism(
        (Runtime.getRuntime().availableProcessors() - 1).coerceAtLeast(2)
    )
    /** Fuer ONNX-Inferenz: maximal 2 parallele Sessions */
    val Inference = Dispatchers.Default.limitedParallelism(2)
}
