package dev.pranav.applock.features.faceauth

import android.graphics.Bitmap
import android.graphics.Rect
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import java.io.Closeable
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Detects a face with ML Kit, applies quality gates, crops it and computes an embedding.
 *
 * Call [process] from a background thread (e.g. the CameraX analyzer executor); it blocks
 * on ML Kit with Tasks.await, which is not allowed on the main thread.
 */
class FaceFrameProcessor(
    private val embedder: FaceEmbedder,
    private val minFaceWidthFraction: Float = 0.25f,
    private val maxYawDeg: Float = 20f,
    private val maxPitchDeg: Float = 20f,
    private val maxRollDeg: Float = 12f,
    private val eyesOpenForEmbedding: Float = 0.5f,
) : Closeable {

    private val detector = FaceDetection.getClient(
        FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
            .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL) // eye-open probs
            .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_NONE)
            .setMinFaceSize(0.2f)
            .enableTracking()
            .build()
    )

    /** [frame] must already be upright (rotation applied). */
    fun process(frame: Bitmap): FrameResult {
        val faces = Tasks.await(detector.process(InputImage.fromBitmap(frame, 0)))
        if (faces.isEmpty()) return FrameResult.Rejected(Hint.NO_FACE)
        if (faces.size > 1) return FrameResult.Rejected(Hint.MULTIPLE_FACES)

        val face = faces[0]
        val box = face.boundingBox
        if (box.width() < frame.width * minFaceWidthFraction) return FrameResult.Rejected(Hint.MOVE_CLOSER)
        if (abs(face.headEulerAngleY) > maxYawDeg ||
            abs(face.headEulerAngleX) > maxPitchDeg ||
            abs(face.headEulerAngleZ) > maxRollDeg
        ) return FrameResult.Rejected(Hint.LOOK_STRAIGHT)

        val left = face.leftEyeOpenProbability
        val right = face.rightEyeOpenProbability
        val eyesOpen = left != null && right != null && left >= eyesOpenForEmbedding && right >= eyesOpenForEmbedding

        val embedding = if (eyesOpen) cropAligned(frame, face)?.let { crop ->
            embedder.embed(crop).also { crop.recycle() }
        } else null

        return FrameResult.Accepted(
            face.trackingId, left, right, embedding, face.headEulerAngleY, face.headEulerAngleX
        )
    }

    /**
     * Square crop around the face with a small margin. No roll de-rotation: instead the roll
     * gate ([maxRollDeg]) keeps faces near-level, and enrollment and verification use the
     * same pipeline, so they stay comparable.
     */
    private fun cropAligned(frame: Bitmap, face: Face): Bitmap? {
        val b = face.boundingBox
        val side = (max(b.width(), b.height()) * 1.15f).toInt()
        val cx = b.centerX()
        val cy = b.centerY()
        val rect = Rect(
            max(0, cx - side / 2),
            max(0, cy - side / 2),
            min(frame.width, cx + side / 2),
            min(frame.height, cy + side / 2),
        )
        // Face is cut off by the frame edge: skip rather than embed a partial face.
        if (rect.width() < side * 0.9f || rect.height() < side * 0.9f) return null

        return Bitmap.createBitmap(frame, rect.left, rect.top, rect.width(), rect.height())
    }

    override fun close() = detector.close()
}
