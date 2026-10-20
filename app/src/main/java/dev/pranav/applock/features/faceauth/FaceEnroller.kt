package dev.pranav.applock.features.faceauth

/**
 * Collects [targetSamples] embeddings of one person for enrollment.
 *
 * - Samples are spaced at least [minIntervalMs] apart, and near-duplicates are skipped,
 *   so the user should move their head slightly between captures.
 * - Every sample must stay similar to the running mean, and the tracked face must not change,
 *   so a second person stepping in can't pollute the enrollment.
 *
 * Feed frames with [onFrame]; when it returns [Status.Done], save `templates` with
 * [FaceTemplateStore.save].
 */
class FaceEnroller(
    private val targetSamples: Int = 6,
    private val minIntervalMs: Long = 350,
    private val nearDuplicate: Float = 0.97f,
    private val minConsistency: Float = 0.55f,
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

        if (result is FrameResult.Rejected) return progress(result.hint)
        result as FrameResult.Accepted

        // A different face (new tracking id) restarts enrollment.
        if (trackingId != null && result.trackingId != null && result.trackingId != trackingId) {
            reset()
        }
        trackingId = trackingId ?: result.trackingId

        val emb = result.embedding ?: return progress(Hint.KEEP_EYES_OPEN)
        if (nowMs - lastCaptureMs < minIntervalMs) return progress(null)

        if (samples.isNotEmpty()) {
            if (FaceMath.cosine(emb, FaceMath.mean(samples)) < minConsistency) return progress(Hint.HOLD_STILL)
            if (samples.any { FaceMath.cosine(emb, it) > nearDuplicate }) return progress(Hint.TURN_SLIGHTLY)
        }

        samples += emb
        lastCaptureMs = nowMs
        return if (samples.size >= targetSamples) Status.Done(samples.toList()) else progress(null)
    }

    fun reset() {
        samples.clear()
        trackingId = null
        lastCaptureMs = 0L
    }

    private fun progress(hint: Hint?) = Status.Progress(samples.size, targetSamples, hint)
}
