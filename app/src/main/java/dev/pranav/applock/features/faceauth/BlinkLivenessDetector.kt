package dev.pranav.applock.features.faceauth

/**
 * Passes once it sees eyes open -> both eyes closed -> eyes open again, with the closed phase
 * no longer than [maxClosedMs]. Defeats a still photo; does NOT defeat a replayed video.
 *
 * Pure Kotlin (takes eye-open probabilities, not ML Kit types) so it can be unit tested.
 */
class BlinkLivenessDetector(
    private val openThreshold: Float = 0.6f,
    private val closedThreshold: Float = 0.25f,
    private val maxClosedMs: Long = 1_000,
) {
    private enum class State { WAIT_OPEN, OPEN, CLOSED, PASSED }

    private var state = State.WAIT_OPEN
    private var closedAt = 0L

    val passed: Boolean get() = state == State.PASSED

    /** Feed one frame's eye-open probabilities (null = unknown). Returns [passed]. */
    fun update(leftOpen: Float?, rightOpen: Float?, nowMs: Long): Boolean {
        if (state == State.PASSED) return true
        if (leftOpen == null || rightOpen == null) return false

        val bothOpen = leftOpen >= openThreshold && rightOpen >= openThreshold
        val bothClosed = leftOpen <= closedThreshold && rightOpen <= closedThreshold

        state = when (state) {
            State.WAIT_OPEN -> if (bothOpen) State.OPEN else State.WAIT_OPEN
            State.OPEN -> if (bothClosed) { closedAt = nowMs; State.CLOSED } else State.OPEN
            State.CLOSED -> when {
                nowMs - closedAt > maxClosedMs -> State.WAIT_OPEN // eyes shut too long, not a blink
                bothOpen -> State.PASSED
                else -> State.CLOSED
            }
            State.PASSED -> State.PASSED
        }
        return passed
    }

    fun reset() {
        state = State.WAIT_OPEN
        closedAt = 0L
    }
}
