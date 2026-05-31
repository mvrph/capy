package com.custom.minimizer.update

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateCheckerTest {

    // ── isNewer ──────────────────────────────────────────────────────────────

    @Test
    fun `isNewer returns true when manifest version is higher than installed`() {
        assertTrue(UpdateChecker.isNewer(manifestVersionCode = 2, installedVersionCode = 1))
    }

    @Test
    fun `isNewer returns false when manifest version equals installed`() {
        assertFalse(UpdateChecker.isNewer(manifestVersionCode = 1, installedVersionCode = 1))
    }

    @Test
    fun `isNewer returns false when manifest version is older than installed`() {
        assertFalse(UpdateChecker.isNewer(manifestVersionCode = 1, installedVersionCode = 2))
    }

    @Test
    fun `isNewer returns false for missing-versionCode sentinel -1`() {
        // optInt default when field is absent; must not trigger a download
        assertFalse(UpdateChecker.isNewer(manifestVersionCode = -1, installedVersionCode = 1))
    }

    @Test
    fun `isNewer returns false when both version codes are the sentinel -1`() {
        assertFalse(UpdateChecker.isNewer(manifestVersionCode = -1, installedVersionCode = -1))
    }

    @Test
    fun `isNewer handles large version jumps correctly`() {
        assertTrue(UpdateChecker.isNewer(manifestVersionCode = 100, installedVersionCode = 2))
    }
}
