package dev.pranav.applock.features.faceauth

import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.SystemClock
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy

/**
 * CameraX analyzer that converts each frame to an upright bitmap, runs it through
 * [processor] and hands the [FrameResult] to [onResult] (on the analyzer thread).
 *
 * Build the ImageAnalysis use case with:
 *   .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
 *   .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
 * and a single-thread executor (the embedder isn't thread-safe).
 */
class FaceCameraAnalyzer(
    private val processor: FaceFrameProcessor,
    private val onResult: (result: FrameResult, timestampMs: Long) -> Unit,
) : ImageAnalysis.Analyzer {

    override fun analyze(image: ImageProxy) {
        val now = SystemClock.elapsedRealtime()
        val upright = image.use { proxy ->
            val raw = proxy.toBitmap()
            val rotation = proxy.imageInfo.rotationDegrees
            if (rotation == 0) raw
            else Bitmap.createBitmap(
                raw, 0, 0, raw.width, raw.height,
                Matrix().apply { postRotate(rotation.toFloat()) }, true,
            ).also { raw.recycle() }
        }
        try {
            val result = try {
                processor.process(upright)
            } catch (e: Exception) {
                FrameResult.Rejected(Hint.NO_FACE) // one bad frame shouldn't kill the stream
            }
            onResult(result, now)
        } finally {
            upright.recycle()
        }
    }
}
