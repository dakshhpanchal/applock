package dev.pranav.applock.features.faceauth

import android.content.Context
import android.graphics.Bitmap
import org.tensorflow.lite.Interpreter
import java.io.Closeable
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

/**
 * Turns a cropped face bitmap into an L2-normalized embedding using a MobileFaceNet-style
 * TFLite model placed at app/src/main/assets/[modelAsset].
 *
 * Input and output sizes are read from the model, so 112x112 -> 128-d and 112x112 -> 192-d
 * variants both work. Some exported MobileFaceNet files have a fixed batch of 2; that is
 * handled by filling every batch slot with the same face and reading slot 0.
 *
 * Not thread-safe. Use it from a single analyzer thread.
 */
class FaceEmbedder(
    context: Context,
    modelAsset: String = DEFAULT_MODEL,
    numThreads: Int = 2,
) : Closeable {

    private val interpreter: Interpreter
    private val batch: Int
    private val inputH: Int
    private val inputW: Int
    val embeddingSize: Int

    private val inputBuffer: ByteBuffer
    private val pixels: IntArray
    private val output: Array<FloatArray>

    init {
        interpreter = Interpreter(loadModel(context, modelAsset), Interpreter.Options().setNumThreads(numThreads))
        val inShape = interpreter.getInputTensor(0).shape() // [batch, h, w, 3]
        require(inShape.size == 4 && inShape[3] == 3) {
            "Expected NHWC RGB input, got ${inShape.contentToString()}"
        }
        batch = inShape[0]
        inputH = inShape[1]
        inputW = inShape[2]
        embeddingSize = interpreter.getOutputTensor(0).shape().last()

        inputBuffer = ByteBuffer.allocateDirect(4 * batch * inputH * inputW * 3).order(ByteOrder.nativeOrder())
        pixels = IntArray(inputW * inputH)
        output = Array(batch) { FloatArray(embeddingSize) }
    }

    fun embed(face: Bitmap): FloatArray {
        val scaled = if (face.width == inputW && face.height == inputH) face
        else Bitmap.createScaledBitmap(face, inputW, inputH, true)
        scaled.getPixels(pixels, 0, inputW, 0, 0, inputW, inputH)
        if (scaled !== face) scaled.recycle()

        inputBuffer.rewind()
        repeat(batch) {
            for (p in pixels) {
                // MobileFaceNet normalization: (x - 127.5) / 128
                inputBuffer.putFloat(((p shr 16 and 0xFF) - 127.5f) / 128f)
                inputBuffer.putFloat(((p shr 8 and 0xFF) - 127.5f) / 128f)
                inputBuffer.putFloat(((p and 0xFF) - 127.5f) / 128f)
            }
        }
        inputBuffer.rewind()
        interpreter.run(inputBuffer, output)
        return FaceMath.l2Normalize(output[0])
    }

    override fun close() = interpreter.close()

    companion object {
        const val DEFAULT_MODEL = "mobilefacenet.tflite"

        private fun loadModel(context: Context, asset: String): MappedByteBuffer =
            context.assets.openFd(asset).use { fd ->
                FileInputStream(fd.fileDescriptor).channel.use { ch ->
                    ch.map(FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength)
                }
            }

        fun isModelBundled(context: Context, asset: String = DEFAULT_MODEL): Boolean =
            runCatching { context.assets.openFd(asset).close(); true }.getOrDefault(false)
    }
}
