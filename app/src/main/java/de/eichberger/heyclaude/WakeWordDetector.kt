package de.eichberger.heyclaude

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import android.content.Context
import java.nio.FloatBuffer
import java.util.Random

/**
 * Offline-Weckworterkennung mit openWakeWord-Modellen über ONNX Runtime.
 *
 * Nachbau der Streaming-Pipeline aus openWakeWord 0.6.0 (utils.AudioFeatures und Model.predict):
 *  - Eingang: 16 kHz, Mono, 16 Bit, in Blöcken zu genau 1280 Samples (80 ms).
 *  - Mel-Spektrogramm über die letzten 1280 + 3*160 Samples, Rohwerte (nicht auf -1..1 skaliert)
 *    als Float; Ausgabe [1, 1, Frames, 32] wird mit x/10 + 2 transformiert.
 *  - Mel-Puffer startet mit 76 Zeilen aus Einsen; Embedding über die letzten 76 Mel-Frames
 *    -> Eingabe [1, 76, 32, 1], Ausgabe [1, 1, 1, 96].
 *  - Feature-Puffer startet mit Embeddings von 4 s Zufallsrauschen (Ganzzahlen -1000..999).
 *  - Weckwort-Modell bekommt die letzten 16 Embeddings als [1, 16, 96], Ausgabe [1, 1].
 *  - Die ersten 5 Vorhersagen nach einem Reset werden auf 0 gesetzt.
 *
 * Die Fenstergrößen werden beim Start aus den ONNX-Dateien gelesen und geprüft, nicht angenommen.
 * Die Gleichheit mit der Referenz wurde vorab mit einem Python-Nachbau derselben Logik geprüft.
 */
class WakeWordDetector(context: Context) : AutoCloseable {

    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()
    private val options = OrtSession.SessionOptions().apply {
        setIntraOpNumThreads(1)
        setInterOpNumThreads(1)
    }

    private val melSession = env.createSession(readAsset(context, MEL_MODEL), options)
    private val embSession = env.createSession(readAsset(context, EMBEDDING_MODEL), options)
    private val wwSession = env.createSession(readAsset(context, WAKEWORD_MODEL), options)

    private val melInput = melSession.inputNames.first()
    private val embInput = embSession.inputNames.first()
    private val wwInput = wwSession.inputNames.first()

    /** Mel-Frames pro Embedding-Fenster (76) und Mel-Bänder (32), aus embedding_model.onnx gelesen. */
    private val melWindow: Int
    private val melBins: Int

    /** Anzahl Embeddings (16) und Embedding-Größe (96), aus dem Weckwort-Modell gelesen. */
    private val featureWindow: Int
    private val embeddingSize: Int

    private var rawTail = FloatArray(0)
    private val melBuffer = ArrayDeque<FloatArray>()
    private val featureBuffer = ArrayDeque<FloatArray>()
    private var predictions = 0

    init {
        val embShape = shapeOf(embSession, embInput) // [?, 76, 32, 1]
        require(embShape.size == 4 && embShape[1] > 0 && embShape[2] > 0 && embShape[3] == 1L) {
            "Unerwartete Eingabeform des Embedding-Modells: ${embShape.contentToString()}"
        }
        melWindow = embShape[1].toInt()
        melBins = embShape[2].toInt()

        val wwShape = shapeOf(wwSession, wwInput) // [1, 16, 96]
        require(wwShape.size == 3 && wwShape[1] > 0 && wwShape[2] > 0) {
            "Unerwartete Eingabeform des Weckwort-Modells: ${wwShape.contentToString()}"
        }
        featureWindow = wwShape[1].toInt()
        embeddingSize = wwShape[2].toInt()

        reset()
    }

    /** Setzt alle Puffer zurück wie AudioFeatures.reset() und Model.reset() der Referenz. */
    fun reset() {
        rawTail = FloatArray(0)
        melBuffer.clear()
        repeat(melWindow) { melBuffer.addLast(FloatArray(melBins) { 1f }) }

        featureBuffer.clear()
        val rnd = Random()
        val noise = FloatArray(NOISE_SAMPLES) { (rnd.nextInt(2000) - 1000).toFloat() }
        val spec = melspectrogram(noise)
        val windows = ArrayList<FloatArray>()
        var i = 0
        while (i + melWindow <= spec.size) {
            windows.add(flatten(spec, i, melWindow))
            i += MEL_STEP
        }
        for (e in embed(windows)) addFeature(e)
        predictions = 0
    }

