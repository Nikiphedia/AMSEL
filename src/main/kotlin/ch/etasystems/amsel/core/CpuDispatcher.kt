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
    /**
     * Fuer ONNX-Inferenz: so viele Fenster gleichzeitig wie [Compute] Threads hat.
     * Jeder run() rechnet mit einem Thread (siehe OnnxBirdNetV3.ensureSession).
     */
    val Inference = Dispatchers.Default.limitedParallelism(
        (Runtime.getRuntime().availableProcessors() - 1).coerceAtLeast(2)
    )
}
