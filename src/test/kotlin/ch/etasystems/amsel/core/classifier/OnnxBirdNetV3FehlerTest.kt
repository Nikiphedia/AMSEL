package ch.etasystems.amsel.core.classifier

import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

/**
 * Ein fehlendes oder defektes Modell muss als Fehler nach aussen gehen —
 * nicht als leere Trefferliste, die wie "keine Voegel" aussieht.
 */
class OnnxBirdNetV3FehlerTest {

    private val einFenster = FloatArray(96_000) { 0.01f }

    @Test
    fun `fehlendes Modell wirft statt leerer Liste`() {
        val classifier = OnnxBirdNetV3("gibt/es/nicht.onnx", "gibt/es/nicht.csv")
        assertFalse(classifier.isAvailable())
        assertFailsWith<IllegalStateException> {
            runBlocking { classifier.classify(einFenster, 32_000) }
        }
    }

    @Test
    fun `defekte Modelldatei wirft statt leerer Liste`() {
        val modell = File.createTempFile("defekt", ".onnx").apply { writeText("kein ONNX-Modell"); deleteOnExit() }
        val labels = File.createTempFile("labels", ".csv").apply {
            writeText("idx;id;sci_name;com_name\n0;x;Parus major;Kohlmeise\n"); deleteOnExit()
        }
        val classifier = OnnxBirdNetV3(modell.absolutePath, labels.absolutePath)
        try {
            assertFailsWith<IllegalStateException> {
                runBlocking { classifier.classify(einFenster, 32_000) }
            }
        } finally {
            classifier.close()
        }
    }
}