    /** Verarbeitet genau [CHUNK_SAMPLES] Samples und liefert die Weckwort-Wahrscheinlichkeit 0..1. */
    fun process(chunk: ShortArray): Float {
        require(chunk.size == CHUNK_SAMPLES) { "Es werden genau $CHUNK_SAMPLES Samples erwartet" }

        // Letzte 1280 + 480 Samples halten (Referenz: raw_data_buffer[-n_samples-160*3:]).
        val keep = CHUNK_SAMPLES + MEL_CONTEXT
        val joined = FloatArray(rawTail.size + chunk.size)
        System.arraycopy(rawTail, 0, joined, 0, rawTail.size)
        for (k in chunk.indices) joined[rawTail.size + k] = chunk[k].toFloat()
        rawTail = if (joined.size > keep) joined.copyOfRange(joined.size - keep, joined.size) else joined

        for (frame in melspectrogram(rawTail)) melBuffer.addLast(frame)
        while (melBuffer.size > melWindow) melBuffer.removeFirst()

        val window = FloatArray(melWindow * melBins)
        melBuffer.forEachIndexed { t, row -> System.arraycopy(row, 0, window, t * melBins, melBins) }
        addFeature(embed(listOf(window)).first())

        val score = predict()
        predictions++
        return if (predictions <= WARMUP_PREDICTIONS) 0f else score
    }

    private fun addFeature(e: FloatArray) {
        featureBuffer.addLast(e)
        while (featureBuffer.size > featureWindow) featureBuffer.removeFirst()
    }

    /** Mel-Spektrogramm, transformiert mit x/10 + 2. Liefert Frames zu je [melBins] Werten. */
    private fun melspectrogram(samples: FloatArray): List<FloatArray> {
        val shape = longArrayOf(1, samples.size.toLong())
        val flat = OnnxTensor.createTensor(env, FloatBuffer.wrap(samples), shape).use { input ->
            melSession.run(mapOf(melInput to input)).use { result ->
                readFloats(result.get(0) as OnnxTensor)
            }
        }
        check(flat.size % melBins == 0) { "Mel-Ausgabe passt nicht zu $melBins Bändern" }
        return List(flat.size / melBins) { f ->
            FloatArray(melBins) { b -> flat[f * melBins + b] / 10f + 2f }
        }
    }

    /** Embeddings für Fenster mit je melWindow x melBins Werten, als Batch [n, 76, 32, 1]. */
    private fun embed(windows: List<FloatArray>): List<FloatArray> {
        if (windows.isEmpty()) return emptyList()
        val data = FloatArray(windows.size * melWindow * melBins)
        windows.forEachIndexed { n, w -> System.arraycopy(w, 0, data, n * w.size, w.size) }
        val shape = longArrayOf(windows.size.toLong(), melWindow.toLong(), melBins.toLong(), 1)
        val flat = OnnxTensor.createTensor(env, FloatBuffer.wrap(data), shape).use { input ->
            embSession.run(mapOf(embInput to input)).use { result ->
                readFloats(result.get(0) as OnnxTensor)
            }
        }
        check(flat.size == windows.size * embeddingSize) { "Embedding-Ausgabe hat ${flat.size} Werte" }
        return List(windows.size) { n -> flat.copyOfRange(n * embeddingSize, (n + 1) * embeddingSize) }
    }

    private fun predict(): Float {
        val data = FloatArray(featureWindow * embeddingSize)
        featureBuffer.forEachIndexed { i, e -> System.arraycopy(e, 0, data, i * embeddingSize, embeddingSize) }
        val shape = longArrayOf(1, featureWindow.toLong(), embeddingSize.toLong())
        return OnnxTensor.createTensor(env, FloatBuffer.wrap(data), shape).use { input ->
            wwSession.run(mapOf(wwInput to input)).use { result ->
                readFloats(result.get(0) as OnnxTensor)[0]
            }
        }
    }

    private fun flatten(spec: List<FloatArray>, start: Int, count: Int): FloatArray {
        val out = FloatArray(count * melBins)
        for (t in 0 until count) System.arraycopy(spec[start + t], 0, out, t * melBins, melBins)
        return out
    }

    override fun close() {
        melSession.close()
        embSession.close()
        wwSession.close()
        options.close()
    }

    companion object {
        const val SAMPLE_RATE = 16000
        const val CHUNK_SAMPLES = 1280
        private const val MEL_CONTEXT = 160 * 3
        private const val MEL_STEP = 8
        private const val NOISE_SAMPLES = 16000 * 4
        private const val WARMUP_PREDICTIONS = 5

        const val MEL_MODEL = "melspectrogram.onnx"
        const val EMBEDDING_MODEL = "embedding_model.onnx"
        const val WAKEWORD_MODEL = "hey_claude.onnx"

        private fun readAsset(context: Context, name: String): ByteArray =
            context.assets.open(name).use { it.readBytes() }

        private fun shapeOf(session: OrtSession, input: String): LongArray =
            (session.inputInfo.getValue(input).info as TensorInfo).shape

        private fun readFloats(tensor: OnnxTensor): FloatArray {
            val buffer = tensor.floatBuffer
            val out = FloatArray(buffer.remaining())
            buffer.get(out)
            return out
        }
    }
}
