package dev.pranav.applock.features.faceauth

/** Head position the enroller waits for. Left/right sign differs by camera, so the sides are generic. */
enum class Pose(val hint: Hint?) {
    ANY(null),
    FRONT(Hint.LOOK_STRAIGHT),
    SIDE_A(Hint.TURN_ONE_SIDE),
    SIDE_B(Hint.TURN_OTHER_SIDE);

    fun matches(yaw: Float): Boolean = when (this) {
        ANY -> true
        FRONT -> kotlin.math.abs(yaw) < 10f
        SIDE_A -> yaw >= 12f
        SIDE_B -> yaw <= -12f
    }

    companion object {
        val GUIDED = listOf(FRONT, SIDE_A, SIDE_B)
    }
}

/**
 * Collects [targetSamples] embeddings of one person for enrollment.
 *
 * - Samples are spaced at least [minIntervalMs] apart, and near-duplicates are skipped,
 *   so the user should move their head slightly between captures.
 * - Every sample must stay similar to the running mean, and the tracked face must not change,
 *   so a second person stepping in can't pollute the enrollment.
 *
 * With several [poses] the samples are split evenly between them and each pose must be held in
 * turn (front, then each side), so the saved templates cover more angles. Side views look less
 * like the frontal ones, so the consistency check compares to the closest sample instead of the mean.
 *
 * Feed frames with [onFrame]; when it returns [Status.Done], save `templates` with
 * [FaceTemplateStore.save].
 */
class FaceEnroller(
    private val targetSamples: Int = 6,
    private val minIntervalMs: Long = 350,
    private val nearDuplicate: Float = 0.97f,
    private val minConsistency: Float = 0.55f,
    private val poses: List<Pose> = listOf(Pose.ANY),
    private val guidedConsistency: Float = 0.45f,
) {
    sealed interface Status {
        data class Progress(val captured: Int, val target: Int, val hint: Hint?) : Status
        data class Done(val templates: List<FloatArray>) : Status
    }

    private val samples = mutableListOf<FloatArray>()
    private var trackingId: Int? = null
    private var lastCaptureMs = 0L

    fun onFrame(result: FrameResult, nowMs: Long): Status {
        if (samples.size >= targetSamples) return Status.Done(samples.toList())

        val guided = poses.size > 1
        val pose = poses[(samples.size * poses.size / targetSamples).coerceAtMost(poses.lastIndex)]

        if (result is FrameResult.Rejected) {
            // Turning to the side can push the head past the detector's angle limit.
            return progress(if (guided && result.hint == Hint.LOOK_STRAIGHT) pose.hint else result.hint)
        }
        result as FrameResult.Accepted

        // A different face (new tracking id) restarts enrollment. Not in guided mode: the
        // detector often re-IDs a face mid-turn; the similarity check below catches swaps.
        if (!guided && trackingId != null && result.trackingId != null && result.trackingId != trackingId) {
            reset()
        }
        trackingId = trackingId ?: result.trackingId

        if (!pose.matches(result.yaw)) return progress(pose.hint)

        val emb = result.embedding ?: return progress(Hint.KEEP_EYES_OPEN)
        if (nowMs - lastCaptureMs < minIntervalMs) return progress(pose.hint)

        if (samples.isNotEmpty()) {
            val similarity = if (guided) samples.maxOf { FaceMath.cosine(emb, it) }
            else FaceMath.cosine(emb, FaceMath.mean(samples))
            if (similarity < (if (guided) guidedConsistency else minConsistency)) return progress(Hint.HOLD_STILL)
            if (samples.any { FaceMath.cosine(emb, it) > nearDuplicate }) return progress(Hint.TURN_SLIGHTLY)
        }

        samples += emb
        lastCaptureMs = nowMs
        if (samples.size >= targetSamples) return Status.Done(samples.toList())
        val next = poses[(samples.size * poses.size / targetSamples).coerceAtMost(poses.lastIndex)]
        return progress(next.hint)
    }

    fun reset() {
        samples.clear()
        trackingId = null
        lastCaptureMs = 0L
    }

    private fun progress(hint: Hint?) = Status.Progress(samples.size, targetSamples, hint)
}
