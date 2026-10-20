package dev.pranav.applock.features.faceauth

/** What one camera frame produced. */
sealed interface FrameResult {
    /** Couldn't use this frame; [hint] is user-facing guidance. */
    data class Rejected(val hint: Hint) : FrameResult

    data class Accepted(
        val trackingId: Int?,
        val leftEyeOpen: Float?,
        val rightEyeOpen: Float?,
        /** Null when eyes are closed: we don't embed mid-blink frames. */
        val embedding: FloatArray?,
    ) : FrameResult
}

enum class Hint { NO_FACE, MULTIPLE_FACES, MOVE_CLOSER, LOOK_STRAIGHT, HOLD_STILL, BLINK, KEEP_EYES_OPEN, TURN_SLIGHTLY }
