package com.custom.minimizer.battery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BatteryMonitorTest {

    // ── computeLevelPct ──────────────────────────────────────────────────────

    @Test
    fun `computeLevelPct returns correct percent for typical values`() {
        assertEquals(50, BatteryMonitor.computeLevelPct(50, 100))
        assertEquals(75, BatteryMonitor.computeLevelPct(3, 4))
        assertEquals(100, BatteryMonitor.computeLevelPct(100, 100))
        assertEquals(0, BatteryMonitor.computeLevelPct(0, 100))
    }

    @Test
    fun `computeLevelPct truncates integer division`() {
        // 1/3 * 100 = 33.33… → 33 (integer truncation, not rounding)
        assertEquals(33, BatteryMonitor.computeLevelPct(1, 3))
    }

    @Test
    fun `computeLevelPct returns -1 when level is unavailable`() {
        assertEquals(-1, BatteryMonitor.computeLevelPct(-1, 100))
    }

    @Test
    fun `computeLevelPct returns -1 when scale is zero`() {
        assertEquals(-1, BatteryMonitor.computeLevelPct(50, 0))
    }

    @Test
    fun `computeLevelPct returns -1 when scale is negative`() {
        assertEquals(-1, BatteryMonitor.computeLevelPct(50, -1))
    }

    @Test
    fun `computeLevelPct returns -1 when both level and scale are unavailable`() {
        assertEquals(-1, BatteryMonitor.computeLevelPct(-1, -1))
    }

    // ── shouldRearm ──────────────────────────────────────────────────────────

    @Test
    fun `shouldRearm is true when device is charging`() {
        assertTrue(BatteryMonitor.shouldRearm(pct = 15, charging = true, threshold = 20))
    }

    @Test
    fun `shouldRearm is true when battery recovers above threshold`() {
        assertTrue(BatteryMonitor.shouldRearm(pct = 21, charging = false, threshold = 20))
    }

    @Test
    fun `shouldRearm is false when unplugged and at or below threshold`() {
        assertFalse(BatteryMonitor.shouldRearm(pct = 20, charging = false, threshold = 20))
        assertFalse(BatteryMonitor.shouldRearm(pct = 10, charging = false, threshold = 20))
    }

    @Test
    fun `shouldRearm threshold boundary - pct exactly at threshold is not a rearm`() {
        assertFalse(BatteryMonitor.shouldRearm(pct = 20, charging = false, threshold = 20))
    }

    @Test
    fun `shouldRearm threshold boundary - pct one above threshold triggers rearm`() {
        assertTrue(BatteryMonitor.shouldRearm(pct = 21, charging = false, threshold = 20))
    }

    // ── hysteresis (capy#19): warn low, only clear once recovered ─────────────

    @Test
    fun `recovery line sits above the warn threshold so it can't flap`() {
        assertTrue(BatteryMonitor.DEFAULT_RECOVERY > BatteryMonitor.DEFAULT_THRESHOLD)
    }

    @Test
    fun `between warn and recovery is the dead zone - neither low nor recovered`() {
        val warn = BatteryMonitor.DEFAULT_THRESHOLD
        val rec = BatteryMonitor.DEFAULT_RECOVERY
        // 50% unplugged: not recovered (below recovery line) ...
        assertFalse(BatteryMonitor.shouldRearm(pct = 50, charging = false, threshold = rec))
        // ... and above the warn line, so it wouldn't (re-)fire a low alert.
        assertTrue(50 > warn)
    }

    @Test
    fun `clears only at or above the recovery line when unplugged`() {
        val rec = BatteryMonitor.DEFAULT_RECOVERY
        assertFalse(BatteryMonitor.shouldRearm(pct = rec, charging = false, threshold = rec))
        assertTrue(BatteryMonitor.shouldRearm(pct = rec + 1, charging = false, threshold = rec))
    }

    @Test
    fun `charging clears the alert at any level`() {
        assertTrue(BatteryMonitor.shouldRearm(pct = 5, charging = true, threshold = BatteryMonitor.DEFAULT_RECOVERY))
    }
}
