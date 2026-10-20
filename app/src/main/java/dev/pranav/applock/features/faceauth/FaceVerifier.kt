package dev.pranav.applock.features.faceauth

/**
 * Decides whether frames from the camera belong to the enrolled user.
 *
 * Success requires, all on the SAME tracked face:
 *  1. a blink (photo defence), and
 *  2. at least [requiredMatches] eyes-open frames whose best similarity to the enrolled
 *     templates is >= [threshold].
 * If the tracked face changes (e.g. someone swaps a phone/photo in), everything resets.
 * After [timeoutMs] without success it reports [Status.TimedOut]; fall back to PIN then.
 */
class FaceVerifier(
    private val templates: List<FloatArray>,
    private val threshold: Float = DEFAULT_THRESHOLD,
    private val requiredMatches: Int = 3,
    private val timeoutMs: Long = 8_000,
    private val liveness: BlinkLivenessDetector = BlinkLivenessDetector(),
) {
    init {
        require(templates.isNotEmpty()) { "No enrolled face" }
    }

    sealed interface Status {
        data class InProgress(val hint: Hint?, val bestScore: Float) : Status
        data class Success(val score: Float) : Status
        data object TimedOut : Status
    }

    private var startMs = -1L
    private var trackingId: Int? = null
    private var matches = 0
    private var bestScore = 0f
    private var done = false

    fun onFrame(result: FrameResult, nowMs: Long): Status {
        if (done) return Status.Success(bestScore)
        if (startMs < 0) startMs = nowMs
        if (nowMs - startMs > timeoutMs) return Status.TimedOut

        if (result is FrameResult.Rejected) return Status.InProgress(result.hint, bestScore)
        result as FrameResult.Accepted

        if (trackingId != null && result.trackingId != null && result.trackingId != trackingId) {
            resetEvidence()
        }
        trackingId = trackingId ?: result.trackingId

        liveness.update(result.leftEyeOpen, result.rightEyeOpen, nowMs)

        result.embedding?.let { emb ->
            val score = FaceMath.bestMatch(emb, templates)
            if (score > bestScore) bestScore = score
            if (score >= threshold) matches++
        }

        if (liveness.passed && matches >= requiredMatches) {
            done = true
            return Status.Success(bestScore)
        }
        val hint = when {
            matches >= requiredMatches -> Hint.BLINK // recognised, waiting for liveness
            else -> null
        }
        return Status.InProgress(hint, bestScore)
    }

    /** Restart the attempt, e.g. when the lock screen is shown again. */
    fun reset() {
        startMs = -1L
        done = false
        resetEvidence()
    }

    private fun resetEvidence() {
        trackingId = null
        matches = 0
        bestScore = 0f
        liveness.reset()
    }

    companion object {
        /**
         * Starting point for MobileFaceNet with cosine similarity. Tune it on your own device:
         * log `bestScore` for you and for a few other people, and pick a value between them,
         * erring high (false rejects only cost a PIN entry).
         */
        const val DEFAULT_THRESHOLD = 0.72f
    }
}
