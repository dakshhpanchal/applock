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
        /** Head turn left/right and up/down in degrees, as reported by the detector. */
        val yaw: Float = 0f,
        val pitch: Float = 0f,
    ) : FrameResult
}

enum class Hint { NO_FACE, MULTIPLE_FACES, MOVE_CLOSER, LOOK_STRAIGHT, HOLD_STILL, BLINK, KEEP_EYES_OPEN, TURN_SLIGHTLY, TURN_ONE_SIDE, TURN_OTHER_SIDE }
