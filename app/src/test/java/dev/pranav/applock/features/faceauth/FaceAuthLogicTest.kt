package dev.pranav.applock.features.faceauth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FaceAuthLogicTest {

    private fun vec(vararg x: Float) = FaceMath.l2Normalize(floatArrayOf(*x))

    private val me = vec(1f, 0.1f, 0f, 0f)
    private val meAgain = vec(1f, 0.12f, 0.02f, 0f)
    private val stranger = vec(0f, 0f, 1f, 0.2f)

    private fun open(emb: FloatArray?, id: Int = 1) = FrameResult.Accepted(id, 0.95f, 0.95f, emb)
    private fun closed(id: Int = 1) = FrameResult.Accepted(id, 0.05f, 0.05f, null)

    @Test fun cosineOfSameVectorIsOne() {
        assertEquals(1f, FaceMath.cosine(me, me), 1e-5f)
        assertTrue(FaceMath.cosine(me, stranger) < 0.2f)
    }

    @Test fun blinkPassesOnlyOnOpenClosedOpen() {
        val d = BlinkLivenessDetector()
        assertFalse(d.update(0.9f, 0.9f, 0))
        assertFalse(d.update(0.1f, 0.1f, 100))
        assertTrue(d.update(0.9f, 0.9f, 250))
    }

    @Test fun eyesShutTooLongIsNotABlink() {
        val d = BlinkLivenessDetector(maxClosedMs = 500)
        d.update(0.9f, 0.9f, 0)
        d.update(0.1f, 0.1f, 100)
        assertFalse(d.update(0.9f, 0.9f, 2_000))
    }

    @Test fun staticPhotoNeverPasses() {
        val v = FaceVerifier(listOf(me), threshold = 0.9f)
        var t = 0L
        repeat(50) {
            val s = v.onFrame(open(meAgain), t)
            assertFalse(s is FaceVerifier.Status.Success)
            t += 100
        }
    }

    @Test fun matchingFacePlusBlinkSucceeds() {
        val v = FaceVerifier(listOf(me), threshold = 0.9f, requiredMatches = 3)
        var t = 0L
        listOf(open(meAgain), open(meAgain), closed(), open(meAgain)).forEach {
            v.onFrame(it, t); t += 100
        }
        assertTrue(v.onFrame(open(meAgain), t) is FaceVerifier.Status.Success)
    }

    @Test fun strangerWithBlinkFails() {
        val v = FaceVerifier(listOf(me), threshold = 0.9f)
        var t = 0L
        repeat(5) {
            listOf(open(stranger), closed(), open(stranger)).forEach { f ->
                assertFalse(v.onFrame(f, t) is FaceVerifier.Status.Success); t += 100
            }
        }
    }

    @Test fun swappingFacesResetsEvidence() {
        val v = FaceVerifier(listOf(me), threshold = 0.9f, requiredMatches = 3)
        // Blink with one tracked face, then show matching frames on a different track.
        v.onFrame(open(stranger, id = 1), 0)
        v.onFrame(closed(id = 1), 100)
        v.onFrame(open(stranger, id = 1), 200)
        var t = 300L
        repeat(3) { assertFalse(v.onFrame(open(me, id = 2), t) is FaceVerifier.Status.Success); t += 100 }
    }

    @Test fun verifierTimesOut() {
        val v = FaceVerifier(listOf(me), timeoutMs = 1_000)
        v.onFrame(FrameResult.Rejected(Hint.NO_FACE), 0)
        assertEquals(FaceVerifier.Status.TimedOut, v.onFrame(FrameResult.Rejected(Hint.NO_FACE), 1_500))
    }

    @Test fun enrollerCollectsVariedConsistentSamples() {
        val e = FaceEnroller(targetSamples = 3, minIntervalMs = 0, nearDuplicate = 0.9999f)
        val samples = listOf(vec(1f, 0.1f, 0f, 0f), vec(1f, 0.2f, 0.05f, 0f), vec(1f, 0f, 0.1f, 0.05f))
        var status: FaceEnroller.Status? = null
        samples.forEachIndexed { i, s -> status = e.onFrame(open(s), i * 100L) }
        assertTrue(status is FaceEnroller.Status.Done)
        assertEquals(3, (status as FaceEnroller.Status.Done).templates.size)
    }

    @Test fun enrollerRejectsDifferentPerson() {
        val e = FaceEnroller(targetSamples = 3, minIntervalMs = 0)
        e.onFrame(open(me), 0)
        val s = e.onFrame(open(stranger), 100) as FaceEnroller.Status.Progress
        assertEquals(1, s.captured)
        assertEquals(Hint.HOLD_STILL, s.hint)
    }
}
