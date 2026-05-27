package com.custom.minimizer.motion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sqrt

class MotionWakeServiceTest {

    // ── vectorMagnitude ──────────────────────────────────────────────────────

    @Test
    fun `vectorMagnitude computes Euclidean norm for unit axes`() {
        // Only x
        assertEquals(3.0f, MotionWakeService.vectorMagnitude(3f, 0f, 0f), 0.001f)
        // Only y
        assertEquals(4.0f, MotionWakeService.vectorMagnitude(0f, 4f, 0f), 0.001f)
        // Only z
        assertEquals(5.0f, MotionWakeService.vectorMagnitude(0f, 0f, 5f), 0.001f)
    }

    @Test
    fun `vectorMagnitude uses all three axes`() {
        // 3-4-5 right triangle extended to 3D: sqrt(3²+4²+0²) = 5
        assertEquals(5.0f, MotionWakeService.vectorMagnitude(3f, 4f, 0f), 0.001f)
    }

    @Test
    fun `vectorMagnitude for gravity-only reading is approximately 9_8`() {
        // Device flat on a table: z ≈ 9.81 m/s², x = y = 0
        val result = MotionWakeService.vectorMagnitude(0f, 0f, 9.81f)
        assertEquals(9.81f, result, 0.01f)
    }

    @Test
    fun `vectorMagnitude is always non-negative`() {
        assertTrue(MotionWakeService.vectorMagnitude(-3f, -4f, -5f) >= 0f)
    }

    @Test
    fun `vectorMagnitude of zero vector is zero`() {
        assertEquals(0f, MotionWakeService.vectorMagnitude(0f, 0f, 0f), 0f)
    }

    @Test
    fun `vectorMagnitude is symmetric — axis order does not matter`() {
        val a = MotionWakeService.vectorMagnitude(1f, 2f, 3f)
        val b = MotionWakeService.vectorMagnitude(3f, 1f, 2f)
        assertEquals(a, b, 0.001f)
    }

    // ── isMotionExceeded ─────────────────────────────────────────────────────

    @Test
    fun `isMotionExceeded returns true when delta exceeds sensitivity`() {
        assertTrue(MotionWakeService.isMotionExceeded(delta = 4.0f, sensitivity = 3.0f))
    }

    @Test
    fun `isMotionExceeded returns false when delta equals sensitivity`() {
        assertFalse(MotionWakeService.isMotionExceeded(delta = 3.0f, sensitivity = 3.0f))
    }

    @Test
    fun `isMotionExceeded returns false when delta is below sensitivity`() {
        assertFalse(MotionWakeService.isMotionExceeded(delta = 1.5f, sensitivity = 3.0f))
    }

    @Test
    fun `isMotionExceeded respects DEFAULT_SENSITIVITY constant`() {
        // Just above the default
        assertTrue(MotionWakeService.isMotionExceeded(
            delta = MotionWakeService.DEFAULT_SENSITIVITY + 0.01f,
            sensitivity = MotionWakeService.DEFAULT_SENSITIVITY
        ))
        // Exactly at the default (not exceeded)
        assertFalse(MotionWakeService.isMotionExceeded(
            delta = MotionWakeService.DEFAULT_SENSITIVITY,
            sensitivity = MotionWakeService.DEFAULT_SENSITIVITY
        ))
    }

    @Test
    fun `isMotionExceeded with zero delta is never exceeded`() {
        assertFalse(MotionWakeService.isMotionExceeded(delta = 0f, sensitivity = 3.0f))
    }
}
